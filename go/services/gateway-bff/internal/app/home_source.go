package app

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The signals snapshot: eligible-only counts (live, RESOLVED tax decision, in stock, real photo) read once and shared by
// the home, the nav, the footer, the sitemap and the community routes, so they cannot disagree (S416, S419).

type snapDept struct {
	Name    string
	Key     string
	Count   int
	Sellers int
	Image   *string
}

type snapComm struct {
	Key     string
	Raw     []string // the product attribute values that resolve to this key
	Count   int
	Sellers int
	Image   *string
}

type homeSnapshot struct {
	At          time.Time
	Total       int
	Sellers     int
	Departments []snapDept // biggest first
	Communities []snapComm // biggest first; every community, visible or not (the thresholds are applied by the engine)
	Brand       int        // eligible products of AfroTransact Retail (the first-party catalogue)
	Providers   int        // distinct service providers in the market
	Listings    []port.UpBkListing
	Fixture     bool
}

// poolSpec is one module's candidate pool request.
type poolSpec struct {
	Module     string
	Department string
	Sort       string
	Limit      int
}

// homeSource is where the home gets its facts: the live services, or a replayed fixture catalogue (staff preview).
type homeSource interface {
	snapshot(ctx context.Context, st domain.HomeSettings, res domain.CommunityResolver) (homeSnapshot, error)
	pool(ctx context.Context, spec poolSpec) ([]domain.ProductSummary, error)
	nearServices(ctx context.Context, zip string) ([]port.UpBkListing, error)
}

const (
	maxFacetUnits   = 20
	maxServiceCards = 200
)

// liveSource reads search (eligibleOnly), the product rails' resolver and bookings.
type liveSource struct {
	s *siteServiceImpl
}

var _ homeSource = (*liveSource)(nil)

func (l *liveSource) snapshot(ctx context.Context, st domain.HomeSettings, res domain.CommunityResolver) (homeSnapshot, error) {
	s := l.s
	now := s.d.Clock.now()
	snap := homeSnapshot{At: now}
	if s.d.Search != nil {
		root, err := s.d.Search.Search(ctx, port.SearchParams{Market: s.d.Market, Size: 1, EligibleOnly: true, NoTrack: true})
		if err != nil {
			return snap, fmt.Errorf("signals: %w", err)
		}
		snap.Total, snap.Sellers = int(root.Total), int(root.DistinctSellers)
		if snap.Total > 0 {
			snap.Departments, snap.Communities = l.facets(ctx, root, st, res)
			if r, err := s.d.Search.Search(ctx, port.SearchParams{Market: s.d.Market, SellerKind: domain.SellerKindPlatform, Size: 1, EligibleOnly: true, NoTrack: true}); err == nil {
				snap.Brand = int(r.Total)
			}
		}
	}
	if s.d.Services != nil {
		list, err := s.d.Services.Search(ctx, "", "", "")
		if err != nil {
			s.d.Log.InfoContext(ctx, "services unavailable for the home signals", "error", err)
		} else {
			if len(list) > maxServiceCards {
				list = list[:maxServiceCards]
			}
			snap.Listings = list
			snap.Providers = providerCount(list)
		}
	}
	return snap, nil
}

func providerCount(list []port.UpBkListing) int {
	seen := map[string]bool{}
	for _, x := range list {
		seen[firstNonEmpty(x.OrgID, x.ProviderName, x.ServiceID)] = true
	}
	return len(seen)
}

// facets reads the per-department and per-community counts, seller counts and best photo, each as one eligible-only query.
func (l *liveSource) facets(ctx context.Context, root port.SearchResult, st domain.HomeSettings, res domain.CommunityResolver) ([]snapDept, []snapComm) {
	s := l.s
	depts := make([]snapDept, 0, maxFacetUnits)
	for _, b := range root.Facets["department"] {
		if b.Count > 0 && strings.TrimSpace(b.Value) != "" && len(depts) < maxFacetUnits {
			depts = append(depts, snapDept{Name: b.Value, Key: domain.DepartmentKey(b.Value), Count: int(b.Count)})
		}
	}
	// Merge the raw community values by canonical key.
	byKey := map[string]*snapComm{}
	var order []string
	buckets := root.Facets["communities"]
	if len(buckets) == 0 {
		buckets = root.Facets["community"]
	}
	for _, b := range buckets {
		key, _ := res.Resolve(b.Value)
		if key == "" || b.Count <= 0 {
			continue
		}
		c := byKey[key]
		if c == nil {
			c = &snapComm{Key: key}
			byKey[key] = c
			order = append(order, key)
		}
		c.Raw = append(c.Raw, b.Value)
		c.Count += int(b.Count)
	}
	comms := make([]snapComm, 0, len(order))
	for _, k := range order {
		comms = append(comms, *byKey[k])
	}
	need := st.Modules[domain.ModCommunities].UnitMinProduct
	var wg sync.WaitGroup
	for i := range depts {
		wg.Go(func() {
			r, err := s.d.Search.Search(ctx, port.SearchParams{Market: s.d.Market, Department: depts[i].Name, Size: 1, Sort: domain.SortRating, EligibleOnly: true, NoTrack: true})
			if err != nil {
				return
			}
			depts[i].Sellers = int(r.DistinctSellers)
			depts[i].Image = firstPhoto(r.Hits)
		})
	}
	for i := range comms {
		if comms[i].Count < need { // below the product threshold: its seller count and photo are never needed
			continue
		}
		wg.Go(func() {
			r, err := s.d.Search.Search(ctx, port.SearchParams{Market: s.d.Market, Community: strings.Join(comms[i].Raw, ","), Size: 1, Sort: domain.SortRating, EligibleOnly: true, NoTrack: true})
			if err != nil {
				return
			}
			comms[i].Sellers = int(r.DistinctSellers)
			comms[i].Image = firstPhoto(r.Hits)
		})
	}
	wg.Wait()
	sort.SliceStable(depts, func(i, j int) bool { return depts[i].Count > depts[j].Count })
	sort.SliceStable(comms, func(i, j int) bool { return comms[i].Count > comms[j].Count })
	return depts, comms
}

// firstPhoto is the photo of the best hit that has a real one (hits are already eligible; the check is the same rule).
func firstPhoto(hits []port.SearchHit) *string {
	for _, h := range hits {
		if u := h.ImageURL; realPhoto(&u) {
			return &u
		}
	}
	return nil
}

func (l *liveSource) pool(ctx context.Context, spec poolSpec) ([]domain.ProductSummary, error) {
	src := domain.RailSource{Kind: "search", Sort: spec.Sort, Department: spec.Department, Limit: spec.Limit}
	if spec.Department != "" {
		src.Kind = "department-bestsellers"
	}
	// A shared scope (the day only): candidate pools are cached and shared, so they never carry a shopper's place or id.
	return l.s.railItems(ctx, src, shopperScope{seed: l.s.d.Clock.now().UTC().Format("2006-01-02")})
}

func (l *liveSource) nearServices(ctx context.Context, zip string) ([]port.UpBkListing, error) {
	if l.s.d.Services == nil || !zip5Home(zip) {
		return nil, nil
	}
	return l.s.d.Services.Search(ctx, "", "", zip)
}

// ------------------------------------------------------------------ fixtures (staff "simulate a catalogue")

// fixtureSource replays a catalogue of n eligible products over four departments and three communities, so staff see the
// thin states before launch and tests cover 0 / 10 / 100 / 10,000. Nothing is read from any service.
type fixtureSource struct {
	n       int
	now     time.Time
	sellers int
}

var _ homeSource = (*fixtureSource)(nil)

var fixtureDepartments = []struct {
	name  string
	share int
}{{"Groceries & Food", 50}, {"Beauty & Personal Care", 25}, {"Fashion & Accessories", 15}, {"Home & Household", 10}}

func newFixtureSource(n int, now time.Time) *fixtureSource {
	return &fixtureSource{n: n, now: now, sellers: max(0, min(n, 1+n/10))}
}

func fixturePhoto(i int) *string {
	u := fmt.Sprintf("https://fixtures.afrotransact.invalid/p/%d.jpg", i)
	return &u
}

func (f *fixtureSource) snapshot(_ context.Context, _ domain.HomeSettings, res domain.CommunityResolver) (homeSnapshot, error) {
	snap := homeSnapshot{At: f.now, Total: f.n, Sellers: f.sellers, Fixture: true, Brand: f.n / 10}
	if f.n == 0 {
		return snap, nil
	}
	for i, d := range fixtureDepartments {
		c := f.n * d.share / 100
		if c == 0 {
			continue
		}
		snap.Departments = append(snap.Departments, snapDept{Name: d.name, Key: domain.DepartmentKey(d.name), Count: c, Sellers: max(1, min(f.sellers, 1+c/10)), Image: fixturePhoto(i)})
	}
	for i, name := range []string{"Nigerian", "Ghanaian", "Ethiopian"} {
		c := f.n / (3 + 4*i)
		key, _ := res.Resolve(name)
		if c > 0 {
			snap.Communities = append(snap.Communities, snapComm{Key: key, Raw: []string{name}, Count: c, Sellers: max(1, min(f.sellers, 1+c/8)), Image: fixturePhoto(100 + i)})
		}
	}
	if f.n >= 10 { // providers exist once there is a marketplace at all
		for i := range 4 {
			snap.Listings = append(snap.Listings, port.UpBkListing{ServiceID: fmt.Sprintf("svc%d", i), OrgID: fmt.Sprintf("org%d", i), Name: "Sample service", ProviderName: fmt.Sprintf("Provider %d", i), Currency: "USD", PriceFromMinor: 4500, Location: "Houston, TX"})
		}
		snap.Providers = 4
	}
	return snap, nil
}

func (f *fixtureSource) pool(_ context.Context, spec poolSpec) ([]domain.ProductSummary, error) {
	limit := min(max(spec.Limit, 1), 24)
	total := f.n
	if spec.Department != "" {
		for _, d := range fixtureDepartments {
			if d.name == spec.Department {
				total = f.n * d.share / 100
			}
		}
	}
	n := min(limit, total)
	tag := "ALL"
	if spec.Department != "" {
		tag = strings.ToUpper(strings.ReplaceAll(domain.DepartmentKey(spec.Department), "-", ""))[:4]
	}
	out := make([]domain.ProductSummary, 0, n)
	for i := range n {
		seller := fmt.Sprintf("seller%d", i%max(1, f.sellers))
		list := int64(1000)
		p := domain.ProductSummary{ATIN: fmt.Sprintf("ATFX%s%05d", tag, i), Title: fmt.Sprintf("Sample product %d", i),
			Department: spec.Department, ImageURL: fixturePhoto(i), InStock: true, AvailableQty: 5 + i*3, PriceMinor: 900, Currency: "USD",
			SellerName: seller, SellerID: seller, SellerKind: domain.SellerKindSeller, Fulfilment: "SELLER", Rating: 3.5 + float64(i%4)*0.4, ReviewCount: 2 + i%9}
		if i%3 == 0 {
			p.ListPriceMinor = &list
		}
		out = append(out, p)
	}
	return out, nil
}

func (f *fixtureSource) nearServices(context.Context, string) ([]port.UpBkListing, error) {
	return nil, nil
}
