package app

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type fakeProviders struct {
	port.CustomerBookingsClient
	p      port.UpBkProvider
	err    error
	rating port.UpBkRating
	rerr   error
	asked  []string
}

func (f *fakeProviders) Provider(_ context.Context, org string) (port.UpBkProvider, error) {
	f.asked = append(f.asked, org)
	return f.p, f.err
}

func (f *fakeProviders) ProviderRating(context.Context, string) (port.UpBkRating, error) {
	return f.rating, f.rerr
}

func day(d string, ranges ...[2]string) port.UpBkDayHours {
	h := port.UpBkDayHours{Day: d}
	for _, r := range ranges {
		h.Ranges = append(h.Ranges, struct {
			Start string `json:"start"`
			End   string `json:"end"`
		}{r[0], r[1]})
	}
	return h
}

func providerSvc(t *testing.T, bk *fakeProviders, cfg domain.StoreConfig) StorefrontService {
	t.Helper()
	raw, _ := json.Marshal(cfg)
	up := port.UpStorefront{Kind: "PROVIDER", OrgID: "org_prov", Slug: "ades-braids", Name: "Ade's Braids", Config: raw, OrgCreatedAt: time.Date(2025, 3, 1, 0, 0, 0, 0, time.UTC)}
	clock := Clock(func() time.Time { return time.Date(2026, 11, 14, 12, 0, 0, 0, time.UTC) })
	pol := domain.StorePolicy{MediaPrefixes: []string{"https://media.example.com/"}}
	return NewStorefrontService(fakeStorefronts{up: up, list: []port.UpPublishedStorefront{{Kind: "PROVIDER", OrgID: "org_prov", Slug: "ades-braids"}}},
		&fakeSearch{hits: []domain.ProductSummary{sfProduct("NOPE", 0, 0, true, "https://m/a.jpg")}}, nil, pol, clock, WithProviders(bk))
}

func goodProvider() port.UpBkProvider {
	sv := func(id, name string, modes ...string) port.UpBkService {
		return port.UpBkService{ID: id, Name: name, Description: "Neat work", Category: "hair", DurationMinutes: 90, PriceMinor: 12000, DepositMinor: 3000, Currency: "usd", Modes: modes}
	}
	bad := sv("s4", "Hostile <b>name</b>", "IN_PERSON")
	bad.ImageURL = "https://evil.example.com/x.jpg"
	good := sv("s5", "Silk press", "IN_PERSON")
	good.ImageURL = "https://media.example.com/product-image/x.jpg"
	return port.UpBkProvider{OrgID: "org_prov", DisplayName: "Ade's Braids", TimeZone: "America/Chicago", Location: "Round Rock, TX", InstantBooking: true,
		Policy:      port.UpBkPolicy{Label: "Moderate", Text: "Free cancellation up to 24 hours before."},
		Services:    []port.UpBkService{sv("s1", "Box braids", "IN_PERSON"), sv("s2", "Home visit", "AT_HOME", "IN_PERSON"), sv("s3", "Video consult", "VIRTUAL"), bad, good},
		Team:        []port.UpBkMember{{ID: "m1", DisplayName: "Ade"}},
		WeeklyHours: []port.UpBkDayHours{day("MONDAY", [2]string{"09:00", "17:00"}), day("TUESDAY"), day("SATURDAY", [2]string{"10:00", "14:00"})},
		ServiceArea: &port.UpBkServiceArea{BaseCity: "Round Rock", BaseState: "TX", RadiusMiles: 25, ServedCities: []string{"Hutto"}, ExtraAreas: []string{"Taylor, TX"}}}
}

func TestProviderPageIsBuiltOnlyFromRealBookingData(t *testing.T) {
	bk := &fakeProviders{p: goodProvider(), rating: port.UpBkRating{Rating: 4.66, RatingCount: 9}}
	got, err := providerSvc(t, bk, domain.DefaultStoreConfigFor(domain.StoreKindProvider)).Get(context.Background(), "ades-braids")
	if err != nil || got.Kind != "PROVIDER" || got.Provider == nil {
		t.Fatalf("got %+v %v", got, err)
	}
	if len(bk.asked) == 0 || bk.asked[0] != "org_prov" {
		t.Fatalf("bookings must be asked for the page's own organisation, asked %v", bk.asked)
	}
	var ids []string
	for _, s := range got.Provider.Services {
		ids = append(ids, s.ID)
		if s.ID == "s4" && (s.Name != "" || s.ImageURL != nil) {
			t.Errorf("markup in a name and an image off the media host must be dropped: %+v", s)
		}
		if s.ID == "s5" && s.ImageURL == nil {
			t.Errorf("an image on the media host is kept")
		}
	}
	if len(ids) != 4 || ids[0] != "s1" || ids[1] != "s2" || ids[2] != "s4" || ids[3] != "s5" {
		t.Fatalf("a virtual service must never be listed, services = %v", ids)
	}
	if len(got.Provider.Hours) != 7 || got.Provider.Hours[0].Day != "MONDAY" || len(got.Provider.Hours[0].Ranges) != 1 || len(got.Provider.Hours[1].Ranges) != 0 || got.Provider.Hours[6].Day != "SUNDAY" {
		t.Fatalf("hours must be Monday first, closed days empty: %+v", got.Provider.Hours)
	}
	if a := got.Provider.Area; a == nil || a.BaseCity != "Round Rock" || a.RadiusMiles != 25 || len(a.ExtraAreas) != 1 {
		t.Fatalf("area = %+v", a)
	}
	if got.Reviews == nil || got.Reviews.Count != 9 || got.Reviews.Average != 4.7 {
		t.Fatalf("rating comes from bookings' aggregate: %+v", got.Reviews)
	}
	if len(got.Products.Hits) != 0 || len(got.Featured) != 0 || !got.Indexable {
		t.Fatalf("a provider has no products and is indexable with services: %+v", got)
	}
	if got.Config.Sections[2].ID != "services" {
		t.Fatalf("provider sections expected: %+v", got.Config.Sections)
	}
}

func TestProviderWithoutHoursOrReviewsShowsNeither(t *testing.T) {
	p := goodProvider()
	p.WeeklyHours = []port.UpBkDayHours{day("MONDAY"), day("TUESDAY")}
	p.ServiceArea = nil
	got, _ := providerSvc(t, &fakeProviders{p: p, rating: port.UpBkRating{}}, domain.DefaultStoreConfigFor(domain.StoreKindProvider)).Get(context.Background(), "ades-braids")
	if len(got.Provider.Hours) != 0 || got.Provider.Area != nil || got.Reviews != nil {
		t.Fatalf("no hours set must not read as closed all week, no area, no zero-review row: %+v %+v", got.Provider, got.Reviews)
	}
}

func TestProviderPageSurvivesBookingsBeingDown(t *testing.T) {
	got, err := providerSvc(t, &fakeProviders{err: errors.New("down"), rerr: errors.New("down")}, domain.DefaultStoreConfigFor(domain.StoreKindProvider)).Get(context.Background(), "ades-braids")
	if err != nil || got.Provider != nil || got.Reviews != nil || got.Indexable {
		t.Fatalf("got %+v %v", got, err)
	}
}

func TestProviderProductsAreEmptyAndSitemapNeedsAService(t *testing.T) {
	svc := providerSvc(t, &fakeProviders{p: goodProvider()}, domain.DefaultStoreConfigFor(domain.StoreKindProvider))
	pr, err := svc.Products(context.Background(), "ades-braids", domain.StorefrontQuery{})
	if err != nil || len(pr.Hits) != 0 {
		t.Fatalf("a provider has no products: %+v %v", pr, err)
	}
	l, _ := svc.List(context.Background())
	if len(l.Items) != 1 || l.Items[0].Kind != "PROVIDER" {
		t.Fatalf("provider with a service is listed: %+v", l)
	}
	empty := providerSvc(t, &fakeProviders{p: port.UpBkProvider{}}, domain.DefaultStoreConfigFor(domain.StoreKindProvider))
	l, _ = empty.List(context.Background())
	if len(l.Items) != 0 {
		t.Fatalf("a provider with no service is not listed: %+v", l)
	}
}

func TestSellerSectionsAreRefusedOnAProviderConfig(t *testing.T) {
	cfg := domain.DefaultStoreConfig() // seller sections
	_, errs := domain.ValidateStoreConfig(cfg, domain.StorePolicy{Kind: domain.StoreKindProvider})
	if errs["sections"] == "" {
		t.Fatal("seller-only sections (featured, categories, policies) must be refused on a provider page")
	}
	prov := domain.DefaultStoreConfigFor(domain.StoreKindProvider)
	if _, errs := domain.ValidateStoreConfig(prov, domain.StorePolicy{Kind: domain.StoreKindProvider}); len(errs) != 0 {
		t.Fatalf("the provider default must be valid: %v", errs)
	}
	if _, errs := domain.ValidateStoreConfig(prov, domain.StorePolicy{}); errs["sections"] == "" {
		t.Fatal("provider-only sections must be refused on a seller page")
	}
}
