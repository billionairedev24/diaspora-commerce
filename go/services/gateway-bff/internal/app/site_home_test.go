package app

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var homeNow = time.Date(2026, 11, 4, 12, 0, 0, 0, time.UTC)

// rulesSearch answers a search from a fixed table keyed "department|sort|query".
type rulesSearch struct {
	port.SearchClient
	hits  map[string][]port.SearchHit
	asked []port.SearchParams
}

func (s *rulesSearch) Search(_ context.Context, p port.SearchParams) (port.SearchResult, error) {
	s.asked = append(s.asked, p)
	return port.SearchResult{Hits: s.hits[p.Department+"|"+p.Sort+"|"+p.Q]}, nil
}

type homeContent struct {
	port.ContentClient
	pl domain.Placements
}

func (c homeContent) Placements(context.Context, string, string) (domain.Placements, error) {
	return c.pl, nil
}

type homeLocation struct{ LocationService }

func (homeLocation) Current(context.Context, *domain.Caller) domain.DeliveryLocation {
	return domain.DeliveryLocation{ZIP: "77042", City: "Houston", State: "TX", Label: "Houston 77042", Serviceable: true}
}

type homeCatalog struct {
	port.CatalogClient
	category map[string]string // product ATIN -> category id
}

func (c homeCatalog) Product(_ context.Context, atin string) (port.CatalogProduct, error) {
	id, ok := c.category[atin]
	if !ok {
		return port.CatalogProduct{}, port.ErrNotFound
	}
	return port.CatalogProduct{ATIN: atin, Title: "Product " + atin, CategoryID: id,
		Images: []struct {
			URL string `json:"url"`
		}{{URL: "https://img.example/" + atin + ".jpg"}},
		Variants: []port.CatalogVariant{{ATIN: atin + "V", PriceMinor: 500, Currency: "USD"}}}, nil
}

func (homeCatalog) Variant(context.Context, string) (port.CatalogVariantView, error) {
	return port.CatalogVariantView{}, port.ErrNotFound
}

func (homeCatalog) Categories(context.Context) ([]port.Category, error) {
	return []port.Category{{ID: "c-groc", Path: "Groceries › Grains"}, {ID: "c-fash", Path: "Fashion › Dresses"}}, nil
}

type homeInventory struct{ out map[string]bool }

func (i homeInventory) Available(_ context.Context, skus []string) (map[string]int, error) {
	m := map[string]int{}
	for _, s := range skus {
		if !i.out[s] {
			m[s] = 9
		}
	}
	return m, nil
}

type homeOrders struct {
	port.OrdersClient
	orders []domain.OrderSummary
}

func (o homeOrders) List(context.Context, string, string, int) (domain.Page[domain.OrderSummary], error) {
	return domain.Page[domain.OrderSummary]{Items: o.orders}, nil
}

func hit(atin, title, dept, img string, rating float64) port.SearchHit {
	return port.SearchHit{ATIN: atin, Title: title, CategoryPath: dept + " › x", ImageURL: img, Rating: rating, InStock: true,
		AvailableQty: 5, PriceMinor: 700, Currency: "USD", SellerKind: domain.SellerKindSeller, Fulfilment: "SELLER"}
}

func photo(atin string) string { return "https://img.example/" + atin + ".jpg" }

func newHomeService(pl domain.Placements, search *rulesSearch, orders port.OrdersClient) SiteService {
	return NewSiteService(SiteDeps{Content: homeContent{pl: pl}, Search: search, Catalog: homeCatalog{category: map[string]string{
		"ATRICE0001": "c-groc", "ATGARI0001": "c-groc", "ATDRESS001": "c-fash", "ATNEW00001": "c-fash", "ATOUT00001": "c-fash"}},
		Inventory: homeInventory{out: map[string]bool{"ATOUT00001V": true}}, Orders: orders, Location: homeLocation{}, Estimator: est,
		Market: "us", Clock: func() time.Time { return homeNow }})
}

func strp(s string) *string { return &s }

func TestRealPhoto(t *testing.T) {
	for u, want := range map[string]bool{
		"https://cdn.example/a/b.jpg":     true,
		"/media/a.webp":                   true,
		"https://cdn.example/a.jpg?w=300": true,
		"":                                false,
		"data:image/png;base64,AAAA":      false,
		"https://cdn.example/placeholder-400.png":   false,
		"https://cdn.example/img/no-image.png":      false,
		"https://cdn.example/icons/box.png":         false,
		"https://cdn.example/logo.svg":              false,
		"ph ph-package":                             false,
		"https://cdn.example/Placeholder/Thing.JPG": false,
	} {
		var in *string
		if u != "" {
			in = &u
		}
		if got := realPhoto(in); got != want {
			t.Errorf("realPhoto(%q) = %v, want %v", u, got, want)
		}
	}
	if realPhoto(nil) {
		t.Error("nil image must not count as a photo")
	}
}

func TestActiveHeroKeepsOrderAndDropsOutsideWindow(t *testing.T) {
	past, future := "2026-11-01T00:00:00Z", "2026-11-10T00:00:00Z"
	slides := []domain.HeroSlide{
		{ID: "always"},
		{ID: "ended", EndsAt: strp(past)},
		{ID: "soon", StartsAt: strp(future)},
		{ID: "live", StartsAt: strp(past), EndsAt: strp(future)},
		{ID: "typo", StartsAt: strp("next tuesday")},
		{ID: "endsNow", EndsAt: strp(homeNow.Format(time.RFC3339))},
	}
	var got []string
	for _, h := range activeHero(slides, homeNow) {
		got = append(got, h.ID)
	}
	if strings.Join(got, ",") != "always,live,typo" {
		t.Errorf("active slides = %v", got)
	}
}

func TestHomeResolvesRuleTiles(t *testing.T) {
	search := &rulesSearch{hits: map[string][]port.SearchHit{
		"Fashion|rating|": {hit("ATNOIMG001", "No photo", "Fashion", "", 5), hit("ATSVG00001", "Icon", "Fashion", "https://x/icon.svg", 5),
			hit("ATDRESS001", "Wax dress", "Fashion", photo("ATDRESS001"), 4.9)},
		"Fashion|newest|":   {hit("ATDRESS001", "Wax dress", "Fashion", photo("ATDRESS001"), 4.9), hit("ATNEW00001", "New gele", "Fashion", photo("ATNEW00001"), 4)},
		"Groceries|rating|": {hit("ATRICE0001", "Rice", "Groceries", photo("ATRICE0001"), 4.8)},
		"Beauty|rating|":    {hit("ATBAD00001", "Out of stock soap", "Beauty", photo("ATBAD00001"), 5)},
	}}
	search.hits["Beauty|rating|"][0].InStock = false
	pl := domain.Placements{Tiles: []domain.Tile{
		{ID: "mix", Title: "Top picks", CTA: "See all", Href: "/s", Cells: []domain.TileCell{
			{Source: &domain.RailSource{Kind: "search", Department: "Fashion", Sort: "rating"}},
			{Source: &domain.RailSource{Kind: "search", Department: "Fashion", Sort: "newest"}},
			{Source: &domain.RailSource{Kind: "search", Department: "Groceries", Sort: "rating"}},
			{Label: "Nigerian", Href: "/communities/nigerian", ImageURL: strp("https://cdn.example/ng.jpg")}}},
		{ID: "dead", Title: "Nothing eligible", CTA: "x", Href: "/s", Cells: []domain.TileCell{
			{Source: &domain.RailSource{Kind: "search", Department: "Beauty", Sort: "rating"}},
			{Source: &domain.RailSource{Kind: "bogus"}}}},
		{ID: "fixed", Title: "Fixed", CTA: "x", Href: "/s", Cells: []domain.TileCell{{Label: "A", Href: "/a"}}},
	}}
	home, err := newHomeService(pl, search, nil).Home(context.Background(), &domain.Caller{})
	if err != nil {
		t.Fatal(err)
	}
	if len(home.Tiles) != 2 || home.Tiles[0].ID != "mix" || home.Tiles[1].ID != "fixed" {
		t.Fatalf("tiles = %+v (a tile with no resolvable rule is dropped, a fixed tile stays)", home.Tiles)
	}
	cells := home.Tiles[0].Cells
	if len(cells) != 4 {
		t.Fatalf("cells = %+v", cells)
	}
	if c := cells[0]; c.ATIN != "ATDRESS001" || c.Href != "/p/ATDRESS001" || c.Label != "Wax dress" || c.ImageURL == nil || *c.ImageURL != photo("ATDRESS001") || c.Source != nil {
		t.Errorf("top-rated cell skipped photo-less and icon hits: %+v", c)
	}
	if c := cells[1]; c.ATIN != "ATNEW00001" {
		t.Errorf("the newest cell must not repeat the dress already shown: %+v", c)
	}
	if c := cells[2]; c.ATIN != "ATRICE0001" {
		t.Errorf("groceries cell = %+v", c)
	}
	if c := cells[3]; c.Label != "Nigerian" || c.Href != "/communities/nigerian" || c.ATIN != "" {
		t.Errorf("fixed cells keep working: %+v", c)
	}
	for _, q := range search.asked {
		if !q.InStock || !q.NoTrack {
			t.Errorf("rule search must ask for in-stock, untracked results: %+v", q)
		}
	}
}

func TestHomeHeroIsFilteredByTime(t *testing.T) {
	pl := domain.Placements{Hero: []domain.HeroSlide{
		{ID: "a", Title: "A", EndsAt: strp("2026-11-03T00:00:00Z")}, {ID: "b", Title: "B"}, {ID: "c", Title: "C", StartsAt: strp("2026-12-01T00:00:00Z")}}}
	home, _ := newHomeService(pl, &rulesSearch{}, nil).Home(context.Background(), &domain.Caller{})
	if len(home.Hero) != 1 || home.Hero[0].ID != "b" {
		t.Errorf("hero = %+v", home.Hero)
	}
}

func signedIn() *domain.Caller { return &domain.Caller{BearerToken: "tok", Subject: "u1"} }

func order(lines ...string) domain.OrderSummary {
	var ls []domain.OrderShipmentLine
	for _, a := range lines {
		ls = append(ls, domain.OrderShipmentLine{Title: a, ProductATIN: a})
	}
	return domain.OrderSummary{OrderNumber: "O1", Shipments: []domain.OrderShipment{{Lines: ls}}}
}

func TestBasedOnYourOrdersSuggestsUnboughtProducts(t *testing.T) {
	search := &rulesSearch{hits: map[string][]port.SearchHit{
		"Groceries|rating|": {hit("ATRICE0001", "Rice", "Groceries", photo("ATRICE0001"), 5), hit("ATGARI0001", "Garri", "Groceries", photo("ATGARI0001"), 4)},
		"Fashion|rating|": {hit("ATDRESS001", "Wax dress", "Fashion", photo("ATDRESS001"), 5), hit("ATNOIMG001", "No photo", "Fashion", "", 5),
			hit("ATNEW00001", "New gele", "Fashion", photo("ATNEW00001"), 4)},
	}}
	// Rice bought twice, a dress once: Groceries leads, bought products are not offered again, the photo-less hit is skipped.
	orders := homeOrders{orders: []domain.OrderSummary{order("ATRICE0001"), order("ATRICE0001", "ATDRESS001")}}
	home, err := newHomeService(domain.Placements{}, search, orders).Home(context.Background(), signedIn())
	if err != nil {
		t.Fatal(err)
	}
	if len(home.Rails) != 1 || home.Rails[0].ID != BasedOnOrdersRailID || home.Rails[0].Title != "Based on your orders" {
		t.Fatalf("rails = %+v", home.Rails)
	}
	var got []string
	for _, p := range home.Rails[0].Items {
		got = append(got, p.ATIN)
	}
	if strings.Join(got, ",") != "ATGARI0001,ATNEW00001" {
		t.Errorf("items = %v", got)
	}
	if home.Rails[0].Href != "/s?dept=Groceries" {
		t.Errorf("href = %q", home.Rails[0].Href)
	}
}

func TestBasedOnYourOrdersIsHiddenWhenThereIsNothingToShow(t *testing.T) {
	svc := func(o port.OrdersClient) *siteServiceImpl {
		return newHomeService(domain.Placements{}, &rulesSearch{}, o).(*siteServiceImpl)
	}
	ctx := context.Background()
	if svc(homeOrders{orders: []domain.OrderSummary{order("ATRICE0001")}}).basedOnOrders(ctx, &domain.Caller{}) != nil {
		t.Error("guests get no personalised rail")
	}
	if svc(homeOrders{}).basedOnOrders(ctx, signedIn()) != nil {
		t.Error("no orders, no rail")
	}
	if svc(homeOrders{orders: []domain.OrderSummary{order("ATRICE0001")}}).basedOnOrders(ctx, signedIn()) != nil {
		t.Error("nothing eligible to suggest, no rail")
	}
	if svc(nil).basedOnOrders(ctx, signedIn()) != nil {
		t.Error("orders unavailable, no rail")
	}
}

func TestRecentlyViewedKeepsOrderAndDropsIneligible(t *testing.T) {
	svc := newHomeService(domain.Placements{}, &rulesSearch{}, nil)
	got, err := svc.RecentlyViewed(context.Background(), []string{"ATGARI0001", "atrice0001", "ATGARI0001", "ATOUT00001", "ATMISSING1", "not an atin", ""})
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, p := range got {
		ids = append(ids, p.ATIN)
	}
	if strings.Join(ids, ",") != "ATGARI0001,ATRICE0001" {
		t.Errorf("recently viewed = %v (out of stock, unknown and malformed ids are dropped, duplicates collapse)", ids)
	}
	if empty, _ := svc.RecentlyViewed(context.Background(), nil); empty == nil || len(empty) != 0 {
		t.Errorf("empty input must give an empty, non-nil list: %v", empty)
	}
}
