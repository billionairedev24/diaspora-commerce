package app

import (
	"context"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- fakes

type fakeProduct struct {
	atin, dept, community, seller string
	photo                         string
	inStock                       bool
	rating                        float64
	reviews                       int
	idx                           int
}

// catSearch is a search service over a generated catalogue: it filters like the real one (department, community,
// seller kind, eligibleOnly = in stock and a real photo), sorts, counts facets and distinct sellers, and counts calls.
type catSearch struct {
	port.SearchClient
	mu       sync.Mutex
	products []fakeProduct
	calls    []port.SearchParams
}

func newCatalogue(n, sellers, photoless int) *catSearch {
	depts := []string{"Groceries & Food", "Beauty & Personal Care", "Fashion & Accessories", "Home & Household"}
	shares := []int{50, 25, 15, 10}
	c := &catSearch{}
	i := 0
	for d, name := range depts {
		for k := 0; k < n*shares[d]/100 || (d == len(depts)-1 && i < n); k++ {
			if i >= n {
				break
			}
			p := fakeProduct{atin: fmt.Sprintf("AT%08d", i), dept: name, seller: fmt.Sprintf("Seller %d", i%max(1, sellers)), photo: fmt.Sprintf("https://img.example/p/%d.jpg", i),
				inStock: true, idx: i}
			switch {
			case i%3 == 0:
				p.community = "Nigerian"
			case i%7 == 0:
				p.community = "ghanaian" // a case variant: must merge with Ghanaian
			case i%5 == 0:
				p.community = "Ghanaian"
			case i%2 == 0:
				p.community = "Ethiopian"
			}
			if i%4 != 0 {
				p.rating, p.reviews = 3+float64((i*13+i/7)%23)/12, 1+i%30
			}
			if i < photoless {
				p.photo = ""
			}
			c.products = append(c.products, p)
			i++
		}
	}
	return c
}

func (c *catSearch) Search(_ context.Context, p port.SearchParams) (port.SearchResult, error) {
	c.mu.Lock()
	c.calls = append(c.calls, p)
	c.mu.Unlock()
	comm := map[string]bool{}
	for _, v := range strings.Split(p.Community, ",") {
		if v != "" {
			comm[v] = true
		}
	}
	var match []fakeProduct
	for _, x := range c.products {
		if p.Department != "" && x.dept != p.Department {
			continue
		}
		if len(comm) > 0 && !comm[x.community] {
			continue
		}
		if p.SellerKind == domain.SellerKindPlatform && x.seller != "Seller 0" {
			continue
		}
		if (p.InStock || p.EligibleOnly) && !x.inStock {
			continue
		}
		if p.EligibleOnly && !realPhoto(&x.photo) {
			continue
		}
		match = append(match, x)
	}
	switch p.Sort {
	case domain.SortRating:
		sort.SliceStable(match, func(i, j int) bool { return match[i].rating > match[j].rating })
	case domain.SortNewest:
		sort.SliceStable(match, func(i, j int) bool { return match[i].idx > match[j].idx })
	}
	res := port.SearchResult{Total: int64(len(match)), Facets: map[string][]port.SearchFacetBucket{}}
	depts, comms, sellers := map[string]int64{}, map[string]int64{}, map[string]bool{}
	for _, x := range match {
		depts[x.dept]++
		if x.community != "" {
			comms[x.community]++
		}
		sellers[x.seller] = true
	}
	for k, v := range depts {
		res.Facets["department"] = append(res.Facets["department"], port.SearchFacetBucket{Value: k, Count: v})
	}
	for k, v := range comms {
		res.Facets["communities"] = append(res.Facets["communities"], port.SearchFacetBucket{Value: k, Count: v})
	}
	if p.EligibleOnly {
		res.DistinctSellers = int64(len(sellers))
	}
	size := p.Size
	if size <= 0 {
		size = 10
	}
	for _, x := range match {
		if len(res.Hits) >= size {
			break
		}
		res.Hits = append(res.Hits, port.SearchHit{ATIN: x.atin, Title: "Product " + x.atin, CategoryPath: x.dept + " › x", ImageURL: x.photo, Rating: x.rating, ReviewCount: x.reviews,
			InStock: x.inStock, AvailableQty: 5 + x.idx%40, PriceMinor: 700, Currency: "USD", SellerName: x.seller, SellerKind: domain.SellerKindSeller, Fulfilment: "SELLER"})
	}
	return res, nil
}

func (c *catSearch) callCount() int { c.mu.Lock(); defer c.mu.Unlock(); return len(c.calls) }

type fakeHome struct {
	mu       sync.Mutex
	settings domain.HomeSettings
	registry []domain.CommunityEntry
	loads    int
}

func (f *fakeHome) HomeSettings(context.Context) (domain.HomeSettings, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.loads++
	return f.settings, nil
}
func (f *fakeHome) CommunityRegistry(context.Context) ([]domain.CommunityEntry, error) {
	return f.registry, nil
}
func (f *fakeHome) PlacementsAt(context.Context, string, string, time.Time) (domain.Placements, error) {
	return domain.Placements{}, nil
}

type fakeMemory struct {
	mu sync.Mutex
	m  map[string]port.HomeVisitState
}

func (f *fakeMemory) Load(_ context.Context, k string) (port.HomeVisitState, bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	v, ok := f.m[k]
	return v, ok, nil
}
func (f *fakeMemory) Save(_ context.Context, k string, st port.HomeVisitState, _ time.Duration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.m == nil {
		f.m = map[string]port.HomeVisitState{}
	}
	f.m[k] = st
	return nil
}

type fakeServices struct {
	port.CustomerBookingsClient
	mu    sync.Mutex
	all   []port.UpBkListing
	calls int
}

func (f *fakeServices) Search(_ context.Context, _, _, zip string) ([]port.UpBkListing, error) {
	f.mu.Lock()
	f.calls++
	f.mu.Unlock()
	if zip != "" {
		return nil, nil
	}
	return f.all, nil
}

type dynLocation struct{ LocationService }

func (dynLocation) Current(context.Context, *domain.Caller) domain.DeliveryLocation {
	return domain.DeliveryLocation{ZIP: "77042", City: "Houston", State: "TX", Label: "Houston", Serviceable: true, Source: domain.LocationDefault}
}

type testClock struct{ t time.Time }

func (c *testClock) now() time.Time { return c.t }

type homeRig struct {
	svc    *siteServiceImpl
	search *catSearch
	home   *fakeHome
	mem    *fakeMemory
	clock  *testClock
	svcs   *fakeServices
}

func newHomeRig(t *testing.T, n, sellers int, env string) *homeRig {
	t.Helper()
	r := &homeRig{search: newCatalogue(n, sellers, 0), home: &fakeHome{settings: domain.DefaultHomeSettings()}, mem: &fakeMemory{}, clock: &testClock{t: homeNow}, svcs: &fakeServices{}}
	r.svc = NewSiteService(SiteDeps{Content: homeContent{}, Search: r.search, Catalog: homeCatalog{category: map[string]string{}}, Inventory: homeInventory{}, Location: dynLocation{},
		Estimator: est, Market: "us", Clock: r.clock.now, Env: env, HomeContent: r.home, Memory: r.mem, Services: r.svcs}).(*siteServiceImpl)
	return r
}

func (r *homeRig) compose(t *testing.T, req HomeRequest) domain.Home {
	t.Helper()
	h, err := r.svc.HomeFor(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func (r *homeRig) advance(d time.Duration) { r.clock.t = r.clock.t.Add(d) }

func homeBlockTypes(h domain.Home) []string {
	var out []string
	for _, b := range h.Blocks {
		out = append(out, b.Type+":"+b.Module)
	}
	return out
}

func productIDs(h domain.Home) []string {
	var out []string
	for _, b := range h.Blocks {
		if b.Rail != nil {
			for _, p := range b.Rail.Items {
				out = append(out, p.ATIN)
			}
		}
	}
	return out
}

func hasModule(h domain.Home, m string) bool {
	for _, b := range h.Blocks {
		if b.Module == m {
			return true
		}
	}
	return false
}

func guest(visitor, window string) HomeRequest {
	return HomeRequest{Visitor: visitor, Window: window, Locale: "en"}
}

// ---------------------------------------------------------------- fixtures 0 / 10 / 100 / 10,000

func TestHomeFixtures0_10_100_10000(t *testing.T) {
	cases := []struct {
		n, sellers int
		want       []string
		absent     []string
	}{
		{0, 0, []string{domain.ModBrandHero, domain.ModSellBand}, []string{domain.ModDepartments, domain.ModCommunities, domain.ModNewArrivals, domain.ModTopSellers, domain.ModGroceries}},
		{10, 3, []string{domain.ModBrandHero, domain.ModDepartments, domain.ModSellBand}, []string{domain.ModCommunities, domain.ModTopSellers, domain.ModGroceries}},
		{100, 10, []string{domain.ModBrandHero, domain.ModDepartments, domain.ModGroceries, domain.ModNewArrivals, domain.ModTopSellers, domain.ModCommunities, domain.ModSellBand}, nil},
		{10000, 400, []string{domain.ModDepartments, domain.ModGroceries, domain.ModNewArrivals, domain.ModTopSellers, domain.ModCommunities, domain.ModDeptRail, domain.ModSellBand}, nil},
	}
	for _, c := range cases {
		t.Run(fmt.Sprintf("%d products", c.n), func(t *testing.T) {
			r := newHomeRig(t, c.n, c.sellers, "dev")
			h := r.compose(t, guest("v1", "w1"))
			if h.Version != 2 {
				t.Fatalf("dev runs the dynamic home, got version %d", h.Version)
			}
			for _, m := range c.want {
				if !hasModule(h, m) {
					t.Errorf("%s should be on the page of %d products: %v", m, c.n, homeBlockTypes(h))
				}
			}
			for _, m := range c.absent {
				if hasModule(h, m) {
					t.Errorf("%s must not be on the page of %d products", m, c.n)
				}
			}
			if len(h.Blocks) > 12 {
				t.Errorf("page capped at 12 modules, got %d", len(h.Blocks))
			}
			assertPageRules(t, h, 2)
		})
	}
}

// assertPageRules: no duplicate product, seller share per module, copy keys not English, tiles have real photos.
func assertPageRules(t *testing.T, h domain.Home, share int) {
	t.Helper()
	seen := map[string]bool{}
	for _, b := range h.Blocks {
		if b.TitleKey != "" && b.Title != "" {
			t.Errorf("%s has a title key and an English title", b.ID)
		}
		if b.Module != "" && b.Title != "" && b.Type != domain.BlockDeals {
			t.Errorf("generated module %s carries English copy %q", b.Module, b.Title)
		}
		if b.TitleKey != "" && !containsStr(domain.HomeCopyKeys, b.TitleKey) {
			t.Errorf("unknown copy key %s", b.TitleKey)
		}
		if b.Rail != nil {
			per := map[string]int{}
			for _, p := range b.Rail.Items {
				if seen[p.ATIN] {
					t.Errorf("%s shown twice on the page", p.ATIN)
				}
				seen[p.ATIN] = true
				per[p.SellerName]++
				if !realPhoto(p.ImageURL) || !p.InStock {
					t.Errorf("%s is not eligible", p.ATIN)
				}
				if p.ReviewCount == 0 && b.Module == domain.ModTopSellers {
					t.Errorf("an unreviewed product in Top rated")
				}
			}
			if len(per) >= 5 {
				for s, n := range per {
					if n > share {
						t.Errorf("%s has %d items in %s, share is %d", s, n, b.ID, share)
					}
				}
			}
		}
		for _, cell := range b.Cells {
			if !realPhoto(cell.ImageURL) {
				t.Errorf("tile %s has no real photo", cell.Label)
			}
		}
	}
}

func containsStr(l []string, s string) bool {
	for _, x := range l {
		if x == s {
			return true
		}
	}
	return false
}

func TestZeroProductsIsATextOnlyBrandPageWithNoDemoData(t *testing.T) {
	r := newHomeRig(t, 0, 0, "dev")
	h := r.compose(t, guest("v1", "w1"))
	got := homeBlockTypes(h)
	want := []string{"brand-hero:brand-hero", "sell-band:sell-band"}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("0 products: %v, want %v", got, want)
	}
	if !h.Blocks[0].TextOnly || h.Blocks[0].ImageURL != nil {
		t.Error("the brand hero is text only")
	}
	if len(h.Hero) != 0 || len(h.Tiles) != 0 || len(h.Announcements) != 0 {
		t.Errorf("no demo hero, tiles or announcements at 0 products: %+v", h)
	}
	// hidden modules cost no upstream call: one signals query, nothing else
	if n := r.search.callCount(); n != 1 {
		t.Fatalf("0 products made %d search calls, want exactly 1 (the signals query)", n)
	}
}

func TestHiddenModulesTriggerNoPoolSearch(t *testing.T) {
	r := newHomeRig(t, 10, 3, "dev") // 10 products: deals (min 6 with 2 sellers) and groceries (min 6) and top sellers (min 8) are hidden
	r.compose(t, guest("v1", "w1"))
	for _, p := range r.search.calls {
		if p.Department == "Groceries & Food" && p.Size > 1 {
			t.Errorf("groceries rail was fetched although it has fewer than 6 products: %+v", p)
		}
	}
}

// ---------------------------------------------------------------- variety

func TestVisitIsStableAndNewVisitsDiffer(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	a := productIDs(r.compose(t, guest("visitor-9", "visit-1")))
	for i := 0; i < 5; i++ {
		if b := productIDs(r.compose(t, guest("visitor-9", "visit-1"))); fmt.Sprint(a) != fmt.Sprint(b) {
			t.Fatalf("refresh inside one visit changed the page:\n%v\n%v", a, b)
		}
	}
	distinct := map[string]bool{fmt.Sprint(a): true}
	for v := 2; v < 12; v++ {
		distinct[fmt.Sprint(productIDs(r.compose(t, guest("visitor-9", fmt.Sprintf("visit-%d", v)))))] = true
	}
	if len(distinct) < 9 {
		t.Fatalf("11 visits gave only %d different pages", len(distinct))
	}
	// and not only the products: the module order varies too
	orders := map[string]bool{}
	for v := 0; v < 15; v++ {
		orders[fmt.Sprint(homeBlockTypes(r.compose(t, guest("visitor-9", fmt.Sprintf("o-%d", v)))))] = true
	}
	if len(orders) < 3 {
		t.Fatalf("module order should vary between visits, saw %d orders", len(orders))
	}
}

func TestDifferentVisitorsSeeDifferentPages(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	seen := map[string]bool{}
	for v := 0; v < 10; v++ {
		seen[fmt.Sprint(productIDs(r.compose(t, guest(fmt.Sprintf("visitor-%d", v), "w"))))] = true
	}
	if len(seen) < 8 {
		t.Fatalf("10 visitors saw %d different pages", len(seen))
	}
}

func TestHeroStaysFirstAndStructureBandsHold(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	for v := 0; v < 20; v++ {
		h := r.compose(t, guest("v", fmt.Sprint(v)))
		if h.Blocks[0].Module != domain.ModBrandHero {
			t.Fatalf("hero band is first: %v", homeBlockTypes(h))
		}
		if last := h.Blocks[len(h.Blocks)-1]; last.Module != domain.ModSellBand {
			t.Fatalf("brand band is last: %v", homeBlockTypes(h))
		}
	}
}

func TestNoveltyMemoryReducesRepeatsAcrossLoads(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	repeats := func(useMemory bool) int {
		total := 0
		for v := 0; v < 25; v++ {
			first := r.compose(t, guest("g", fmt.Sprintf("a%d", v)))
			req := guest("g", fmt.Sprintf("b%d", v))
			if useMemory {
				req.Seen = first.Visit.Seen
			}
			second := r.compose(t, req)
			prev := map[string]bool{}
			for _, id := range productIDs(first) {
				prev[id] = true
			}
			for _, id := range productIDs(second) {
				if prev[id] {
					total++
				}
			}
		}
		return total
	}
	without, with := repeats(false), repeats(true)
	if with >= without {
		t.Fatalf("novelty memory should reduce repeats: %d with, %d without", with, without)
	}
}

func TestSignedInMemoryIsServerSideAndStableInsideAVisit(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	c := &domain.Caller{Subject: "cust-1", BearerToken: "t"}
	req := HomeRequest{Caller: c, Locale: "en"}
	a := productIDs(r.compose(t, req))
	for i := 0; i < 4; i++ {
		r.advance(2 * time.Minute) // refreshes inside the 30 minute window
		if b := productIDs(r.compose(t, req)); fmt.Sprint(a) != fmt.Sprint(b) {
			t.Fatalf("a signed-in refresh inside the visit changed the page")
		}
	}
	r.advance(45 * time.Minute) // a new visit
	next := productIDs(r.compose(t, req))
	if fmt.Sprint(a) == fmt.Sprint(next) {
		t.Fatal("a new visit after 30 minutes of inactivity shows a different page")
	}
	repeat := 0
	prev := map[string]bool{}
	for _, id := range a {
		prev[id] = true
	}
	for _, id := range next {
		if prev[id] {
			repeat++
		}
	}
	if repeat > len(next)*7/10 {
		t.Errorf("the new visit repeats %d of %d items", repeat, len(next))
	}
	if len(r.mem.m) != 1 {
		t.Error("the history is kept server side, one entry for the shopper")
	}
	for k := range r.mem.m {
		if strings.Contains(k, "cust-1") {
			t.Error("the memory key must not be the customer id")
		}
	}
}

func TestCrawlerGetsTheDeterministicPage(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	a := r.compose(t, HomeRequest{Crawler: true, Visitor: "x", Window: "1"})
	b := r.compose(t, HomeRequest{Crawler: true, Visitor: "y", Window: "2", Seen: []string{"deadbeef"}})
	if fmt.Sprint(homeBlockTypes(a)) != fmt.Sprint(homeBlockTypes(b)) || fmt.Sprint(productIDs(a)) != fmt.Sprint(productIDs(b)) {
		t.Fatal("crawlers must get the same page whatever visitor, window or memory")
	}
	if !a.Visit.Deterministic {
		t.Error("the response says it is deterministic")
	}
	// no visitor id at all is the same deterministic page
	c := r.compose(t, HomeRequest{})
	if fmt.Sprint(productIDs(a)) != fmt.Sprint(productIDs(c)) {
		t.Error("no visitor id gives the deterministic default")
	}
}

func TestDiscoveryModulesExcludeRecentlyViewedAndCart(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	base := productIDs(r.compose(t, guest("v", "w")))
	ex := base[:6]
	req := guest("v", "w")
	req.Exclude = ex
	for _, id := range productIDs(r.compose(t, req)) {
		for _, e := range ex {
			if id == e {
				t.Fatalf("%s is in the visitor's recently viewed or cart and must not be in discovery", id)
			}
		}
	}
}

func TestSellerShareIsConfigurableAndDefaultsToTwo(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	assertPageRules(t, r.compose(t, guest("v", "w")), 2)
	st := domain.DefaultHomeSettings()
	st.Caps.SellerShare = 1
	r.home.settings = st
	r.advance(2 * time.Minute)
	assertPageRules(t, r.compose(t, guest("v", "w2")), 1)
}

// ---------------------------------------------------------------- communities, departments, nav, sitemap

func TestCommunitiesNeedThreeWithEightProductsAndTwoSellers(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	h := r.compose(t, guest("v", "w"))
	var cells []domain.BlockCell
	for _, b := range h.Blocks {
		if b.Module == domain.ModCommunities {
			cells = b.Cells
		}
	}
	if len(cells) < 3 {
		t.Fatalf("100 products have 3 qualifying communities: %v", homeBlockTypes(h))
	}
	keys := map[string]bool{}
	for _, c := range cells {
		keys[c.Key] = true
		if c.Key != strings.ToLower(c.Key) || !strings.HasSuffix(c.Href, "/"+c.Key) {
			t.Errorf("label and link come from one key: %+v", c)
		}
	}
	if !keys["ghanaian"] || len(cells) != len(keys) {
		t.Errorf("Ghanaian and ghanaian are one community: %v", cells)
	}
	r2 := newHomeRig(t, 30, 5, "dev") // too thin
	if hasModule(r2.compose(t, guest("v", "w")), domain.ModCommunities) {
		t.Error("30 products: communities stay hidden")
	}
	nav, err := r2.svc.Navigation(context.Background(), "en")
	if err != nil || len(nav.Communities) != 0 {
		t.Errorf("hidden on the home means hidden in the nav and footer: %v %+v", err, nav.Communities)
	}
	if _, _, err := r2.svc.Community(context.Background(), "nigerian", "en"); err == nil {
		t.Error("/communities/nigerian below the threshold is a 404, never a zero-result page")
	}
}

func TestCommunityRouteAliasAndCanonical(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	r.home.registry = []domain.CommunityEntry{{Key: "ghanaian", Labels: domain.LocaleLabels{"en": "Ghanaian", "fr": "Ghanéen"}, Aliases: []string{"ghana"}}}
	c, canonical, err := r.svc.Community(context.Background(), "ghanaian", "fr")
	if err != nil || !canonical || c.Label != "Ghanéen" {
		t.Fatalf("canonical route: %+v %v %v", c, canonical, err)
	}
	if _, canonical, err = r.svc.Community(context.Background(), "Ghana", "en"); err != nil || canonical {
		t.Fatalf("an alias resolves but is not canonical (the storefront redirects): %v %v", canonical, err)
	}
	if c.ImageSource != "product" || !realPhoto(c.ImageURL) {
		t.Errorf("the photo is a real top product: %+v", c)
	}
	r.home.registry[0].ImageURL = strp("https://cdn.example/editorial/placeholder.png")
	r.advance(2 * time.Minute)
	c, _, _ = r.svc.Community(context.Background(), "ghanaian", "en")
	if c.ImageSource != "product" {
		t.Error("an editorial image that fails the real-photo check is ignored")
	}
}

func TestNavigationFromLiveTaxonomyHasNoDeadLinks(t *testing.T) {
	r := newHomeRig(t, 10, 3, "dev")
	nav, err := r.svc.Navigation(context.Background(), "en")
	if err != nil {
		t.Fatal(err)
	}
	if len(nav.All) != 4 || len(nav.Bar) != 4 {
		t.Fatalf("every department with products and no other: %+v", nav.All)
	}
	for _, d := range nav.All {
		if d.Count == 0 || d.Href == "" {
			t.Errorf("a department with nothing in it is not linked: %+v", d)
		}
	}
	labels := map[string]bool{}
	for _, d := range nav.All {
		labels[d.Label] = true
	}
	if !labels["Groceries"] || !labels["Beauty"] || !labels["Fashion"] || !labels["Home"] {
		t.Errorf("short display labels derived from the live names: %v", nav.All)
	}
	if nav.Services.Available {
		t.Error("no provider, no Services entry")
	}
	if nav.Brand.Available != true {
		t.Error("first-party products exist")
	}
	r.svcs.all = []port.UpBkListing{{ServiceID: "s1", OrgID: "o1", Name: "Braiding", ProviderName: "Ama"}}
	r.advance(2 * time.Minute)
	nav, _ = r.svc.Navigation(context.Background(), "fr")
	if !nav.Services.Available || nav.Bar[0].Label != "Épicerie" && nav.Bar[1].Label != "Épicerie" {
		t.Errorf("services appear with any provider; labels follow the locale: %+v", nav)
	}
}

func TestPhotolessProductsDoNotCountAnywhere(t *testing.T) {
	r := newHomeRig(t, 40, 8, "dev")
	for i := range r.search.products {
		if i < 3 {
			r.search.products[i].photo = ""
		}
	}
	h := r.compose(t, guest("v", "w"))
	nav, _ := r.svc.Navigation(context.Background(), "en")
	total := 0
	for _, d := range nav.All {
		total += d.Count
	}
	if total != 37 {
		t.Errorf("3 photo-less products are excluded from the nav counts: %d", total)
	}
	sm, _ := r.svc.SitemapSources(context.Background())
	sum := 0
	for _, e := range sm.Departments {
		sum += e.Count
	}
	if sum != total {
		t.Errorf("sitemap and nav read one snapshot: %d vs %d", sum, total)
	}
	_ = h
}

func TestSitemapOmitsEmptyDepartmentsAndHiddenCommunities(t *testing.T) {
	r := newHomeRig(t, 30, 5, "dev")
	sm, err := r.svc.SitemapSources(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(sm.Communities) != 0 {
		t.Errorf("hidden communities leave no sitemap trace: %v", sm.Communities)
	}
	for _, e := range sm.Departments {
		if e.Count == 0 {
			t.Errorf("empty department in the sitemap: %+v", e)
		}
	}
}

// ---------------------------------------------------------------- services module

func TestServicesShownWheneverAnyProviderExistsNearYouNeedsAZip(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	r.svcs.all = []port.UpBkListing{{ServiceID: "s1", OrgID: "o1", Name: "Braiding", ProviderName: "Ama", PriceFromMinor: 6000, Currency: "USD", ImageURL: "https://img.example/s1.jpg"},
		{ServiceID: "s2", OrgID: "o2", Name: "Catering", ProviderName: "Kofi", ImageURL: "https://img.example/icons/x.svg"}}
	h := r.compose(t, guest("v", "w"))
	var svc *domain.HomeBlock
	for i := range h.Blocks {
		if h.Blocks[i].Module == domain.ModServices {
			svc = &h.Blocks[i]
		}
	}
	if svc == nil {
		t.Fatalf("providers exist in the market: the module shows even when none serves the ZIP: %v", homeBlockTypes(h))
	}
	if svc.TitleKey != domain.KeyServicesPopular {
		t.Errorf("no provider serves the ZIP: popular services, got %s", svc.TitleKey)
	}
	for _, c := range svc.Services {
		if c.ID == "s2" && c.ImageURL != nil {
			t.Error("a service with an icon image has no picture, never an icon")
		}
	}
}

// ---------------------------------------------------------------- mode, settings and the cache

func TestModeDefaultsByEnvironmentAndSettingsOverride(t *testing.T) {
	for env, want := range map[string]int{"dev": 2, "uat": 2, "trial": 2, "local": 2, "prod": 0} {
		r := newHomeRig(t, 100, 10, env)
		if got := r.compose(t, guest("v", "w")).Version; got != want {
			t.Errorf("%s: version %d, want %d", env, got, want)
		}
	}
	prod := newHomeRig(t, 100, 10, "prod")
	on := domain.DynamicOn
	prod.home.settings.Dynamic = &on
	prod.advance(2 * time.Minute)
	if prod.compose(t, guest("v", "w")).Version != 2 {
		t.Error("the owner flips prod on in settings")
	}
	dev := newHomeRig(t, 100, 10, "dev")
	off := domain.DynamicOff
	dev.home.settings.Dynamic = &off
	if dev.compose(t, guest("v", "w")).Version != 0 {
		t.Error("home.dynamic=off restores today's composition without a deploy")
	}
}

func TestShadowModeServesLegacyAndComparesWithoutRemembering(t *testing.T) {
	r := newHomeRig(t, 100, 10, "prod")
	shadow := domain.DynamicShadow
	r.home.settings.Dynamic = &shadow
	c := &domain.Caller{Subject: "cust-1", BearerToken: "t"}
	h := r.compose(t, HomeRequest{Caller: c})
	if h.Version != 0 {
		t.Error("shadow mode: the live response is unchanged")
	}
	if len(r.mem.m) != 0 {
		t.Error("shadow mode remembers nothing")
	}
}

func TestSettingsChangeReachesTheGatewayWithinTheCacheTTL(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	if !hasModule(r.compose(t, guest("v", "w")), domain.ModGroceries) {
		t.Fatal("groceries shown by default")
	}
	st := domain.DefaultHomeSettings()
	g := st.Modules[domain.ModGroceries]
	g.Enabled = false
	st.Modules[domain.ModGroceries] = g
	r.home.settings = st
	r.advance(30 * time.Second)
	if !hasModule(r.compose(t, guest("v", "w")), domain.ModGroceries) {
		t.Fatal("inside the 60 s TTL the earlier settings still apply")
	}
	r.advance(31 * time.Second)
	if hasModule(r.compose(t, guest("v", "w")), domain.ModGroceries) {
		t.Fatal("after the TTL the staff change is live")
	}
}

func TestSignalsAreSharedAcrossVisitorsWithinTheTTL(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	r.compose(t, guest("a", "1"))
	after := r.search.callCount()
	for i := 0; i < 5; i++ {
		r.compose(t, guest(fmt.Sprintf("b%d", i), "1"))
	}
	if extra := r.search.callCount() - after; extra > 4 { // only a different "Top in" department pool may be fetched, once
		t.Errorf("5 more visitors caused %d extra upstream searches", extra)
	}
}

// ---------------------------------------------------------------- staff: preview, debug, replay

func TestStaffPreviewExplainsEveryModuleAndReplaysFixtures(t *testing.T) {
	r := newHomeRig(t, 100, 10, "prod")
	for _, catalog := range []string{"0", "10", "100", "10000"} {
		_, decisions, err := r.svc.PreviewHome(context.Background(), HomeRequest{Catalog: catalog, Locale: "en"})
		if err != nil {
			t.Fatal(err)
		}
		if len(decisions) < len(domain.ModuleLabels()) {
			t.Fatalf("catalog %s: %d decisions", catalog, len(decisions))
		}
		for _, d := range decisions {
			if !d.Shown && (d.Reason == "" || d.Detail == "") {
				t.Errorf("catalog %s: %s hidden without a reason", catalog, d.Module)
			}
			if d.Label == "" || strings.Contains(d.Detail, "atin") {
				t.Errorf("decisions name modules, never ids: %+v", d)
			}
		}
	}
	h, decisions, _ := r.svc.PreviewHome(context.Background(), HomeRequest{Catalog: "0"})
	if len(h.Blocks) != 2 {
		t.Errorf("the 0 fixture is the brand page: %v", homeBlockTypes(h))
	}
	var comm domain.ModuleDecision
	_, d10, _ := r.svc.PreviewHome(context.Background(), HomeRequest{Catalog: "10"})
	for _, d := range d10 {
		if d.Module == domain.ModCommunities {
			comm = d
		}
	}
	if comm.Shown || comm.Reason == "" || !strings.Contains(comm.Detail, "3 communities") {
		t.Errorf("staff read why communities are hidden: %+v", comm)
	}
	_ = decisions
	if r.search.callCount() != 0 {
		t.Error("a fixture replay reads no service")
	}
}

func TestDebugCarriesSeedAndReplayReproducesTheView(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	req := guest("v", "w")
	req.Debug = true
	a := r.compose(t, req)
	if a.Debug == nil || a.Debug.Seed == "" || len(a.Debug.Decisions) == 0 {
		t.Fatalf("debug: %+v", a.Debug)
	}
	replay := HomeRequest{Debug: true, Seed: a.Debug.Seed, Visitor: "someone-else", Window: "other"}
	b := r.compose(t, replay)
	if fmt.Sprint(productIDs(a)) != fmt.Sprint(productIDs(b)) || fmt.Sprint(homeBlockTypes(a)) != fmt.Sprint(homeBlockTypes(b)) {
		t.Fatal("the seed reproduces the exact view")
	}
	if plain := r.compose(t, guest("v", "w")); plain.Debug != nil {
		t.Error("no debug field without debug mode")
	}
}

func TestAsOfPreviewReadsPlacementsAtThatInstant(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	called := false
	r.svc.d.HomeContent = &asOfContent{fakeHome: r.home, onAt: func(at time.Time) { called = true }}
	future := homeNow.Add(40 * 24 * time.Hour)
	if _, _, err := r.svc.PreviewHome(context.Background(), HomeRequest{AsOf: future}); err != nil || !called {
		t.Fatalf("as-of preview must ask content for the placements live at that instant: %v %v", called, err)
	}
}

type asOfContent struct {
	*fakeHome
	onAt func(time.Time)
}

func (a *asOfContent) PlacementsAt(_ context.Context, _, _ string, at time.Time) (domain.Placements, error) {
	a.onAt(at)
	return domain.Placements{}, nil
}

// ---------------------------------------------------------------- metrics and events

type recMetrics struct {
	mu     sync.Mutex
	elig   map[string]int
	events int
}

func (m *recMetrics) Eligibility(module string, shown bool, reason string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.elig == nil {
		m.elig = map[string]int{}
	}
	m.elig[fmt.Sprintf("%s|%v|%s", module, shown, reason)]++
}
func (m *recMetrics) Mismatch(string)                            {}
func (m *recMetrics) ComposeSeconds(string, float64)             {}
func (m *recMetrics) SignalsAge(float64)                         {}
func (m *recMetrics) ModuleEvent(string, string, string, string) { m.events++ }

func TestEligibilityMetricsAreLowCardinalityAndEventsRejectUnknownModules(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	m := &recMetrics{}
	r.svc.d.Metrics = m
	r.compose(t, guest("v", "w"))
	for k := range m.elig {
		parts := strings.Split(k, "|")
		if !domain.IsModule(parts[0]) && !strings.HasPrefix(parts[0], "placement:") {
			t.Errorf("metric label %q is not a registry module", parts[0])
		}
	}
	if err := r.svc.RecordModuleEvent(context.Background(), domain.HomeModuleEvent{Module: "deals", Event: "impression"}); err != nil || m.events != 1 {
		t.Fatalf("a registry module is counted: %v", err)
	}
	for _, bad := range []domain.HomeModuleEvent{{Module: "AT0000123", Event: "click"}, {Module: "deals", Event: "hover"}, {Module: "deals", Event: "click", Position: 99}} {
		if err := r.svc.RecordModuleEvent(context.Background(), bad); err == nil {
			t.Errorf("%+v must be rejected", bad)
		}
	}
}

func TestGeneratedBlocksCarryNoPersonalFieldWhenShared(t *testing.T) {
	r := newHomeRig(t, 100, 10, "dev")
	h := r.compose(t, HomeRequest{Caller: &domain.Caller{Subject: "cust-1", BearerToken: "t"}, Locale: "en"})
	for _, b := range h.Blocks {
		if b.Scope == domain.ScopeShared && (b.Module == domain.ModContinue || b.Module == domain.ModRecent) {
			t.Errorf("%s is personal but marked shared", b.Module)
		}
	}
	// the personal slot exists for the browser, marked visitor-scoped, with a key and no English
	for _, b := range h.Blocks {
		if b.Type == domain.BlockRecentlyViewed && (b.Scope != domain.ScopeVisitor || b.TitleKey != domain.KeyRecentTitle || b.Title != "") {
			t.Errorf("recently viewed: %+v", b)
		}
	}
}

// ---------------------------------------------------------------- personalisation hooks (S425) and copy (S426)

type fakeWishlist struct{ atins []string }

func (f fakeWishlist) Saved(context.Context, string) ([]string, error) { return f.atins, nil }

func TestGuestsInOneRegionAndLanguageWithoutAVisitorIdGetTheSamePage(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	a := r.compose(t, HomeRequest{Locale: "en"})
	b := r.compose(t, HomeRequest{Locale: "en"})
	if fmt.Sprint(productIDs(a)) != fmt.Sprint(productIDs(b)) || fmt.Sprint(homeBlockTypes(a)) != fmt.Sprint(homeBlockTypes(b)) {
		t.Fatal("guests with no history see only the shared default page")
	}
	for _, blk := range a.Blocks {
		if blk.Scope == domain.ScopeVisitor && blk.Rail != nil {
			t.Errorf("a guest page carries no personal rail: %s", blk.ID)
		}
	}
}

func TestWishlistHookLiftsSavedProductsWithoutForcingThemIn(t *testing.T) {
	r := newHomeRig(t, 400, 40, "dev")
	saved := make([]string, 0, 60)
	for i := 0; i < 60; i++ {
		saved = append(saved, fmt.Sprintf("AT%08d", i))
	}
	signed := &domain.Caller{Subject: "cust-9", BearerToken: "t"}
	count := func(withHook bool) int {
		n := 0
		for v := 0; v < 30; v++ {
			r.svc.d.Wishlist = nil
			if withHook {
				r.svc.d.Wishlist = fakeWishlist{atins: saved}
			}
			h := r.compose(t, HomeRequest{Caller: signed, Window: fmt.Sprintf("w%d", v), Locale: "en"})
			for _, id := range productIDs(h) {
				if containsStr(saved, id) {
					n++
				}
			}
		}
		return n
	}
	if with, without := count(true), count(false); with < without {
		t.Errorf("saved products should not rank lower: %d with the hook, %d without", with, without)
	}
}

func TestNoGeneratedBlockFieldIsAnEnglishLiteral(t *testing.T) {
	fset := token.NewFileSet()
	banned := map[string]bool{"Title": true, "Sub": true, "Body": true, "CTA": true, "Label": true}
	for _, file := range []string{"site_dynamic.go", "site_dynamic_build.go", "site_nav.go", "home_source.go"} {
		f, err := parser.ParseFile(fset, file, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		ast.Inspect(f, func(n ast.Node) bool {
			kv, ok := n.(*ast.KeyValueExpr)
			if !ok {
				return true
			}
			key, ok := kv.Key.(*ast.Ident)
			lit, isLit := kv.Value.(*ast.BasicLit)
			if ok && isLit && banned[key.Name] && lit.Kind == token.STRING && lit.Value != `""` {
				t.Errorf("%s: %s is set to the literal %s; home copy goes out as keys (contracts/i18n/home-copy.json)", fset.Position(kv.Pos()), key.Name, lit.Value)
			}
			return true
		})
	}
}

func TestCopyFileCoversEveryKeyInFiveLocalesWithDraftsMarked(t *testing.T) {
	raw, err := os.ReadFile("../../../../../contracts/i18n/home-copy.json")
	if err != nil {
		t.Skip("contracts not available: ", err)
	}
	var f struct {
		Keys map[string]map[string]any `json:"keys"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	for _, k := range domain.HomeCopyKeys {
		entry := f.Keys[k]
		review, _ := entry["needsReview"].([]any)
		if len(review) != 4 {
			t.Errorf("%s: fr, es, ar and yo are machine drafts and must be listed in needsReview (got %v)", k, review)
		}
	}
}

func TestNoLiteralDepartmentListRemainsInTheGateway(t *testing.T) {
	stale := []string{`"Event & party"`, `"Home & Art"`, `Value: "Books"`, `Value: "Health"`}
	err := filepath.WalkDir("..", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return err
		}
		raw, rerr := os.ReadFile(path)
		if rerr != nil {
			return rerr
		}
		for _, s := range stale {
			if strings.Contains(string(raw), s) {
				t.Errorf("%s still holds a literal department list entry %s; departments come from the live taxonomy", path, s)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestShadowModeCountsDecisionsAndMismatches(t *testing.T) {
	r := newHomeRig(t, 100, 10, "prod")
	shadow := domain.DynamicShadow
	r.home.settings.Dynamic = &shadow
	m := &recMetrics{}
	r.svc.d.Metrics = m
	if h := r.compose(t, guest("v", "w")); h.Version != 0 {
		t.Fatal("shadow serves the legacy page")
	}
	if len(m.elig) == 0 {
		t.Error("shadow mode must count home_module_eligibility so the rollout can be judged before switching on")
	}
}

func TestHeroSlideWithoutARealPhotoIsNeverShownAsAPicture(t *testing.T) {
	now := homeNow
	slides := []domain.HeroSlide{
		{ID: "a", Title: "No image", ImageURL: nil},
		{ID: "b", Title: "Icon", ImageURL: strp("https://cdn.example/icons/box.svg")},
		{ID: "c", Title: "Placeholder", ImageURL: strp("https://cdn.example/placeholder.png")},
		{ID: "d", Title: "Real", ImageURL: strp("https://cdn.example/hero.jpg")},
		{ID: "e", Title: "Expired", ImageURL: strp("https://cdn.example/old.jpg"), EndsAt: strp(now.Add(-time.Hour).Format(time.RFC3339))},
	}
	got := heroSlides(slides, now)
	if len(got) != 1 || got[0].ID != "d" {
		t.Fatalf("only a live slide with a real photo is a picture: %+v", got)
	}
	if len(heroSlides(slides[:3], now)) != 0 {
		t.Fatal("with none left the brand hero (text only) takes over, see TestZeroProducts")
	}
}
