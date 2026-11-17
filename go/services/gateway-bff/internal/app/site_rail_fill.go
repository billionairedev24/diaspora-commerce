package app

import (
	"context"
	"encoding/json"
	"hash/fnv"
	"slices"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Rail fill (docs/engineering/dynamic-home.md, "Filling short rails"). A rail with fewer cards than fit the row must not
// leave a dead area. Resolved here, with the rail, so the storefront needs no second request and nothing shifts after load.
const (
	// maxFilledRails bounds the extra upstream work one page may do for fills.
	maxFilledRails = 4
	// maxAuctionFills bounds sponsored-products auctions run for fills (each one records ad impressions).
	maxAuctionFills = 2
)

// railFillPayload is the payload of a rail_fill placement.
type railFillPayload struct {
	Advertiser    string   `json:"advertiser"`
	Headline      string   `json:"headline"`
	Text          string   `json:"text"`
	CTA           string   `json:"cta"`
	Href          string   `json:"href"`
	ImageURL      *string  `json:"imageUrl"`
	Departments   []string `json:"departments"`
	SKUs          []string `json:"skus"`
	States        []string `json:"states"`
	MaxPerSession int      `json:"maxPerSession"`
}

// railFillCandidates decodes the rail_fill placements: valid payloads only, an image only when it is a real photo, and
// a state filter applied. Products named by SKU are resolved once and must be eligible (in stock, real photo).
func (s *siteServiceImpl) railFillCandidates(ctx context.Context, pl domain.Placements, loc domain.DeliveryLocation, now time.Time) []domain.RailFillCandidate {
	var out []domain.RailFillCandidate
	for _, bp := range pl.RailFill {
		var p railFillPayload
		if json.Unmarshal(bp.Payload, &p) != nil || strings.TrimSpace(p.Headline) == "" || strings.TrimSpace(p.Href) == "" {
			continue
		}
		if len(p.States) > 0 && !stateAllowed(p.States, loc.State) {
			continue
		}
		adv := strings.TrimSpace(firstNonEmpty(p.Advertiser, bp.Advertiser))
		tile := domain.RailFill{ID: bp.ID, Advertiser: adv, Sponsored: adv != "", Title: p.Headline, Body: p.Text, CTA: p.CTA,
			Href: p.Href, MaxPerSession: p.MaxPerSession}
		if realPhoto(p.ImageURL) {
			tile.ImageURL = p.ImageURL
		}
		if len(p.SKUs) > 0 {
			tile.Products = eligibleOnly(s.byATIN(ctx, p.SKUs[:min(len(p.SKUs), 3)], 3, now))
		}
		out = append(out, domain.RailFillCandidate{Tile: tile, Priority: bp.Priority, Departments: p.Departments})
	}
	return out
}

// railDepartment is the department most of the rail's products belong to.
func railDepartment(items []domain.ProductSummary) string {
	count := map[string]int{}
	best := ""
	for _, p := range items {
		d := strings.TrimSpace(p.Department)
		if d == "" {
			continue
		}
		count[d]++
		if best == "" || count[d] > count[best] {
			best = d
		}
	}
	return best
}

// relaxableSource is whether a content rail of this source kind keeps its meaning when topped up with more products of its department.
func relaxableSource(kind string) bool {
	switch kind {
	case "search", "department-bestsellers", "community":
		return true
	}
	return false
}

// relaxMode says how a rail may be topped up with more products: not at all (personal, delivery-promise and spotlight
// rails would be mislabelled), only with real deals (a deals rail), or with popular in-stock products of its department.
func relaxMode(b domain.HomeBlock) (ok bool, dealsOnly bool) {
	switch b.Type {
	case domain.BlockDeals:
		return true, true
	case domain.BlockRail:
		if b.Rail != nil && b.Rail.Relaxable {
			return true, false
		}
		mod := b.Module
		if b.Tracking != nil && mod == "" {
			mod = b.Tracking.Module
		}
		return mod == domain.ModGroceries || mod == domain.ModDeptRail, false
	}
	return false, false
}

// fillable says whether a block is an organic product rail that may get a fill.
func fillable(b domain.HomeBlock) bool {
	if b.Rail == nil || b.Sponsored {
		return false
	}
	switch b.Type {
	case domain.BlockRail, domain.BlockBasedOnOrders, domain.BlockDeals, domain.BlockCommunity, domain.BlockSeller:
		return true
	}
	return false
}

// fillRails fills every short organic rail of the page, in page order. used holds what the page shows (extended with
// products added). quiet (crawler, staff preview) never runs the sponsored auction, which would record impressions.
func (s *siteServiceImpl) fillRails(ctx context.Context, blocks []domain.HomeBlock, pl domain.Placements, sc shopperScope, used map[string]bool, quiet bool, now time.Time) {
	var short []int
	for i, b := range blocks {
		if fillable(b) && len(b.Rail.Items) < domain.RailFillTarget && len(short) < maxFilledRails {
			short = append(short, i)
		}
	}
	if len(short) == 0 {
		return
	}
	cands := s.railFillCandidates(ctx, pl, sc.loc, now)
	auctions := 0
	for _, i := range short {
		b := &blocks[i]
		relax, deals := relaxMode(*b)
		res := s.fillOne(ctx, b.Rail.ID, b.Rail.Items, cands, sc, used, quiet, relax, deals, &auctions, now)
		b.Rail.Items = res.Items
		b.Rail.Fill = res.Fill
		for _, p := range res.Items {
			used[p.ATIN] = true
		}
		if res.Fill != nil {
			for _, p := range res.Fill.Products {
				used[p.ATIN] = true
			}
			if res.Fill.Sponsored && res.Fill.Kind == domain.RailFillPlacement {
				b.Rail.Fill.MaxPerSession = res.Fill.MaxPerSession
			}
		}
	}
}

// fillOne resolves the fill of one rail.
func (s *siteServiceImpl) fillOne(ctx context.Context, railID string, items []domain.ProductSummary, cands []domain.RailFillCandidate, sc shopperScope, used map[string]bool, quiet, relax, dealsOnly bool, auctions *int, now time.Time) domain.RailFillResult {
	dept := railDepartment(items)
	in := domain.RailFillInput{Items: items, Department: dept, Used: used, Placements: cands, Rotate: rotation(sc.seed, railID)}
	// (1) relaxed products: the same department, nearest sellers first, popular and in stock. Only fetched when needed.
	// A personal rail (based on orders) is never topped up: the shopper's bought products would come back.
	if relax {
		if rel, err := s.resolveRail(ctx, domain.RailSource{Kind: "department-bestsellers", Department: dept, Limit: 24}, sc); err == nil {
			pool := eligibleOnly(rel)
			if dealsOnly { // a "Deals of the day" rail only ever holds real deals
				pool = slices.DeleteFunc(slices.Clone(pool), func(p domain.ProductSummary) bool { return discountPercent(p) < dealMinPercent })
			}
			in.Relaxed = rankForShopper(pool, sc) // nearest sellers first, never filtered out
		}
	}
	if !quiet {
		in.Auction = func() *domain.RailFill {
			if *auctions >= maxAuctionFills {
				return nil
			}
			*auctions++
			cards, searchID := s.sponsoredProducts(ctx, blockPayload{Department: dept})
			if len(cards) == 0 {
				return nil
			}
			return &domain.RailFill{Kind: domain.RailFillSponsoredProducts, Sponsored: true, Advertiser: "Marketplace sellers", Title: dept,
				Href: "/s", Products: cards[:min(len(cards), 3)], SearchID: searchID}
		}
	}
	return domain.PickRailFill(in)
}

// rotation turns the visit seed and the rail into a stable number (a crawler's empty seed gives 0: first tile wins).
func rotation(seed, rail string) uint64 {
	if seed == "" {
		return 0
	}
	h := fnv.New64a()
	_, _ = h.Write([]byte(seed + "|" + rail))
	return h.Sum64()
}

// RecentlyViewedRail is the "Recently viewed" rail with its fill tile. The rail holds only what the shopper opened, so
// it is never topped up with other products (they would be mislabelled); a Sponsored or editorial tile fills the row.
func (s *siteServiceImpl) RecentlyViewedRail(ctx context.Context, c *domain.Caller, atins []string) ([]domain.ProductSummary, *domain.RailFill, error) {
	items, err := s.RecentlyViewed(ctx, atins)
	if err != nil || len(items) == 0 || len(items) >= domain.RailFillTarget {
		return items, nil, err
	}
	now := s.d.Clock.now()
	loc := s.d.Location.Current(ctx, c)
	pl, perr := s.d.Content.Placements(ctx, s.d.Market, loc.ZIP)
	if perr != nil {
		pl = domain.Placements{}
	}
	sc := newScope(loc, c, now)
	auctions := 0
	res := s.fillOne(ctx, "recently-viewed", items, s.railFillCandidates(ctx, pl, loc, now), sc, map[string]bool{}, false, false, false, &auctions, now)
	return items, res.Fill, nil
}
