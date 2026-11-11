package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The home composition. Staff place modules as content placements (slots home_product_row, display_banner,
// home_brand_banner, home_sponsored_products, home_ad_row, home_advertorial and home_block); the page body is those
// placements in priority order (highest first, content's order for ties). Every module is resolved here, so a module
// with nothing to show is dropped, a product is on the page once, and a paid module is always marked sponsored.

var placementIDRe = regexp.MustCompile(`^plc_[A-Za-z0-9_]{1,60}$`)

const (
	// MinRailItems is the fewest products a rail needs to be shown; a placement may lower or raise it (minItems).
	MinRailItems = 4
	// minSponsoredItems is the fewest products a sponsored products rail needs.
	minSponsoredItems = 2
	// dealMinPercent is the smallest discount a deal shows (list price against the offer price).
	dealMinPercent = 10
	blockProducts  = 24
)

// blockPayload is the union of the payload fields of the module slots (each slot's schema allows its own subset).
type blockPayload struct {
	Type          string            `json:"type"`
	Title         string            `json:"title"`
	Headline      string            `json:"headline"`
	Sub           string            `json:"sub"`
	Text          string            `json:"text"`
	CTA           string            `json:"cta"`
	Href          string            `json:"href"`
	Tone          string            `json:"tone"`
	Advertiser    string            `json:"advertiser"`
	ImageURL      *string           `json:"imageUrl"`
	LogoURL       *string           `json:"logoUrl"`
	SKUs          []string          `json:"skus"`
	Query         string            `json:"query"`
	Department    string            `json:"department"`
	Community     string            `json:"community"`
	Limit         int               `json:"limit"`
	MinItems      int               `json:"minItems"`
	MaxDays       int               `json:"maxDays"`
	MaxPerSession int               `json:"maxPerSession"`
	States        []string          `json:"states"`
	Audience      string            `json:"audience"`
	EndsAt        string            `json:"endsAt"`
	Ads           []domain.BlockAd  `json:"ads"`
	Cells         []domain.TileCell `json:"cells"`
}

type bodyEntry struct {
	slot       string
	id         string
	advertiser string
	rail       *domain.RailPlacement
	banner     *domain.Banner
	p          blockPayload
}

// resolved is a module with the products it would claim; nil block = hidden.
type resolved struct {
	block *domain.HomeBlock
	min   int
	paid  bool
}

// orderedBody lists the body placements in page order and drops those that do not apply to this shopper (states,
// audience). Without body placements (an older content service, or the fallback rails) it is the banners then the rails.
func orderedBody(pl domain.Placements, loc domain.DeliveryLocation, signedIn bool) []bodyEntry {
	var out []bodyEntry
	if len(pl.Body) == 0 {
		for i := range pl.Banners {
			out = append(out, bodyEntry{slot: "display_banner", id: pl.Banners[i].ID, banner: &pl.Banners[i]})
		}
		for i := range pl.Rails {
			out = append(out, bodyEntry{slot: "home_product_row", id: pl.Rails[i].ID, rail: &pl.Rails[i]})
		}
		return out
	}
	body := append([]domain.BodyPlacement(nil), pl.Body...)
	sort.SliceStable(body, func(i, j int) bool { return body[i].Priority > body[j].Priority })
	for _, b := range body {
		e := bodyEntry{slot: b.Slot, id: b.ID, advertiser: b.Advertiser}
		switch b.Slot {
		case "home_product_row":
			for i := range pl.Rails {
				if pl.Rails[i].ID == b.ID {
					e.rail = &pl.Rails[i]
				}
			}
			if e.rail == nil {
				continue
			}
		case "display_banner":
			for i := range pl.Banners {
				if pl.Banners[i].ID == b.ID {
					e.banner = &pl.Banners[i]
				}
			}
			if e.banner == nil {
				continue
			}
		default:
			if err := json.Unmarshal(b.Payload, &e.p); err != nil {
				continue // a payload this build cannot read is left out, never guessed at
			}
			if e.p.Advertiser == "" {
				e.p.Advertiser = b.Advertiser
			}
			if !stateAllowed(e.p.States, loc.State) || !audienceAllowed(e.p.Audience, signedIn) {
				continue
			}
		}
		out = append(out, e)
	}
	return out
}

// stateAllowed is the state targeting of a module: no states means everywhere; otherwise the shopper's state must be listed.
func stateAllowed(states []string, state string) bool {
	if len(states) == 0 {
		return true
	}
	for _, s := range states {
		if state != "" && strings.EqualFold(strings.TrimSpace(s), state) {
			return true
		}
	}
	return false
}

func audienceAllowed(audience string, signedIn bool) bool {
	switch audience {
	case "signed-in":
		return signedIn
	case "signed-out":
		return !signedIn
	}
	return true
}

var paidSlots = map[string]string{"home_brand_banner": domain.BlockBrandBanner, "home_sponsored_products": domain.BlockSponsoredProducts,
	"home_ad_row": domain.BlockAdRow, "home_advertorial": domain.BlockAdvertorial}

// defaultCap is the per-session impression cap of paid modules that do not set one (maxPerSession).
var defaultCap = map[string]int{domain.BlockBrandBanner: 3, domain.BlockAdvertorial: 3, domain.BlockAdRow: 5}

// composeBody builds the page body. used holds what the tiles already show; it is extended with every product the body
// shows. Sponsored products and brand-banner strips claim their products before organic modules, so an advertised
// product is not repeated further down as an organic card.
func (s *siteServiceImpl) composeBody(ctx context.Context, c *domain.Caller, loc domain.DeliveryLocation, pl domain.Placements, sc shopperScope, now time.Time, used map[string]bool) []domain.HomeBlock {
	signedIn := c != nil && c.Authenticated()
	entries := orderedBody(pl, loc, signedIn)
	if !hasBlockType(entries, domain.BlockBasedOnOrders) {
		entries = append([]bodyEntry{{slot: "home_block", id: BasedOnOrdersRailID, p: blockPayload{Type: domain.BlockBasedOnOrders}}}, entries...)
	}
	if !hasBlockType(entries, domain.BlockRecentlyViewed) {
		entries = append(entries, bodyEntry{slot: "home_block", id: "recently-viewed", p: blockPayload{Type: domain.BlockRecentlyViewed}})
	}
	res := make([]resolved, len(entries))
	var wg sync.WaitGroup
	for i, e := range entries {
		wg.Go(func() { res[i] = s.resolveEntry(ctx, c, loc, sc, now, e) })
	}
	wg.Wait()

	// Paid products claim first.
	for i, r := range res {
		if r.block == nil || !r.paid {
			continue
		}
		if r.block.Rail != nil {
			free := unclaimed(r.block.Rail.Items, used)
			if len(free) < r.min {
				res[i].block = nil
				continue
			}
			r.block.Rail.Items = claim(free, used)
		}
		for _, p := range r.block.Products {
			used[p.ATIN] = true
		}
	}
	out := make([]domain.HomeBlock, 0, len(res))
	for _, r := range res {
		if r.block == nil {
			continue
		}
		if r.block.Rail != nil && !r.paid {
			// A rail that ends up too short is hidden and must not use up products another module could show.
			free := unclaimed(r.block.Rail.Items, used)
			if len(free) < r.min {
				continue
			}
			r.block.Rail.Items = claim(free, used)
		}
		out = append(out, *r.block)
	}
	return out
}

func hasBlockType(entries []bodyEntry, t string) bool {
	for _, e := range entries {
		if e.slot == "home_block" && e.p.Type == t {
			return true
		}
	}
	return false
}

// unclaimed is the products not used yet, without marking them.
func unclaimed(items []domain.ProductSummary, used map[string]bool) []domain.ProductSummary {
	out := make([]domain.ProductSummary, 0, len(items))
	for _, p := range items {
		if !used[p.ATIN] {
			out = append(out, p)
		}
	}
	return out
}

// claim keeps the products not used yet and marks them used.
func claim(items []domain.ProductSummary, used map[string]bool) []domain.ProductSummary {
	kept := make([]domain.ProductSummary, 0, len(items))
	for _, p := range items {
		if !used[p.ATIN] {
			used[p.ATIN] = true
			kept = append(kept, p)
		}
	}
	return kept
}

func (s *siteServiceImpl) resolveEntry(ctx context.Context, c *domain.Caller, loc domain.DeliveryLocation, sc shopperScope, now time.Time, e bodyEntry) resolved {
	switch e.slot {
	case "home_product_row":
		items, err := s.railItems(ctx, e.rail.Source, sc)
		if err != nil {
			s.d.Log.InfoContext(ctx, "rail not resolved", "rail", e.id, "error", err)
			return resolved{}
		}
		return resolved{min: firstPositive(e.rail.MinItems, MinRailItems), block: &domain.HomeBlock{ID: e.id, Type: domain.BlockRail,
			Rail: &domain.Rail{ID: e.id, Title: s.railTitle(e.rail.Title, loc, items, now), Href: e.rail.Href, Items: items}}}
	case "display_banner":
		b := e.banner
		return resolved{block: &domain.HomeBlock{ID: e.id, Type: domain.BlockBanner, Sponsored: true, Advertiser: b.Advertiser, Title: b.Title, Sub: b.Sub,
			CTA: b.CTA, Href: b.Href, ImageURL: b.ImageURL}}
	case "home_brand_banner", "home_sponsored_products", "home_ad_row", "home_advertorial":
		return s.resolvePaid(ctx, sc, e)
	case "home_block":
		return s.resolveBlock(ctx, c, loc, sc, now, e)
	}
	return resolved{}
}

func capFor(e bodyEntry, t string) int {
	if e.p.MaxPerSession > 0 {
		return e.p.MaxPerSession
	}
	return defaultCap[t]
}

// resolvePaid builds the four paid modules. Every one is sponsored, names its advertiser and is dropped without one.
func (s *siteServiceImpl) resolvePaid(ctx context.Context, sc shopperScope, e bodyEntry) resolved {
	t := paidSlots[e.slot]
	p := e.p
	if strings.TrimSpace(p.Advertiser) == "" {
		return resolved{} // an unlabelled ad is never shown
	}
	b := &domain.HomeBlock{ID: e.id, Type: t, Sponsored: true, Advertiser: p.Advertiser, MaxPerSession: capFor(e, t)}
	switch t {
	case domain.BlockBrandBanner:
		if p.Headline == "" || p.Href == "" {
			return resolved{}
		}
		b.Title, b.Sub, b.CTA, b.Href, b.ImageURL, b.LogoURL, b.Tone = p.Headline, p.Sub, firstNonEmpty(p.CTA, "Shop now"), p.Href, p.ImageURL, p.LogoURL, p.Tone
		if len(p.SKUs) > 0 {
			skus := p.SKUs
			b.Products = eligibleOnly(s.byATIN(ctx, skus[:min(len(skus), 3)], 3, s.d.Clock.now()))
		}
		return resolved{block: b, paid: true}
	case domain.BlockAdvertorial:
		if p.Headline == "" || p.Href == "" {
			return resolved{}
		}
		b.Title, b.Body, b.CTA, b.Href, b.ImageURL = p.Headline, p.Text, firstNonEmpty(p.CTA, "Read more"), p.Href, p.ImageURL
		return resolved{block: b, paid: true}
	case domain.BlockAdRow:
		for _, a := range p.Ads {
			if a.ImageURL != "" && a.Href != "" && len(b.Ads) < 3 {
				a.Advertiser = firstNonEmpty(a.Advertiser, p.Advertiser)
				b.Ads = append(b.Ads, a)
			}
		}
		if len(b.Ads) < 2 {
			return resolved{}
		}
		return resolved{block: b, paid: true}
	case domain.BlockSponsoredProducts:
		items, searchID := s.sponsoredProducts(ctx, p)
		if len(items) == 0 {
			return resolved{}
		}
		b.Title, b.Href, b.SearchID = firstNonEmpty(p.Title, "Sponsored products"), firstNonEmpty(p.Href, "/s"), searchID
		b.Rail = &domain.Rail{ID: e.id, Title: b.Title, Href: b.Href, Items: items}
		return resolved{block: b, paid: true, min: minSponsoredItems}
	}
	return resolved{}
}

// sponsoredProducts asks search for the products sellers promote for the module's words or department, through the
// same ad auction as search results (which records the impressions and signs the click tokens). Only the sponsored hits
// are used, and only when eligible (in stock, real photo).
func (s *siteServiceImpl) sponsoredProducts(ctx context.Context, p blockPayload) ([]domain.ProductSummary, string) {
	if s.d.Search == nil {
		return nil, ""
	}
	res, err := s.d.Search.Search(ctx, port.SearchParams{Q: strings.TrimSpace(p.Query), Market: s.d.Market, Department: p.Department,
		Sort: domain.SortRelevance, Size: blockProducts, InStock: true, Ads: true, NoTrack: true})
	if err != nil {
		s.d.Log.InfoContext(ctx, "sponsored products unavailable; module omitted", "error", err)
		return nil, ""
	}
	cards := []domain.ProductSummary{}
	for _, h := range res.Hits {
		if h.Sponsored == nil {
			continue
		}
		if card := summaryFromHit(h, s.d.Estimator, s.d.Clock.now()); eligibleCard(card) {
			cards = append(cards, card)
		}
	}
	return eligibleOnly(withOffers(ctx, s.d.Offers, cards)), res.SearchID
}

func (s *siteServiceImpl) resolveBlock(ctx context.Context, c *domain.Caller, loc domain.DeliveryLocation, sc shopperScope, now time.Time, e bodyEntry) resolved {
	p := e.p
	b := &domain.HomeBlock{ID: e.id, Type: p.Type, Title: p.Title, Sub: p.Sub, CTA: p.CTA, Href: p.Href, ImageURL: p.ImageURL, Tone: p.Tone}
	limit := p.Limit
	if limit <= 0 || limit > blockProducts {
		limit = blockProducts
	}
	switch p.Type {
	case domain.BlockBasedOnOrders:
		if r := s.basedOnOrders(ctx, c, sc); r != nil {
			b.Rail = r
			return resolved{block: b, min: firstPositive(p.MinItems, 2)}
		}
	case domain.BlockRecentlyViewed, domain.BlockPreorders:
		return resolved{block: b} // the browser (its own history) or the pre-order service decides whether there is anything to show
	case domain.BlockDeals:
		items := s.deals(ctx, p, sc, limit)
		if len(items) == 0 {
			return resolved{}
		}
		b.Title = s.railTitle(firstNonEmpty(p.Title, "Deals of the day"), loc, items, now)
		b.Href = firstNonEmpty(p.Href, "/s?sort=rating")
		b.EndsAt = firstNonEmpty(validRFC3339(p.EndsAt), endOfDay(now))
		b.Rail = &domain.Rail{ID: e.id, Title: b.Title, Href: b.Href, Items: items}
		return resolved{block: b, min: firstPositive(p.MinItems, 3)}
	case domain.BlockCommunity:
		return s.communitySpotlight(ctx, e, sc, loc, now, limit)
	case domain.BlockSeller:
		return s.sellerSpotlight(ctx, e, sc, loc, now, limit)
	case domain.BlockOccasions:
		b.Cells = s.occasionCells(ctx, p.Cells, sc)
		if len(b.Cells) >= 3 {
			return resolved{block: b}
		}
	case domain.BlockServices:
		if n := s.serviceCount(ctx, loc); n > 0 {
			b.Title = strings.ReplaceAll(firstNonEmpty(p.Title, "Services near {city}"), "{city}", firstNonEmpty(loc.City, "you"))
			b.Sub = firstNonEmpty(p.Sub, "Book braiders, caterers, photographers and more from providers in your area.")
			b.CTA, b.Href = firstNonEmpty(p.CTA, "Browse services"), firstNonEmpty(p.Href, "/services")
			return resolved{block: b}
		}
	case domain.BlockMembership:
		if p.Title != "" && p.Href != "" {
			return resolved{block: b}
		}
	case domain.BlockSignIn:
		if p.Title != "" && p.Href != "" && !(c != nil && c.Authenticated()) {
			return resolved{block: b}
		}
	}
	return resolved{}
}

func validRFC3339(s string) string {
	if _, err := time.Parse(time.RFC3339, strings.TrimSpace(s)); err != nil {
		return ""
	}
	return strings.TrimSpace(s)
}

// endOfDay is the next UTC midnight: the day's deals rotate with the day (see shopperScope).
func endOfDay(now time.Time) string {
	n := now.UTC()
	return time.Date(n.Year(), n.Month(), n.Day()+1, 0, 0, 0, 0, time.UTC).Format(time.RFC3339)
}

// discountPercent is the whole-number saving of the offer price against its list (compare-at) price, 0 when there is none.
func discountPercent(p domain.ProductSummary) int {
	if p.ListPriceMinor == nil || *p.ListPriceMinor <= p.PriceMinor || p.PriceMinor <= 0 {
		return 0
	}
	return int((*p.ListPriceMinor - p.PriceMinor) * 100 / *p.ListPriceMinor)
}

// deals are in-stock products with a real photo and at least dealMinPercent off their list price, biggest discount
// first; the daily rotation of the pool keeps it fresh without staff work.
func (s *siteServiceImpl) deals(ctx context.Context, p blockPayload, sc shopperScope, limit int) []domain.ProductSummary {
	pool, err := s.resolveRail(ctx, domain.RailSource{Kind: "search", Query: p.Query, Department: p.Department, Sort: domain.SortRating, Limit: blockProducts}, sc)
	if err != nil {
		return nil
	}
	return dealsFrom(pool, limit)
}

func dealsFrom(pool []domain.ProductSummary, limit int) []domain.ProductSummary {
	out := []domain.ProductSummary{}
	for _, c := range pool {
		if eligibleCard(c) && discountPercent(c) >= dealMinPercent {
			out = append(out, c)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return discountPercent(out[i]) > discountPercent(out[j]) })
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

// communitySpotlight features one community: the placement's, or by rule one of the communities that have enough
// products in stock, rotating with the day and the shopper's place.
func (s *siteServiceImpl) communitySpotlight(ctx context.Context, e bodyEntry, sc shopperScope, loc domain.DeliveryLocation, now time.Time, limit int) resolved {
	key := strings.ToLower(strings.TrimSpace(e.p.Community))
	if key == "" {
		res, err := s.d.Search.Search(ctx, port.SearchParams{Market: s.d.Market, Size: 1, InStock: true, NoTrack: true})
		if err != nil {
			return resolved{}
		}
		key = pickCommunity(res.Facets, firstPositive(e.p.MinItems, MinRailItems), sc.seed)
	}
	if key == "" {
		return resolved{}
	}
	items, err := s.railItems(ctx, domain.RailSource{Kind: "community", Community: key, Limit: limit}, sc)
	if err != nil || len(items) == 0 {
		return resolved{}
	}
	name := titleCase(key)
	title := strings.ReplaceAll(firstNonEmpty(e.p.Title, "Spotlight: the {community} kitchen"), "{community}", name)
	href := firstNonEmpty(e.p.Href, "/communities/"+url.PathEscape(key))
	b := &domain.HomeBlock{ID: e.id, Type: domain.BlockCommunity, Title: title, Sub: e.p.Sub, CTA: firstNonEmpty(e.p.CTA, "Explore "+name), Href: href,
		ImageURL: e.p.ImageURL, Rail: &domain.Rail{ID: e.id, Title: title, Href: href, Items: items}}
	return resolved{block: b, min: firstPositive(e.p.MinItems, MinRailItems)}
}

// pickCommunity chooses among the facet's communities with at least min products by a hash of the day's seed.
func pickCommunity(facets map[string][]port.SearchFacetBucket, min int, seed string) string {
	buckets := facets["communities"]
	if len(buckets) == 0 {
		buckets = facets["community"]
	}
	var keys []string
	for _, b := range buckets {
		if b.Count >= int64(min) && strings.TrimSpace(b.Value) != "" {
			keys = append(keys, strings.ToLower(strings.TrimSpace(b.Value)))
		}
	}
	if len(keys) == 0 {
		return ""
	}
	sort.Strings(keys)
	return keys[int(shopperScope{seed: seed}.hash("community")%uint64(len(keys)))]
}

func titleCase(s string) string {
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

// sellerSpotlight features the best rated seller whose products reach the shopper: products shipped from near them
// (or, with none, the best rated), grouped by seller; sellers with no reviews cannot win, and AfroTransact's own
// catalogue is not a "seller".
func (s *siteServiceImpl) sellerSpotlight(ctx context.Context, e bodyEntry, sc shopperScope, loc domain.DeliveryLocation, now time.Time, limit int) resolved {
	pool, err := s.resolveRail(ctx, domain.RailSource{Kind: "near-you", Department: e.p.Department, Limit: blockProducts}, sc)
	if err != nil || len(pool) == 0 {
		return resolved{}
	}
	name, items, avg, reviews := topSeller(pool, firstPositive(e.p.MinItems, 3))
	if name == "" {
		return resolved{}
	}
	if len(items) > limit {
		items = items[:limit]
	}
	title := strings.ReplaceAll(firstNonEmpty(e.p.Title, "Top-rated seller near {city}: {seller}"), "{seller}", name)
	title = strings.ReplaceAll(title, "{city}", firstNonEmpty(loc.City, "you"))
	href := firstNonEmpty(e.p.Href, "/s?q="+url.QueryEscape(name))
	b := &domain.HomeBlock{ID: e.id, Type: domain.BlockSeller, Title: title, Href: href, CTA: firstNonEmpty(e.p.CTA, "See their shop"),
		Sub: fmt.Sprintf("%.1f average from %d reviews", avg, reviews), Rail: &domain.Rail{ID: e.id, Title: title, Href: href, Items: items}}
	return resolved{block: b, min: firstPositive(e.p.MinItems, 3)}
}

// topSeller picks the marketplace seller with the best review-weighted rating among those with at least min products.
func topSeller(pool []domain.ProductSummary, min int) (string, []domain.ProductSummary, float64, int) {
	type agg struct {
		items   []domain.ProductSummary
		stars   float64
		reviews int
	}
	by := map[string]*agg{}
	var order []string
	for _, p := range pool {
		if p.SellerKind != domain.SellerKindSeller || p.SellerName == "" || p.ReviewCount <= 0 {
			continue
		}
		a := by[p.SellerName]
		if a == nil {
			a = &agg{}
			by[p.SellerName] = a
			order = append(order, p.SellerName)
		}
		a.items = append(a.items, p)
		a.stars += p.Rating * float64(p.ReviewCount)
		a.reviews += p.ReviewCount
	}
	best, bestAvg := "", 0.0
	for _, n := range order {
		a := by[n]
		if len(a.items) < min {
			continue
		}
		if avg := a.stars / float64(a.reviews); avg > bestAvg {
			best, bestAvg = n, avg
		}
	}
	if best == "" {
		return "", nil, 0, 0
	}
	return best, by[best].items, bestAvg, by[best].reviews
}

// occasionCells fill "Shop by occasion": a fixed cell is kept as set; a rule cell keeps its label and link and borrows the
// photo of the best eligible product of its rule (so nothing is hard-coded per occasion).
func (s *siteServiceImpl) occasionCells(ctx context.Context, cells []domain.TileCell, sc shopperScope) []domain.BlockCell {
	out := make([]*domain.BlockCell, len(cells))
	var wg sync.WaitGroup
	for i, c := range cells {
		if c.Label == "" || c.Href == "" {
			continue
		}
		if !validRuleSource(c.Source) {
			out[i] = &domain.BlockCell{Label: c.Label, Href: c.Href, ImageURL: c.ImageURL}
			continue
		}
		wg.Go(func() {
			src := *c.Source
			src.Limit = 6
			items, err := s.railItems(ctx, src, sc)
			if err != nil || len(items) == 0 {
				return
			}
			out[i] = &domain.BlockCell{Label: c.Label, Href: c.Href, ImageURL: items[0].ImageURL}
		})
	}
	wg.Wait()
	res := make([]domain.BlockCell, 0, len(out))
	for _, c := range out {
		if c != nil {
			res = append(res, *c)
		}
	}
	return res
}

var zip5Home = func(z string) bool { return len(z) == 5 && strings.Trim(z, "0123456789") == "" }

// serviceCount is how many services providers list for the shopper's ZIP; zero (or a failing bookings service) hides the module.
func (s *siteServiceImpl) serviceCount(ctx context.Context, loc domain.DeliveryLocation) int {
	if s.d.Services == nil || !zip5Home(loc.ZIP) {
		return 0
	}
	list, err := s.d.Services.Search(ctx, "", "", loc.ZIP)
	if err != nil {
		return 0
	}
	return len(list)
}

// RecordAdEvent counts an impression or a click on a paid home module against its placement. Nothing identifies the
// shopper: the placement id and the kind are all that leave the gateway.
func (s *siteServiceImpl) RecordAdEvent(ctx context.Context, placementID, kind string) error {
	if !placementIDRe.MatchString(placementID) {
		return domain.ValidationProblem(map[string]string{"placementId": "Invalid placement"})
	}
	if kind != "impression" && kind != "click" {
		return domain.ValidationProblem(map[string]string{"event": "Must be impression or click"})
	}
	if err := s.d.Content.RecordPlacementEvent(ctx, placementID, kind); err != nil {
		return mapErr("content", err)
	}
	return nil
}
