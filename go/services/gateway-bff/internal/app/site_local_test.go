package app

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var (
	houston = domain.DeliveryLocation{ZIP: "77042", City: "Houston", State: "TX", Label: "Houston 77042", Serviceable: true}
	edison  = domain.DeliveryLocation{ZIP: "08817", City: "Edison", State: "NJ", Label: "Edison 08817", Serviceable: true}
	denver  = domain.DeliveryLocation{ZIP: "80202", City: "Denver", State: "CO", Label: "Denver 80202", Serviceable: true}
)

// zipLocation resolves the caller's session ZIP the way the real service does: from the session.
type zipLocation struct{ LocationService }

func (zipLocation) Current(_ context.Context, c *domain.Caller) domain.DeliveryLocation {
	switch c.ZIP() {
	case "08817":
		return edison
	case "80202":
		return denver
	}
	return houston
}

// zipContent records the ZIP each placements request carried.
type zipContent struct {
	port.ContentClient
	pl   domain.Placements
	zips *[]string
}

func (c zipContent) Placements(_ context.Context, _, zip string) (domain.Placements, error) {
	*c.zips = append(*c.zips, zip)
	return c.pl, nil
}

// shipsFromOffers answers the buy box of each product with its ship-from label.
type shipsFromOffers struct {
	port.StorefrontOffersClient
	from map[string]string
}

func (o shipsFromOffers) ProductSummaries(_ context.Context, atins []string) (map[string]port.OfferBuyBox, error) {
	out := map[string]port.OfferBuyBox{}
	for _, a := range atins {
		label := o.from[a]
		city, _, _ := strings.Cut(label, ",")
		out[a] = port.OfferBuyBox{ATIN: a, OfferCount: 1, BuyBox: &port.PublicOffer{OfferID: a + "-o", ATIN: a, Seller: port.OfferSeller{ID: "org_" + a, Name: "Seller " + a,
			Kind: "SELLER", City: city}, PriceMinor: 700, Currency: "USD", Fulfilment: "SELLER", AvailableQty: 5, StockStatus: "IN_STOCK", ShipsFrom: label}}
	}
	return out, nil
}

type fakePromise struct{ days map[string]int }

func (f fakePromise) Promises(_ context.Context, zip string, items []port.PromiseItem) (map[string]int, error) {
	out := map[string]int{}
	for _, it := range items {
		if d, ok := f.days[zip+"|"+it.ATIN]; ok {
			out[it.ATIN] = d
		}
	}
	return out, nil
}

func localHits(n int) []port.SearchHit {
	var hs []port.SearchHit
	for i := range n {
		a := fmt.Sprintf("ATLOC%05d", i)
		hs = append(hs, hit(a, "Product "+a, "Groceries", photo(a), 5-float64(i)/100))
	}
	return hs
}

func atinsOf(items []domain.ProductSummary) string {
	var s []string
	for _, p := range items {
		s = append(s, p.ATIN)
	}
	return strings.Join(s, ",")
}

func TestShipsFromPlaceAndTier(t *testing.T) {
	card := func(shipsFrom, city string) domain.ProductSummary {
		return domain.ProductSummary{ShipsFrom: shipsFrom, SellerCity: city}
	}
	for _, tc := range []struct {
		name string
		p    domain.ProductSummary
		loc  domain.DeliveryLocation
		want int
	}{
		{"same city", card("Houston, TX", ""), houston, tierCity},
		{"city match ignores case", card("houston, tx", ""), houston, tierCity},
		{"same state other city", card("Dallas, TX", ""), houston, tierState},
		{"same region other state", card("Atlanta, GA", ""), houston, tierRegion},
		{"other region", card("Edison, NJ", ""), houston, tierFar},
		{"northeast neighbours", card("Bronx, NY", ""), edison, tierRegion},
		{"fulfilled by AfroTransact has no place", card("AfroTransact", ""), houston, tierFar},
		{"seller city only", card("", "Houston"), houston, tierCity},
		{"nothing known", card("", ""), houston, tierFar},
		{"shopper place unknown", card("Houston, TX", ""), domain.DeliveryLocation{ZIP: "77042"}, tierFar},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := locationTier(tc.p, tc.loc); got != tc.want {
				t.Errorf("tier = %d, want %d", got, tc.want)
			}
		})
	}
}

func TestNearShopperKeepsOnlyCloseProducts(t *testing.T) {
	in := []domain.ProductSummary{{ATIN: "A", ShipsFrom: "Houston, TX"}, {ATIN: "B", ShipsFrom: "Edison, NJ"}, {ATIN: "C", ShipsFrom: "Dallas, TX"}, {ATIN: "D", ShipsFrom: "AfroTransact"}}
	if got := atinsOf(nearShopper(in, houston)); got != "A,C" {
		t.Errorf("near Houston = %s", got)
	}
	if got := atinsOf(nearShopper(in, edison)); got != "B" {
		t.Errorf("near Edison = %s", got)
	}
	if got := nearShopper(in, domain.DeliveryLocation{ZIP: "99999"}); len(got) != 0 {
		t.Errorf("an unknown place has nothing near it: %v", got)
	}
}

func TestRankForShopperIsDeterministicAndVariesByDayZIPAndShopper(t *testing.T) {
	var in []domain.ProductSummary
	for i := range 12 {
		in = append(in, domain.ProductSummary{ATIN: fmt.Sprintf("AT%02d", i)})
	}
	day1, day2 := time.Date(2026, 11, 4, 12, 0, 0, 0, time.UTC), time.Date(2026, 11, 5, 12, 0, 0, 0, time.UTC)
	guest := func(id string) *domain.Caller { return &domain.Caller{Session: &domain.Session{ID: id}} }
	order := func(loc domain.DeliveryLocation, c *domain.Caller, now time.Time) string {
		return atinsOf(rankForShopper(in, newScope(loc, c, now)))
	}
	base := order(houston, guest("s1"), day1)
	if again := order(houston, guest("s1"), day1.Add(9*time.Hour)); again != base {
		t.Errorf("same day, ZIP and shopper must give the same order:\n%s\n%s", base, again)
	}
	for name, other := range map[string]string{
		"another ZIP":     order(edison, guest("s1"), day1),
		"another day":     order(houston, guest("s1"), day2),
		"another shopper": order(houston, &domain.Caller{Subject: "u9", BearerToken: "t"}, day1),
	} {
		if other == base {
			t.Errorf("%s should rotate the order", name)
		}
	}
	// Rotation never moves a product more than one bucket away from its search rank.
	got := rankForShopper(in, newScope(houston, guest("s1"), day1))
	for i, p := range got {
		var rank int
		fmt.Sscanf(p.ATIN, "AT%02d", &rank)
		if rank/rotationBucket != i/rotationBucket {
			t.Errorf("%s landed at %d, outside its bucket", p.ATIN, i)
		}
	}
	if got := atinsOf(rankForShopper(in, shopperScope{})); got != atinsOf(in) {
		t.Errorf("a zero scope keeps the search order, got %s", got)
	}
}

func TestRankForShopperPutsCloserProductsFirst(t *testing.T) {
	in := []domain.ProductSummary{{ATIN: "FAR", ShipsFrom: "Edison, NJ"}, {ATIN: "STATE", ShipsFrom: "Dallas, TX"}, {ATIN: "REGION", ShipsFrom: "Atlanta, GA"}, {ATIN: "CITY", ShipsFrom: "Houston, TX"}}
	if got := atinsOf(rankForShopper(in, newScope(houston, &domain.Caller{}, homeNow))); got != "CITY,STATE,REGION,FAR" {
		t.Errorf("order = %s", got)
	}
}

type localFixture struct {
	zips   []string
	search *rulesSearch
}

func (f *localFixture) service(pl domain.Placements, from map[string]string, days map[string]int) SiteService {
	f.zips = nil
	return NewSiteService(SiteDeps{Content: zipContent{pl: pl, zips: &f.zips}, Search: f.search, Catalog: homeCatalog{category: map[string]string{}},
		Inventory: homeInventory{}, Offers: shipsFromOffers{from: from}, Promise: fakePromise{days: days}, Location: zipLocation{}, Estimator: est,
		Market: "us", Clock: func() time.Time { return homeNow }})
}

func guestAt(zip string) *domain.Caller {
	return &domain.Caller{Session: &domain.Session{ID: "visit", ZIP: zip}}
}

func railByID(h domain.Home, id string) *domain.Rail {
	for i := range h.Rails {
		if h.Rails[i].ID == id {
			return &h.Rails[i]
		}
	}
	return nil
}

func TestHomeChangesWithTheShoppersZIP(t *testing.T) {
	hits := localHits(6)
	f := &localFixture{search: &rulesSearch{hits: map[string][]port.SearchHit{"|rating|": hits}}}
	from := map[string]string{"ATLOC00000": "Houston, TX", "ATLOC00001": "Dallas, TX", "ATLOC00002": "Edison, NJ", "ATLOC00003": "Bronx, NY",
		"ATLOC00004": "Edison, NJ", "ATLOC00005": "AfroTransact"}
	pl := domain.Placements{Rails: []domain.RailPlacement{{ID: "near", Title: "Near {city}, {state}", Href: "/s", Source: domain.RailSource{Kind: "near-you", Limit: 12}}}}
	svc := f.service(pl, from, nil)

	tx, err := svc.Home(context.Background(), guestAt("77042"))
	if err != nil {
		t.Fatal(err)
	}
	nj, err := svc.Home(context.Background(), guestAt("08817"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(f.zips, ",") != "77042,08817" {
		t.Errorf("content must be asked for each shopper's ZIP, got %v", f.zips)
	}
	if tx.DeliverTo.City != "Houston" || nj.DeliverTo.City != "Edison" {
		t.Errorf("deliverTo = %+v / %+v", tx.DeliverTo, nj.DeliverTo)
	}
	r1, r2 := railByID(tx, "near"), railByID(nj, "near")
	if r1 == nil || r2 == nil {
		t.Fatalf("near-you rails missing: %+v / %+v", tx.Rails, nj.Rails)
	}
	if r1.Title != "Near Houston, TX" || r2.Title != "Near Edison, NJ" {
		t.Errorf("titles = %q / %q", r1.Title, r2.Title)
	}
	if got := atinsOf(r1.Items); got != "ATLOC00000,ATLOC00001" {
		t.Errorf("Houston sees %s (Houston first, then Texas; nothing from NJ, NY or AfroTransact)", got)
	}
	if got := atinsOf(r2.Items); got != "ATLOC00002,ATLOC00004,ATLOC00003" {
		t.Errorf("Edison sees %s (NJ before NY)", got)
	}
}

func TestNearYouRailIsHiddenWhenNothingShipsNearby(t *testing.T) {
	f := &localFixture{search: &rulesSearch{hits: map[string][]port.SearchHit{"|rating|": localHits(3)}}}
	pl := domain.Placements{Rails: []domain.RailPlacement{{ID: "near", Title: "Near {city}", Href: "/s", Source: domain.RailSource{Kind: "near-you"}},
		{ID: "popular", Title: "Popular", Href: "/s", Source: domain.RailSource{Kind: "search", Sort: "rating"}}}}
	home, err := f.service(pl, map[string]string{"ATLOC00000": "Edison, NJ"}, nil).Home(context.Background(), guestAt("77042"))
	if err != nil {
		t.Fatal(err)
	}
	if railByID(home, "near") != nil {
		t.Errorf("an empty near-you rail must be hidden: %+v", home.Rails)
	}
	if railByID(home, "popular") == nil {
		t.Errorf("other rails stay: %+v", home.Rails)
	}
}

func TestDeliverableFastKeepsPromisesWithinDaysAndNamesTheDay(t *testing.T) {
	f := &localFixture{search: &rulesSearch{hits: map[string][]port.SearchHit{"|relevance|": localHits(4)}}}
	days := map[string]int{"77042|ATLOC00000": 1, "77042|ATLOC00001": 2, "77042|ATLOC00002": 5, "08817|ATLOC00002": 1}
	pl := domain.Placements{Rails: []domain.RailPlacement{{ID: "fast", Title: "Get it by {day}", Href: "/s", Source: domain.RailSource{Kind: "deliverable-fast"}}}}
	svc := f.service(pl, nil, days)

	tx, _ := svc.Home(context.Background(), guestAt("77042"))
	r := railByID(tx, "fast")
	if r == nil {
		t.Fatalf("rails = %+v", tx.Rails)
	}
	items := strings.Split(atinsOf(r.Items), ",")
	if len(items) != 2 || (items[0] != "ATLOC00000" && items[1] != "ATLOC00000") || strings.Contains(atinsOf(r.Items), "ATLOC00002") {
		t.Errorf("only products promised within 2 days to the ZIP qualify, got %v", items)
	}
	if want := "Get it by " + est.DayLabel(2, homeNow); r.Title != want {
		t.Errorf("title = %q, want %q (the day everything arrives by)", r.Title, want)
	}
	nj, _ := svc.Home(context.Background(), guestAt("08817"))
	if r := railByID(nj, "fast"); r == nil || atinsOf(r.Items) != "ATLOC00002" || r.Title != "Get it by "+est.DayLabel(1, homeNow) {
		t.Errorf("a different ZIP gets different promises: %+v", r)
	}
	if none, _ := svc.Home(context.Background(), guestAt("80202")); railByID(none, "fast") != nil {
		t.Errorf("no promise to the ZIP hides the rail: %+v", none.Rails)
	}
}

func TestHomeShowsAProductOnce(t *testing.T) {
	f := &localFixture{search: &rulesSearch{hits: map[string][]port.SearchHit{"|rating|": localHits(3), "Groceries|rating|": localHits(3)}}}
	pl := domain.Placements{
		Tiles: []domain.Tile{{ID: "t", Title: "T", CTA: "x", Href: "/s", Cells: []domain.TileCell{
			{Source: &domain.RailSource{Kind: "search", Department: "Groceries", Sort: "rating"}},
			{Source: &domain.RailSource{Kind: "search", Department: "Groceries", Sort: "rating"}}}}},
		Rails: []domain.RailPlacement{
			{ID: "a", Title: "A", Href: "/s", Source: domain.RailSource{Kind: "search", Sort: "rating"}},
			{ID: "b", Title: "B", Href: "/s", Source: domain.RailSource{Kind: "search", Sort: "rating"}}}}
	home, err := f.service(pl, nil, nil).Home(context.Background(), guestAt("77042"))
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]string{}
	note := func(where, atin string) {
		if prev, dup := seen[atin]; dup {
			t.Errorf("%s shown in %s and %s", atin, prev, where)
		}
		seen[atin] = where
	}
	for _, tile := range home.Tiles {
		for _, c := range tile.Cells {
			note("tile", c.ATIN)
		}
	}
	for _, r := range home.Rails {
		for _, p := range r.Items {
			note("rail "+r.ID, p.ATIN)
		}
	}
	if len(home.Tiles) != 1 || len(seen) != 3 || railByID(home, "b") != nil {
		t.Errorf("3 products: 2 in the tile, 1 left for rail a, none for b (hidden); tiles=%d seen=%v rails=%+v", len(home.Tiles), seen, home.Rails)
	}
}

func TestTileRulePicksDifferentProductsPerLocation(t *testing.T) {
	f := &localFixture{search: &rulesSearch{hits: map[string][]port.SearchHit{"Groceries|rating|": localHits(2)}}}
	from := map[string]string{"ATLOC00000": "Houston, TX", "ATLOC00001": "Edison, NJ"}
	pl := domain.Placements{Tiles: []domain.Tile{{ID: "t", Title: "T", CTA: "x", Href: "/s", Cells: []domain.TileCell{
		{Source: &domain.RailSource{Kind: "search", Department: "Groceries", Sort: "rating"}},
		{Source: &domain.RailSource{Kind: "search", Department: "Groceries", Sort: "rating"}}}}}}
	svc := f.service(pl, from, nil)
	first := func(zip string) string {
		h, err := svc.Home(context.Background(), guestAt(zip))
		if err != nil || len(h.Tiles) != 1 {
			t.Fatalf("%s: %v %+v", zip, err, h.Tiles)
		}
		return h.Tiles[0].Cells[0].ATIN
	}
	if a, b := first("77042"), first("08817"); a != "ATLOC00000" || b != "ATLOC00001" {
		t.Errorf("the first cell should be the local product: Houston %s, Edison %s", a, b)
	}
}

func TestValidRuleSourceKnowsTheLocationKinds(t *testing.T) {
	for src, want := range map[string]bool{"near-you": true, "deliverable-fast": true, "community": false, "nope": false} {
		if got := validRuleSource(&domain.RailSource{Kind: src}); got != want {
			t.Errorf("%s valid = %v, want %v", src, got, want)
		}
	}
	if !validRuleSource(&domain.RailSource{Kind: "community", Community: "ghanaian"}) {
		t.Error("a community source with a community is valid")
	}
}
