package app

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type fakeStorefronts struct {
	up   port.UpStorefront
	err  error
	list []port.UpPublishedStorefront
}

func (f fakeStorefronts) PublishedStorefronts(context.Context) ([]port.UpPublishedStorefront, error) {
	return f.list, f.err
}

func (f fakeStorefronts) Storefront(context.Context, string) (port.UpStorefront, error) {
	return f.up, f.err
}

// fakeSearch answers every search with the same hits and records the last query.
type fakeSearch struct {
	hits []domain.ProductSummary
	last domain.SearchQuery
}

func (f *fakeSearch) Search(_ context.Context, _ *domain.Caller, q domain.SearchQuery) (domain.SearchPage, error) {
	if len(q.ATINs) == 0 {
		f.last = q
	}
	hits := f.hits
	if len(q.ATINs) > 0 { // like the search service: only these products
		hits = nil
		for _, h := range f.hits {
			for _, a := range q.ATINs {
				if h.ATIN == a {
					hits = append(hits, h)
				}
			}
		}
	}
	return domain.SearchPage{Hits: hits, Total: int64(len(hits)), Size: max(q.Size, 1), PageCount: 1,
		Facets: domain.Facets{Department: []domain.FacetBucket{{Value: "Footwear", Count: 2}, {Value: "Empty", Count: 0}}}}, nil
}

func (f *fakeSearch) Suggest(context.Context, *domain.Caller, string) (domain.Suggestions, error) {
	return domain.Suggestions{}, nil
}

type fakePickup struct {
	port.DeliveryFactsClient
	places []port.PickupLocation
}

func (f fakePickup) PickupLocations(context.Context, string, string) ([]port.PickupLocation, error) {
	return f.places, nil
}

func sfProduct(atin string, rating float64, reviews int, inStock bool, img string) domain.ProductSummary {
	var u *string
	if img != "" {
		u = &img
	}
	return domain.ProductSummary{ATIN: atin, Title: atin, Rating: rating, ReviewCount: reviews, InStock: inStock, ImageURL: u, PriceMinor: 1000, OfferID: "o-" + atin}
}

func storefrontSvc(t *testing.T, cfg domain.StoreConfig, hits []domain.ProductSummary, places []port.PickupLocation) (StorefrontService, *fakeSearch) {
	t.Helper()
	raw, _ := json.Marshal(cfg)
	search := &fakeSearch{hits: hits}
	up := port.UpStorefront{OrgID: "org_1", Slug: "laserfeet", Name: "Laserfeet", Description: "Foot care", Config: raw, ContactEmail: "hi@example.com",
		OrgCreatedAt: time.Date(2025, 3, 1, 0, 0, 0, 0, time.UTC)}
	clock := Clock(func() time.Time { return time.Date(2026, 11, 14, 12, 0, 0, 0, time.UTC) })
	return NewStorefrontService(fakeStorefronts{up: up}, search, fakePickup{places: places}, domain.StorePolicy{}, clock), search
}

func TestOnlySellableProductsAppear(t *testing.T) {
	cfg := domain.DefaultStoreConfig()
	hits := []domain.ProductSummary{
		sfProduct("A", 0, 0, true, "https://media.example.com/a.jpg"),
		sfProduct("B", 0, 0, false, "https://media.example.com/b.jpg"), // out of stock
		sfProduct("C", 0, 0, true, ""),                                 // no photo
		sfProduct("D", 0, 0, true, "https://example.com/placeholder.png"),
		sfProduct("E", 0, 0, true, "data:image/svg+xml;base64,AAA"),
	}
	svc, search := storefrontSvc(t, cfg, hits, nil)
	got, err := svc.Get(context.Background(), "laserfeet")
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Products.Hits) != 1 || got.Products.Hits[0].ATIN != "A" {
		t.Fatalf("grid = %+v", got.Products.Hits)
	}
	if !search.last.EligibleOnly || search.last.SellerID != "org_1" {
		t.Fatalf("search must be the seller's and eligible-only: %+v", search.last)
	}
}

func TestNoReviewsMeansNoSummary(t *testing.T) {
	svc, _ := storefrontSvc(t, domain.DefaultStoreConfig(), []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}, nil)
	got, _ := svc.Get(context.Background(), "laserfeet")
	if got.Reviews != nil {
		t.Fatalf("reviews must be null without reviews, got %+v", got.Reviews)
	}
}

type fakeRatings struct {
	r   port.UpRating
	err error
}

func (f fakeRatings) SellerRating(context.Context, string) (port.UpRating, error) { return f.r, f.err }

func svcWithRatings(t *testing.T, r fakeRatings) StorefrontService {
	t.Helper()
	raw, _ := json.Marshal(domain.DefaultStoreConfig())
	up := port.UpStorefront{OrgID: "org_1", Slug: "laserfeet", Name: "Laserfeet", Config: raw}
	clock := Clock(func() time.Time { return time.Date(2026, 11, 14, 12, 0, 0, 0, time.UTC) })
	return NewStorefrontService(fakeStorefronts{up: up}, &fakeSearch{hits: []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}}, nil, domain.StorePolicy{}, clock, WithSellerRatings(r))
}

// The rating is catalog's aggregate over real published reviews; nothing is read from the product cards.
func TestSellerRatingIsTheBackendAggregate(t *testing.T) {
	got, _ := svcWithRatings(t, fakeRatings{r: port.UpRating{Average: 4.75, Count: 12}}).Get(context.Background(), "laserfeet")
	if got.Reviews == nil || got.Reviews.Count != 12 || got.Reviews.Average != 4.8 {
		t.Fatalf("reviews = %+v", got.Reviews)
	}
}

func TestNoRatingWithoutRealReviewsOrWhenTheAggregateIsUnavailable(t *testing.T) {
	for name, r := range map[string]fakeRatings{"zero": {r: port.UpRating{Average: 0, Count: 0}}, "count only": {r: port.UpRating{Average: 0, Count: 3}},
		"error": {err: errors.New("catalog down")}} {
		got, _ := svcWithRatings(t, r).Get(context.Background(), "laserfeet")
		if got.Reviews != nil {
			t.Errorf("%s: reviews must be null, got %+v", name, got.Reviews)
		}
	}
}

// A featured pick is looked up by ATIN, not found by scanning the first pages: it survives however big the catalogue is.
func TestFeaturedPickIsNotLostBeyondTheFirstPages(t *testing.T) {
	cfg := domain.DefaultStoreConfig()
	cfg.Featured = []string{"Z199"}
	hits := []domain.ProductSummary{}
	for i := range 200 {
		hits = append(hits, sfProduct("P"+itoa(i), 0, 0, true, "https://m/p.jpg"))
	}
	hits = append(hits, sfProduct("Z199", 0, 0, true, "https://m/z.jpg"))
	svc, _ := storefrontSvc(t, cfg, hits, nil)
	got, _ := svc.Get(context.Background(), "laserfeet")
	if len(got.Featured) != 1 || got.Featured[0].ATIN != "Z199" {
		t.Fatalf("featured = %+v", got.Featured)
	}
}

func TestOnSaleFilterReachesSearchAndKeepsInStockOnly(t *testing.T) {
	svc, search := storefrontSvc(t, domain.DefaultStoreConfig(), []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}, nil)
	if _, err := svc.Products(context.Background(), "laserfeet", domain.StorefrontQuery{OnSale: true}); err != nil {
		t.Fatal(err)
	}
	if !search.last.OnSale || !search.last.EligibleOnly || search.last.SellerID != "org_1" {
		t.Fatalf("search must be on sale, eligible-only and the seller's: %+v", search.last)
	}
	if _, err := svc.Products(context.Background(), "laserfeet", domain.StorefrontQuery{}); err != nil {
		t.Fatal(err)
	}
	if search.last.OnSale {
		t.Fatal("the default grid must not filter on sale")
	}
}

func TestFeaturedOnlyKeepsSellableProductsInPickOrder(t *testing.T) {
	cfg := domain.DefaultStoreConfig()
	cfg.Featured = []string{"B", "GONE", "A"}
	hits := []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg"), sfProduct("B", 0, 0, true, "https://m/b.jpg")}
	svc, _ := storefrontSvc(t, cfg, hits, nil)
	got, _ := svc.Get(context.Background(), "laserfeet")
	if len(got.Featured) != 2 || got.Featured[0].ATIN != "B" || got.Featured[1].ATIN != "A" {
		t.Fatalf("featured = %+v", got.Featured)
	}
}

func TestContactAndPickupOnlyWhenTheSellerShowsThem(t *testing.T) {
	place := port.PickupLocation{Kind: "SELLER", OwnerOrg: "org_1", Active: true}
	place.Address.PostalCode = "77002-1234"
	hits := []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}

	off, _ := storefrontSvc(t, domain.DefaultStoreConfig(), hits, []port.PickupLocation{place})
	g, _ := off.Get(context.Background(), "laserfeet")
	if g.ContactEmail != nil || g.Pickup != nil {
		t.Fatalf("contact/pickup leaked while off: %+v %+v", g.ContactEmail, g.Pickup)
	}

	cfg := domain.DefaultStoreConfig()
	cfg.Contact.ShowEmail, cfg.Pickup.Show = true, true
	cfg.Contact.Phone = "713 555 1234"
	cfg.Social = []domain.StoreSocialLink{{Network: "instagram", URL: "https://instagram.com/laserfeet"}, {Network: "website", URL: "https://laserfeet.example.com/"}}
	on, _ := storefrontSvc(t, cfg, hits, []port.PickupLocation{place})
	g, _ = on.Get(context.Background(), "laserfeet")
	if g.Pickup == nil || g.Pickup.Zip != "77002" {
		t.Fatalf("pickup missing: %+v", g.Pickup)
	}
	// The login email, the phone number and (until earned) website links are never on the public page.
	if g.ContactEmail != nil || g.Config.Contact.ShowEmail || g.Config.Contact.Phone != "" {
		t.Fatalf("private contact leaked: %+v %+v", g.ContactEmail, g.Config.Contact)
	}
	if len(g.Config.Social) != 1 || g.Config.Social[0].Network != "instagram" {
		t.Fatalf("website link shown before it is earned: %+v", g.Config.Social)
	}

	// shown by the seller but not switched on (or not the seller's own place): never claimed
	inactive := place
	inactive.Active = false
	none, _ := storefrontSvc(t, cfg, hits, []port.PickupLocation{inactive})
	g, _ = none.Get(context.Background(), "laserfeet")
	if g.Pickup != nil {
		t.Fatalf("pickup claimed without an active place: %+v", g.Pickup)
	}
}

func TestNothingAboutCarriersOrAfroTransactDeliveryIsSerialised(t *testing.T) {
	cfg := domain.DefaultStoreConfig()
	cfg.Pickup.Show = true
	place := port.PickupLocation{Kind: "SELLER", OwnerOrg: "org_1", Active: true, Name: "UPS Store #12"}
	place.Address.PostalCode = "77002"
	svc, _ := storefrontSvc(t, cfg, []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}, []port.PickupLocation{place})
	got, _ := svc.Get(context.Background(), "laserfeet")
	b, _ := json.Marshal(got)
	for _, banned := range []string{"UPS", "FedEx", "USPS", "DHL", "carrier", "AfroTransact collects", "AfroTransact delivers"} {
		if strings.Contains(strings.ToLower(string(b)), strings.ToLower(banned)) {
			t.Fatalf("storefront JSON mentions %q: %s", banned, b)
		}
	}
}

func TestUnpublishedOrUnknownIsA404(t *testing.T) {
	clock := Clock(func() time.Time { return time.Now() })
	notFound := &port.UpstreamProblem{Upstream: "seller", Status: http.StatusNotFound}
	svc := NewStorefrontService(fakeStorefronts{err: notFound}, &fakeSearch{}, nil, domain.StorePolicy{}, clock)
	for _, slug := range []string{"laserfeet", "UPPER_case", "../etc", ""} {
		_, err := svc.Get(context.Background(), slug)
		var p *domain.Problem
		if !errors.As(err, &p) || p.Status != http.StatusNotFound {
			t.Errorf("%q: want 404 problem, got %v", slug, err)
		}
	}
}

func TestProductsQueryIsClampedAndPassedThrough(t *testing.T) {
	svc, search := storefrontSvc(t, domain.DefaultStoreConfig(), []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}, nil)
	_, err := svc.Products(context.Background(), "laserfeet", domain.StorefrontQuery{Q: " sandals ", Category: "Footwear", Price: "u10", Sort: "bogus", Page: 0, Size: 9999})
	if err != nil {
		t.Fatal(err)
	}
	q := search.last
	if q.Q != "sandals" || q.Department != "Footwear" || q.Price != "u10" || q.Sort != domain.SortRelevance || q.Page != 0 || q.Size != 48 || !q.EligibleOnly || q.Ads {
		t.Fatalf("query = %+v", q)
	}
}

func TestCategoriesAreTheSellersLiveOnes(t *testing.T) {
	svc, _ := storefrontSvc(t, domain.DefaultStoreConfig(), []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}, nil)
	got, _ := svc.Get(context.Background(), "laserfeet")
	if len(got.Categories) != 1 || got.Categories[0].Name != "Footwear" {
		t.Fatalf("categories = %+v", got.Categories)
	}
}

func TestSellingSinceAndVerifiedAreReal(t *testing.T) {
	svc, _ := storefrontSvc(t, domain.DefaultStoreConfig(), []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}, nil)
	got, _ := svc.Get(context.Background(), "laserfeet")
	if got.SellingSinceYear == nil || *got.SellingSinceYear != 2025 || !got.Verified || !got.Indexable {
		t.Fatalf("got %+v", got)
	}
	empty, _ := storefrontSvc(t, domain.DefaultStoreConfig(), nil, nil)
	g, _ := empty.Get(context.Background(), "laserfeet")
	if g.Indexable {
		t.Fatal("a page with no live products must not be indexable (no sitemap entry)")
	}
}

func TestSitemapListOnlyHasStoresWithLiveProducts(t *testing.T) {
	clock := Clock(func() time.Time { return time.Now() })
	pages := fakeStorefronts{list: []port.UpPublishedStorefront{{OrgID: "org_1", Slug: "laserfeet"}}}
	live := NewStorefrontService(pages, &fakeSearch{hits: []domain.ProductSummary{sfProduct("A", 0, 0, true, "https://m/a.jpg")}}, nil, domain.StorePolicy{}, clock)
	got, err := live.List(context.Background())
	if err != nil || len(got.Items) != 1 || got.Items[0].Slug != "laserfeet" {
		t.Fatalf("live store missing: %+v %v", got, err)
	}
	empty := NewStorefrontService(pages, &fakeSearch{hits: []domain.ProductSummary{sfProduct("B", 0, 0, false, "https://m/b.jpg")}}, nil, domain.StorePolicy{}, clock)
	got, _ = empty.List(context.Background())
	if len(got.Items) != 0 {
		t.Fatalf("a store with nothing sellable must not be listed: %+v", got)
	}
}
