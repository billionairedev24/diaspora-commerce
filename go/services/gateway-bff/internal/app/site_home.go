package app

import (
	"context"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Home-page product rules. Everything a rule (tile cell, rail, personalised rail) puts in front of a shopper is
// eligible: in stock and with a real photograph. Placeholders, icons and missing images never qualify.

const (
	// BasedOnOrdersRailID and RecentlyViewedRailID are the ids of the personalised rails.
	BasedOnOrdersRailID = "based-on-orders"
	// RecentlyViewedLimit is how many cards the recently viewed rail shows at most; RecentlyViewedStored is how many
	// remembered ids (the browser keeps 20) are resolved, so ids that drop out (sold out, held, a duplicate of a
	// product already listed) do not leave the rail short while older products are still eligible.
	RecentlyViewedLimit  = 12
	RecentlyViewedStored = 20

	ruleCandidates = 12
	ordersLookback = 10
	ordersProducts = 8
	ordersRailSize = 12
	minRuleCells   = 2
)

// realPhoto reports whether u points at an actual photograph. It is the ONE shared rule (searchindex.PhotoOK): the indexer
// stores it as photo_ok and search counts with it, so nav, footer, sitemap and the home agree with what a shopper is shown.
func realPhoto(u *string) bool { return u != nil && searchindex.PhotoOK(*u) }

// eligibleCard is the home eligibility rule: in stock and a real photo.
func eligibleCard(p domain.ProductSummary) bool {
	return p.InStock && realPhoto(p.ImageURL)
}

// taxResolved is the tax half of the eligibility rule: nothing is shown without a stored, definite tax decision, so
// every variant must be READY. Catalog already answers 404 for held products; this keeps a product that was held after
// it was cached, or a catalog that omits the status, off home rails (never guessed: a missing status is not READY).
func taxResolved(p port.CatalogProduct) bool {
	for _, v := range p.Variants {
		if v.TaxStatus != "READY" {
			return false
		}
	}
	return len(p.Variants) > 0
}

func eligibleOnly(in []domain.ProductSummary) []domain.ProductSummary {
	out := make([]domain.ProductSummary, 0, len(in))
	for _, p := range in {
		if eligibleCard(p) {
			out = append(out, p)
		}
	}
	return out
}

// validRuleSource says whether a content-supplied source can be resolved (unknown kinds are ignored, not guessed at).
func validRuleSource(src *domain.RailSource) bool {
	if src == nil {
		return false
	}
	switch src.Kind {
	case "search":
		return strings.TrimSpace(src.Query) != "" || strings.TrimSpace(src.Department) != ""
	case "department-bestsellers":
		return strings.TrimSpace(src.Department) != ""
	case "fulfilled-fastest", "near-you", "deliverable-fast":
		return true
	case "community":
		return strings.TrimSpace(src.Community) != ""
	case "atins":
		return len(src.ATINs) > 0
	}
	return false
}

// activeHero keeps the slides whose optional window contains now, in content's order. An unreadable bound is treated as
// absent so a typo never hides the whole carousel.
func activeHero(slides []domain.HeroSlide, now time.Time) []domain.HeroSlide {
	out := make([]domain.HeroSlide, 0, len(slides))
	for _, h := range slides {
		if t, ok := parseBound(h.StartsAt); ok && now.Before(t) {
			continue
		}
		if t, ok := parseBound(h.EndsAt); ok && !now.Before(t) {
			continue
		}
		out = append(out, h)
	}
	return out
}

func parseBound(s *string) (time.Time, bool) {
	if s == nil || strings.TrimSpace(*s) == "" {
		return time.Time{}, false
	}
	t, err := time.Parse(time.RFC3339, strings.TrimSpace(*s))
	return t, err == nil
}

// resolveTiles turns rule cells into product cells. Candidates for every rule are fetched concurrently; they are then
// assigned in page order so a product appears in one cell only. A rule with no eligible product drops its cell, and a
// tile left with fewer than two cells (or none, for fixed tiles) is dropped rather than shown half empty.
func (s *siteServiceImpl) resolveTiles(ctx context.Context, tiles []domain.Tile, sc shopperScope) []domain.Tile {
	type slot struct{ tile, cell int }
	var rules []slot
	for ti, t := range tiles {
		for ci, c := range t.Cells {
			if validRuleSource(c.Source) {
				rules = append(rules, slot{ti, ci})
			}
		}
	}
	candidates := make([][]domain.ProductSummary, len(rules))
	var wg sync.WaitGroup
	for i, r := range rules {
		wg.Go(func() {
			src := *tiles[r.tile].Cells[r.cell].Source
			src.Limit = ruleCandidates
			items, err := s.railItems(ctx, src, sc)
			if err != nil {
				s.d.Log.InfoContext(ctx, "tile rule not resolved", "tile", tiles[r.tile].ID, "cell", r.cell, "error", err)
				return
			}
			candidates[i] = items
		})
	}
	wg.Wait()

	used := map[string]bool{}
	chosen := map[slot]*domain.ProductSummary{}
	for i, r := range rules {
		for _, p := range candidates[i] {
			if !used[p.ATIN] {
				used[p.ATIN] = true
				chosen[r] = &p
				break
			}
		}
	}
	out := make([]domain.Tile, 0, len(tiles))
	for ti, t := range tiles {
		cells := make([]domain.TileCell, 0, len(t.Cells))
		ruled := false
		for ci, c := range t.Cells {
			if !validRuleSource(c.Source) {
				c.Source = nil
				cells = append(cells, c)
				continue
			}
			ruled = true
			if p := chosen[slot{ti, ci}]; p != nil {
				cells = append(cells, domain.TileCell{Label: p.Title, Href: "/p/" + p.ATIN, ImageURL: p.ImageURL, ATIN: p.ATIN})
			}
		}
		if len(cells) == 0 || (ruled && len(cells) < minRuleCells) {
			continue
		}
		t.Cells = cells
		out = append(out, t)
	}
	return out
}

// RecentlyViewed resolves the ATINs the browser remembers into cards: unknown, unavailable, held-for-tax and photo-less
// products are dropped, order is kept (most recent first) and a product is listed once however many of its variant or
// product ids were remembered (older browsers stored the variant ATIN the shopper picked). Cheap by design: no storage,
// one catalog and one inventory read per product.
func (s *siteServiceImpl) RecentlyViewed(ctx context.Context, atins []string) ([]domain.ProductSummary, error) {
	seen := map[string]bool{}
	ids := make([]string, 0, len(atins))
	for _, a := range atins {
		a = strings.ToUpper(strings.TrimSpace(a))
		if atinRe.MatchString(a) && !seen[a] && len(ids) < RecentlyViewedStored {
			seen[a] = true
			ids = append(ids, a)
		}
	}
	if len(ids) == 0 {
		return []domain.ProductSummary{}, nil
	}
	items := eligibleOnly(s.byATIN(ctx, ids, RecentlyViewedStored, s.d.Clock.now()))
	return items[:min(len(items), RecentlyViewedLimit)], nil
}

// basedOnOrders is the signed-in shopper's "Based on your orders" rail: the departments of what they bought most
// (newest orders first), filled with the best-rated eligible products there that they have not bought yet. Nil when the
// shopper is a guest, has no orders, or nothing eligible is found.
func (s *siteServiceImpl) basedOnOrders(ctx context.Context, c *domain.Caller, sc shopperScope) *domain.Rail {
	if s.d.Orders == nil || c == nil || !c.Authenticated() {
		return nil
	}
	page, err := s.d.Orders.List(ctx, c.BearerToken, "", ordersLookback)
	if err != nil || len(page.Items) == 0 {
		return nil
	}
	bought := map[string]bool{}
	var order []string
	for _, o := range page.Items {
		for _, sh := range o.Shipments {
			for _, l := range sh.Lines {
				a := strings.ToUpper(strings.TrimSpace(l.ProductATIN))
				if a != "" && !bought[a] {
					bought[a] = true
					order = append(order, a)
				}
			}
		}
	}
	if len(order) == 0 {
		return nil
	}
	if len(order) > ordersProducts {
		order = order[:ordersProducts]
	}
	depts := make([]string, len(order))
	var wg sync.WaitGroup
	for i, a := range order {
		wg.Go(func() {
			if p, _, err := resolveProduct(ctx, s.d.Catalog, a); err == nil {
				depts[i] = departmentFor(ctx, s.d.Catalog, p.CategoryID)
			}
		})
	}
	wg.Wait()
	top := topDepartments(depts, 2)
	if len(top) == 0 {
		return nil
	}
	pools := make([][]domain.ProductSummary, len(top))
	for i, d := range top {
		wg.Go(func() {
			items, err := s.railItems(ctx, domain.RailSource{Kind: "department-bestsellers", Department: d, Limit: ordersRailSize}, sc)
			if err != nil {
				return
			}
			pools[i] = items
		})
	}
	wg.Wait()
	items := interleave(pools, bought, ordersRailSize)
	if len(items) == 0 {
		return nil
	}
	return &domain.Rail{ID: BasedOnOrdersRailID, Title: "Based on your orders", Href: "/s?dept=" + url.QueryEscape(top[0]), Items: items}
}

// topDepartments returns up to n departments by how often they occur, ties broken by first appearance (newest order).
func topDepartments(depts []string, n int) []string {
	count := map[string]int{}
	var first []string
	for _, d := range depts {
		if d == "" {
			continue
		}
		if count[d] == 0 {
			first = append(first, d)
		}
		count[d]++
	}
	sort.SliceStable(first, func(i, j int) bool { return count[first[i]] > count[first[j]] })
	if len(first) > n {
		first = first[:n]
	}
	return first
}

// interleave takes products from each pool in turn, skipping duplicates and anything in skip, up to limit.
func interleave(pools [][]domain.ProductSummary, skip map[string]bool, limit int) []domain.ProductSummary {
	out := []domain.ProductSummary{}
	seen := map[string]bool{}
	for i := 0; len(out) < limit; i++ {
		progressed := false
		for _, pool := range pools {
			if i >= len(pool) {
				continue
			}
			progressed = true
			p := pool[i]
			if skip[p.ATIN] || seen[p.ATIN] || len(out) >= limit {
				continue
			}
			seen[p.ATIN] = true
			out = append(out, p)
		}
		if !progressed {
			break
		}
	}
	return out
}
