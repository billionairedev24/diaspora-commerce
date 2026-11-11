package app

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// fnSearch answers every search with fn.
type fnSearch struct {
	port.SearchClient
	fn func(port.SearchParams) (port.SearchResult, error)
}

func (s fnSearch) Search(_ context.Context, p port.SearchParams) (port.SearchResult, error) {
	return s.fn(p)
}

type recordingContent struct {
	homeContent
	events []string
	err    error
}

func (c *recordingContent) RecordPlacementEvent(_ context.Context, id, kind string) error {
	c.events = append(c.events, id+":"+kind)
	return c.err
}

type emptyServices struct {
	port.CustomerBookingsClient
	n int
}

func (e emptyServices) Search(context.Context, string, string, string) ([]port.UpBkListing, error) {
	return make([]port.UpBkListing, e.n), nil
}

func manyHits(prefix string, n int) []port.SearchHit {
	out := make([]port.SearchHit, 0, n)
	for i := range n {
		atin := fmt.Sprintf("%s%05d", prefix, i)
		out = append(out, hit(atin, "Item "+atin, "Groceries", photo(atin), 4.5))
	}
	return out
}

func composeService(pl domain.Placements, search port.SearchClient, services port.CustomerBookingsClient) SiteService {
	return NewSiteService(SiteDeps{Content: homeContent{pl: pl}, Search: search, Catalog: homeCatalog{category: map[string]string{}}, Inventory: homeInventory{},
		Services: services, Location: homeLocation{}, Estimator: est, Market: "us", Clock: func() time.Time { return homeNow }})
}

func organic(n int) port.SearchClient {
	return fnSearch{fn: func(port.SearchParams) (port.SearchResult, error) {
		return port.SearchResult{Hits: manyHits("ATORG", n)}, nil
	}}
}

func payload(s string) []byte { return []byte(s) }

func blockTypes(h domain.Home) []string {
	out := make([]string, 0, len(h.Blocks))
	for _, b := range h.Blocks {
		out = append(out, b.Type+":"+b.ID)
	}
	return out
}

func find(h domain.Home, id string) *domain.HomeBlock {
	for i := range h.Blocks {
		if h.Blocks[i].ID == id {
			return &h.Blocks[i]
		}
	}
	return nil
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestComposeOrdersByPriorityAndKeepsContentOrderForTies(t *testing.T) {
	pl := domain.Placements{
		Rails:   []domain.RailPlacement{{ID: "plc_rail", Title: "Rail", Href: "/s", Source: domain.RailSource{Kind: "search"}}},
		Banners: []domain.Banner{{ID: "plc_banner", Advertiser: "Acme", Title: "T"}},
		Body: []domain.BodyPlacement{
			{ID: "plc_rail", Slot: "home_product_row", Priority: 10},
			{ID: "plc_adrow", Slot: "home_ad_row", Priority: 50, Advertiser: "Acme", Payload: payload(`{"ads":[{"imageUrl":"https://i/1.jpg","alt":"a","href":"/x"},{"imageUrl":"https://i/2.jpg","alt":"b","href":"/y"}]}`)},
			{ID: "plc_banner", Slot: "display_banner", Priority: 10},
			{ID: "plc_adv", Slot: "home_advertorial", Priority: 30, Advertiser: "Acme", Payload: payload(`{"headline":"H","text":"t","href":"/a"}`)},
		}}
	h, err := composeService(pl, organic(8), nil).Home(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"ad-row:plc_adrow", "advertorial:plc_adv", "rail:plc_rail", "banner:plc_banner", "recently-viewed:recently-viewed"}
	if got := blockTypes(h); !equal(got, want) {
		t.Fatalf("order = %v, want %v", got, want)
	}
}

func TestComposeTargetsByStateAndAudience(t *testing.T) {
	banner := func(id, states string) domain.BodyPlacement {
		return domain.BodyPlacement{ID: id, Slot: "home_brand_banner", Priority: 10, Advertiser: "Acme",
			Payload: payload(`{"headline":"H","href":"/x","states":` + states + `}`)}
	}
	pl := domain.Placements{Body: []domain.BodyPlacement{banner("plc_tx", `["tx"]`), banner("plc_nj", `["NJ","NY"]`), banner("plc_all", `[]`),
		{ID: "plc_signin", Slot: "home_block", Priority: 5, Payload: payload(`{"type":"signin-promo","title":"Sign in","href":"/signin"}`)}}}
	svc := composeService(pl, organic(0), nil)
	guest, _ := svc.Home(context.Background(), nil)
	if find(guest, "plc_tx") == nil || find(guest, "plc_all") == nil || find(guest, "plc_nj") != nil {
		t.Errorf("Houston (TX) must see the TX and untargeted banners only: %v", blockTypes(guest))
	}
	if find(guest, "plc_signin") == nil {
		t.Errorf("a signed-out visitor sees the sign-in band: %v", blockTypes(guest))
	}
	in, _ := svc.Home(context.Background(), signedIn())
	if find(in, "plc_signin") != nil {
		t.Errorf("a signed-in shopper never sees the sign-in band")
	}
}

func TestPaidModulesAreAlwaysLabelledAndNeverWithoutAnAdvertiser(t *testing.T) {
	pl := domain.Placements{Body: []domain.BodyPlacement{
		{ID: "plc_anon", Slot: "home_advertorial", Priority: 9, Payload: payload(`{"headline":"H","text":"t","href":"/a"}`)},
		{ID: "plc_one", Slot: "home_ad_row", Priority: 8, Advertiser: "Acme", Payload: payload(`{"ads":[{"imageUrl":"https://i/1.jpg","alt":"a","href":"/x"}]}`)},
		{ID: "plc_ok", Slot: "home_advertorial", Priority: 7, Advertiser: "Acme", Payload: payload(`{"headline":"H","text":"t","href":"/a"}`)},
		{ID: "plc_bad", Slot: "home_advertorial", Priority: 6, Advertiser: "Acme", Payload: payload(`{not json`)},
	}}
	h, _ := composeService(pl, organic(0), nil).Home(context.Background(), nil)
	for _, id := range []string{"plc_anon", "plc_one", "plc_bad"} {
		if find(h, id) != nil {
			t.Errorf("%s must be dropped: %v", id, blockTypes(h))
		}
	}
	b := find(h, "plc_ok")
	if b == nil || !b.Sponsored || b.Advertiser != "Acme" || b.MaxPerSession != 3 {
		t.Fatalf("advertorial = %+v", b)
	}
}

func sponsoredHit(atin, campaign string) port.SearchHit {
	h := hit(atin, "Promoted "+atin, "Groceries", photo(atin), 4.8)
	h.Sponsored = &port.SearchSponsored{Label: "Sponsored", Advertiser: "Mama Put", CampaignID: campaign, Slot: 1, Token: "tok-" + atin}
	return h
}

func noPhoto(h port.SearchHit) port.SearchHit {
	h.ImageURL = ""
	return h
}

func TestSponsoredProductsRailUsesAdHitsOnlyAndClaimsBeforeOrganicRails(t *testing.T) {
	var asked []port.SearchParams
	search := fnSearch{fn: func(p port.SearchParams) (port.SearchResult, error) {
		asked = append(asked, p)
		if p.Ads {
			return port.SearchResult{SearchID: "srch-1", Hits: []port.SearchHit{hit("ATORG00000", "organic", "Groceries", photo("ATORG00000"), 4),
				sponsoredHit("ATSPN00001", "c1"), sponsoredHit("ATORG00001", "c2"), noPhoto(sponsoredHit("ATNOPHOTO", "c3"))}}, nil
		}
		return port.SearchResult{Hits: manyHits("ATORG", 8)}, nil
	}}
	pl := domain.Placements{
		Rails: []domain.RailPlacement{{ID: "plc_rail", Title: "Rail", Href: "/s", Source: domain.RailSource{Kind: "search"}}},
		Body: []domain.BodyPlacement{
			{ID: "plc_rail", Slot: "home_product_row", Priority: 50},
			{ID: "plc_sp", Slot: "home_sponsored_products", Priority: 10, Advertiser: "Marketplace sellers", Payload: payload(`{"title":"Promoted","query":"rice"}`)},
		}}
	h, _ := composeService(pl, search, nil).Home(context.Background(), nil)
	sp := find(h, "plc_sp")
	if sp == nil || !sp.Sponsored || sp.SearchID != "srch-1" || len(sp.Rail.Items) != 2 {
		t.Fatalf("sponsored block = %+v", sp)
	}
	if got := sp.Rail.Items[0]; got.Sponsor == nil || got.Sponsor.AdToken != "tok-ATSPN00001" || got.Sponsor.CampaignID != "c1" {
		t.Errorf("click token and campaign must reach the card: %+v", got.Sponsor)
	}
	ads := false
	for _, p := range asked {
		ads = ads || p.Ads && p.NoTrack && p.Q == "rice"
	}
	if !ads {
		t.Errorf("the module must run the ad auction (ads=true) without logging a search event: %+v", asked)
	}
	// The organic rail is ahead of it on the page, yet the advertised product is not repeated there.
	for _, p := range find(h, "plc_rail").Rail.Items {
		if p.ATIN == "ATSPN00001" || p.ATIN == "ATORG00001" {
			t.Errorf("advertised product %s repeated in an organic rail", p.ATIN)
		}
	}
}

func TestSponsoredProductsAreOmittedWhenSearchFailsOrHasNoAds(t *testing.T) {
	pl := domain.Placements{Body: []domain.BodyPlacement{{ID: "plc_sp", Slot: "home_sponsored_products", Priority: 10, Advertiser: "Sellers", Payload: payload(`{}`)}}}
	failing := fnSearch{fn: func(port.SearchParams) (port.SearchResult, error) { return port.SearchResult{}, errors.New("down") }}
	if h, err := composeService(pl, failing, nil).Home(context.Background(), nil); err != nil || find(h, "plc_sp") != nil {
		t.Errorf("a failing ad service must omit the block, not the page: err=%v %v", err, blockTypes(h))
	}
	one := fnSearch{fn: func(port.SearchParams) (port.SearchResult, error) {
		return port.SearchResult{Hits: []port.SearchHit{sponsoredHit("ATSPN00001", "c1")}}, nil
	}}
	if h, _ := composeService(pl, one, nil).Home(context.Background(), nil); find(h, "plc_sp") != nil {
		t.Errorf("a one-product sponsored rail is not shown")
	}
}

func TestRailsBelowTheMinimumAreHidden(t *testing.T) {
	pl := domain.Placements{Rails: []domain.RailPlacement{
		{ID: "few", Title: "Few", Href: "/s", Source: domain.RailSource{Kind: "search"}},
		{ID: "lowered", Title: "Lowered", Href: "/s", Source: domain.RailSource{Kind: "search"}, MinItems: 2}}}
	h, _ := composeService(pl, organic(3), nil).Home(context.Background(), nil)
	if find(h, "few") != nil || find(h, "lowered") == nil {
		t.Errorf("min items: %v", blockTypes(h))
	}
}

func TestDealsNeedARealDiscountPhotoAndStock(t *testing.T) {
	list := func(p int64) *int64 { return &p }
	pool := []domain.ProductSummary{
		{ATIN: "ATD0000001", PriceMinor: 800, ListPriceMinor: list(1000), InStock: true, ImageURL: strp(photo("a"))},  // 20%
		{ATIN: "ATD0000002", PriceMinor: 950, ListPriceMinor: list(1000), InStock: true, ImageURL: strp(photo("b"))},  // 5%: too small
		{ATIN: "ATD0000003", PriceMinor: 500, ListPriceMinor: list(1000), InStock: true, ImageURL: strp(photo("c"))},  // 50%
		{ATIN: "ATD0000004", PriceMinor: 500, ListPriceMinor: list(1000), InStock: false, ImageURL: strp(photo("d"))}, // sold out
		{ATIN: "ATD0000005", PriceMinor: 500, ListPriceMinor: list(1000), InStock: true, ImageURL: strp("/placeholder.png")},
		{ATIN: "ATD0000006", PriceMinor: 500, InStock: true, ImageURL: strp(photo("f"))}, // no compare-at price
	}
	got := dealsFrom(pool, 10)
	if len(got) != 2 || got[0].ATIN != "ATD0000003" || got[1].ATIN != "ATD0000001" {
		t.Fatalf("deals = %+v", got)
	}
	if discountPercent(got[0]) != 50 || discountPercent(pool[5]) != 0 {
		t.Errorf("discount percent")
	}
	if len(dealsFrom(pool[1:2], 10)) != 0 {
		t.Errorf("a module with no qualifying deal is empty (and so hidden)")
	}
	if e := endOfDay(time.Date(2026, 11, 4, 23, 59, 0, 0, time.UTC)); e != "2026-11-05T00:00:00Z" {
		t.Errorf("endOfDay = %s", e)
	}
}

func TestSellerAndCommunitySpotlightRules(t *testing.T) {
	mk := func(seller string, kind string, rating float64, reviews int, n int) []domain.ProductSummary {
		var out []domain.ProductSummary
		for i := range n {
			out = append(out, domain.ProductSummary{ATIN: fmt.Sprintf("AT%s%d", seller[:3], i), SellerName: seller, SellerKind: kind, Rating: rating, ReviewCount: reviews})
		}
		return out
	}
	pool := append(append(append(mk("Alpha Foods", domain.SellerKindSeller, 4.2, 40, 4), mk("Beta Spices", domain.SellerKindSeller, 4.9, 30, 4)...),
		mk("Gamma New", domain.SellerKindSeller, 5.0, 0, 5)...), mk("AfroTransact Retail", domain.SellerKindPlatform, 5, 900, 5)...)
	name, items, avg, reviews := topSeller(pool, 3)
	if name != "Beta Spices" || len(items) != 4 || avg < 4.89 || reviews != 120 {
		t.Errorf("top seller = %s %d %.2f %d (unreviewed sellers and the platform cannot win)", name, len(items), avg, reviews)
	}
	if n, _, _, _ := topSeller(mk("Delta", domain.SellerKindSeller, 4.9, 10, 2), 3); n != "" {
		t.Errorf("a seller with too few products is not featured")
	}
	facets := map[string][]port.SearchFacetBucket{"communities": {{Value: "Ghanaian", Count: 9}, {Value: "nigerian", Count: 12}, {Value: "tiny", Count: 1}}}
	a, b := pickCommunity(facets, 4, "day1"), pickCommunity(facets, 4, "day1")
	if a != b || (a != "ghanaian" && a != "nigerian") {
		t.Errorf("community pick must be stable per seed and skip thin communities: %q %q", a, b)
	}
	if pickCommunity(nil, 4, "x") != "" {
		t.Errorf("no facets, no spotlight")
	}
}

func TestServicesTeaserIsHiddenWithoutProviders(t *testing.T) {
	pl := domain.Placements{Body: []domain.BodyPlacement{{ID: "plc_svc", Slot: "home_block", Priority: 10, Payload: payload(`{"type":"services-near-you"}`)}}}
	if h, _ := composeService(pl, organic(0), emptyServices{n: 0}).Home(context.Background(), nil); find(h, "plc_svc") != nil {
		t.Errorf("no providers in the zone: hidden")
	}
	h, _ := composeService(pl, organic(0), emptyServices{n: 2}).Home(context.Background(), nil)
	if b := find(h, "plc_svc"); b == nil || b.Href != "/services" || b.Title != "Services near Houston" {
		t.Errorf("teaser = %+v", b)
	}
}

func TestPersonalBlocksAreImplicitAndMarkedForTheClient(t *testing.T) {
	h, _ := composeService(domain.Placements{}, organic(0), nil).Home(context.Background(), nil)
	if got := blockTypes(h); !equal(got, []string{"recently-viewed:recently-viewed"}) {
		t.Errorf("a guest on an empty composition gets only the client-side recently viewed marker: %v", got)
	}
}

func TestRecordAdEventValidatesAndForwardsOnlyThePlacementAndKind(t *testing.T) {
	c := &recordingContent{}
	svc := NewSiteService(SiteDeps{Content: c})
	if err := svc.RecordAdEvent(context.Background(), "plc_home_brand", "impression"); err != nil || len(c.events) != 1 || c.events[0] != "plc_home_brand:impression" {
		t.Fatalf("err=%v events=%v", err, c.events)
	}
	for _, bad := range [][2]string{{"x", "click"}, {"plc_a", "view"}, {"", ""}, {"plc_a b", "click"}} {
		if err := svc.RecordAdEvent(context.Background(), bad[0], bad[1]); err == nil {
			t.Errorf("%v must be rejected", bad)
		}
	}
	if len(c.events) != 1 {
		t.Errorf("invalid events must not reach content: %v", c.events)
	}
}

func TestSponsoredProductsRunOneAuctionPerTermAndKeepEachCardsSearchID(t *testing.T) {
	search := fnSearch{fn: func(p port.SearchParams) (port.SearchResult, error) {
		switch p.Q {
		case "rice":
			return port.SearchResult{SearchID: "srch-rice", Hits: []port.SearchHit{sponsoredHit("ATRIC00001", "c1"), sponsoredHit("ATRIC00002", "c2")}}, nil
		case "shea":
			return port.SearchResult{SearchID: "srch-shea", Hits: []port.SearchHit{sponsoredHit("ATRIC00002", "c2"), sponsoredHit("ATSHE00001", "c3")}}, nil
		}
		return port.SearchResult{}, nil
	}}
	pl := domain.Placements{Body: []domain.BodyPlacement{{ID: "plc_sp", Slot: "home_sponsored_products", Priority: 10, Advertiser: "Sellers", Payload: payload(`{"title":"Promoted","query":"rice OR shea"}`)}}}
	h, _ := composeService(pl, search, nil).Home(context.Background(), nil)
	sp := find(h, "plc_sp")
	if sp == nil || len(sp.Rail.Items) != 3 {
		t.Fatalf("three distinct sponsored products expected: %+v", sp)
	}
	got := map[string]string{}
	for _, p := range sp.Rail.Items {
		got[p.ATIN] = p.Sponsor.SearchID
	}
	if got["ATRIC00001"] != "srch-rice" || got["ATSHE00001"] != "srch-shea" || got["ATRIC00002"] != "srch-rice" {
		t.Errorf("each card must carry the search its token was signed for: %v", got)
	}
}

func TestAdRowWithoutAPlacementAdvertiserNeedsEachAdToNameOne(t *testing.T) {
	pl := domain.Placements{Body: []domain.BodyPlacement{
		{ID: "plc_named", Slot: "home_ad_row", Priority: 9, Payload: payload(`{"ads":[{"imageUrl":"https://i/1.jpg","alt":"a","href":"/x","advertiser":"Acme"},{"imageUrl":"https://i/2.jpg","alt":"b","href":"/y","advertiser":"Beta"}]}`)},
		{ID: "plc_unnamed", Slot: "home_ad_row", Priority: 8, Payload: payload(`{"ads":[{"imageUrl":"https://i/1.jpg","alt":"a","href":"/x"},{"imageUrl":"https://i/2.jpg","alt":"b","href":"/y"}]}`)}}}
	h, _ := composeService(pl, organic(0), nil).Home(context.Background(), nil)
	named := find(h, "plc_named")
	if named == nil || !named.Sponsored || len(named.Ads) != 2 || named.Ads[1].Advertiser != "Beta" {
		t.Fatalf("named ad row = %+v", named)
	}
	if find(h, "plc_unnamed") != nil {
		t.Errorf("image ads that name no advertiser must not be shown")
	}
}
