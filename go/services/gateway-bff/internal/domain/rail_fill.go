package domain

import (
	"sort"
	"strings"
)

// RailFillTarget is how many cards a rail should hold before it counts as full on a wide screen. A rail with fewer
// cards leaves the right of the row empty, so it is filled (PickRailFill) instead of left hollow.
const RailFillTarget = 7

// RailFill kinds.
const (
	// RailFillPlacement is a tile from a rail_fill placement: Sponsored when it names an advertiser, else staff editorial.
	RailFillPlacement = "placement"
	// RailFillSponsoredProducts is a tile of promoted products from the sponsored-products auction.
	RailFillSponsoredProducts = "sponsored-products"
)

// RailFill is the tile that fills the empty columns of a short rail. A sponsored tile always names its advertiser; the
// storefront labels it "Sponsored" and counts its impressions and clicks (placement id, or the search id for products).
type RailFill struct {
	Kind       string `json:"kind"`
	ID         string `json:"id,omitempty"`
	Sponsored  bool   `json:"sponsored"`
	Advertiser string `json:"advertiser,omitempty"`
	Title      string `json:"title"`
	Body       string `json:"body,omitempty"`
	CTA        string `json:"cta,omitempty"`
	Href       string `json:"href"`
	// ImageURL is nil unless it is a real photo: the tile is then text only, never a placeholder.
	ImageURL *string `json:"imageUrl,omitempty"`
	// Products are at most three eligible products (in stock, real photo) the tile shows.
	Products      []ProductSummary `json:"products,omitempty"`
	SearchID      string           `json:"searchId,omitempty"`
	MaxPerSession int              `json:"maxPerSession,omitempty"`
}

// RailFillCandidate is a staff rail_fill placement, already filtered by the content service for market, ZIP prefix and schedule.
type RailFillCandidate struct {
	Tile     RailFill
	Priority int
	// Departments limits the tile to rails of these departments (case-insensitive); empty = any rail.
	Departments []string
}

// RailFillInput is everything PickRailFill decides from. All products must already be eligible (in stock, real photo).
type RailFillInput struct {
	Items      []ProductSummary
	Target     int
	Department string
	// Relaxed are more products for the rail in preference order (same department first, nearby sellers, popular).
	Relaxed []ProductSummary
	// Used are the products the rest of the page shows; a relaxed product never repeats one.
	Used map[string]bool
	// Auction is the sponsored-products tile for the rail's context, nil when there is none. Resolved lazily by the caller.
	Auction func() *RailFill
	// Placements are the rail_fill placements that apply to this market and ZIP.
	Placements []RailFillCandidate
	// Rotate picks among tiles of equal priority (a visit seed); 0 keeps the first.
	Rotate uint64
}

// RailFillResult is the rail's items (possibly extended) and its tile, with the tier that filled it for metrics and tests.
type RailFillResult struct {
	Items []ProductSummary
	Fill  *RailFill
	// Tier is "none" (rail already full), "products", "sponsored", "placement" or "collapse".
	Tier string
}

// PickRailFill fills a short rail. Priority: (1) more relevant eligible products, (2) a Sponsored tile (a paid
// placement, then the sponsored-products auction), (3) a staff editorial placement, (4) nothing: the rail collapses
// to its own width. A tile is never unlabelled when paid, never repeats a product already in the rail, and a rail that
// is full gets neither.
func PickRailFill(in RailFillInput) RailFillResult {
	target := in.Target
	if target <= 0 {
		target = RailFillTarget
	}
	items := append([]ProductSummary(nil), in.Items...)
	if len(items) >= target {
		return RailFillResult{Items: items, Tier: "none"}
	}
	have := map[string]bool{}
	for _, p := range items {
		have[p.ATIN] = true
	}
	for _, p := range in.Relaxed {
		if len(items) >= target {
			break
		}
		if have[p.ATIN] || in.Used[p.ATIN] || p.ATIN == "" {
			continue
		}
		have[p.ATIN] = true
		items = append(items, p)
	}
	if len(items) >= target {
		return RailFillResult{Items: items, Tier: "products"}
	}
	// (2) paid placements, then the auction.
	if t := pickPlacement(in, have, true); t != nil {
		return RailFillResult{Items: items, Fill: t, Tier: "sponsored"}
	}
	if in.Auction != nil {
		if t := in.Auction(); t != nil && t.Sponsored && strings.TrimSpace(t.Advertiser) != "" && len(t.Products) > 0 {
			t.Products = dropTaken(t.Products, have, 3)
			if len(t.Products) > 0 {
				return RailFillResult{Items: items, Fill: t, Tier: "sponsored"}
			}
		}
	}
	// (3) staff editorial.
	if t := pickPlacement(in, have, false); t != nil {
		return RailFillResult{Items: items, Fill: t, Tier: "placement"}
	}
	return RailFillResult{Items: items, Tier: "collapse"}
}

func dropTaken(in []ProductSummary, have map[string]bool, max int) []ProductSummary {
	out := make([]ProductSummary, 0, max)
	for _, p := range in {
		if !have[p.ATIN] && len(out) < max {
			out = append(out, p)
		}
	}
	return out
}

// pickPlacement chooses the highest-priority placement of the wanted kind (paid or editorial) that targets the rail's department.
func pickPlacement(in RailFillInput, have map[string]bool, paid bool) *RailFill {
	var c []RailFillCandidate
	for _, cand := range in.Placements {
		t := cand.Tile
		isPaid := strings.TrimSpace(t.Advertiser) != ""
		if isPaid != paid || strings.TrimSpace(t.Title) == "" || strings.TrimSpace(t.Href) == "" {
			continue
		}
		if len(cand.Departments) > 0 && !containsFold(cand.Departments, in.Department) {
			continue
		}
		c = append(c, cand)
	}
	if len(c) == 0 {
		return nil
	}
	sort.SliceStable(c, func(i, j int) bool {
		if c[i].Priority != c[j].Priority {
			return c[i].Priority > c[j].Priority
		}
		return c[i].Tile.ID < c[j].Tile.ID
	})
	top := 1
	for top < len(c) && c[top].Priority == c[0].Priority {
		top++
	}
	t := c[int(in.Rotate%uint64(top))].Tile
	t.Kind, t.Sponsored = RailFillPlacement, paid
	t.Products = dropTaken(t.Products, have, 3)
	return &t
}

func containsFold(list []string, v string) bool {
	for _, x := range list {
		if strings.EqualFold(strings.TrimSpace(x), strings.TrimSpace(v)) {
			return true
		}
	}
	return false
}

// RecentlyViewed is the answer of GET /v1/home/recently-viewed: the cards and, when the rail is short, its fill tile.
type RecentlyViewed struct {
	Items []ProductSummary `json:"items"`
	Fill  *RailFill        `json:"fill,omitempty"`
}
