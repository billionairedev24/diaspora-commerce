package app_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/fixture"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/smarty"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

type failing struct{}

func (failing) Name() string { return "google-places" }
func (failing) Suggest(context.Context, domain.AutocompleteQuery) ([]domain.Suggestion, error) {
	return nil, errors.New("quota exceeded")
}
func (failing) Resolve(context.Context, string, string) (domain.Address, error) {
	return domain.Address{}, errors.New("quota exceeded")
}

type brokenValidator struct{}

func (brokenValidator) Name() string { return "smarty" }
func (brokenValidator) Validate(context.Context, domain.Address) (domain.Validation, error) {
	return domain.Validation{}, errors.New("timeout")
}

func svc(providers ...port.Autocompleter) app.AddressService {
	return app.NewAddressService(providers, brokenValidator{}, smarty.NewRules(), quiet)
}

func TestFallsBackToNextProvider(t *testing.T) {
	res, err := svc(failing{}, fixture.New()).Autocomplete(context.Background(),
		domain.AutocompleteQuery{Input: "4521 Main", SessionToken: "session-1", Country: "US"})
	if err != nil {
		t.Fatal(err)
	}
	out := res.Suggestions
	if len(out) == 0 || out[0].MainText != "4521 Main St" || out[0].Provider != "fixture" || res.Session != "session-1" {
		t.Fatalf("unexpected suggestions %+v", res)
	}
}

func TestResolveKeepsTypedHouseNumber(t *testing.T) {
	s := svc(fixture.New())
	res, err := s.Autocomplete(context.Background(), domain.AutocompleteQuery{Input: "4521 Main Houston", Country: "US"})
	sugs := res.Suggestions
	if err != nil || len(sugs) == 0 {
		t.Fatalf("suggest: %v %v", sugs, err)
	}
	a, err := s.Resolve(context.Background(), sugs[0].ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if a.Line1 != "4521 Main St" || a.City != "Houston" || a.State != "TX" || a.PostalCode != "77002" {
		t.Fatalf("resolved %+v", a)
	}
}

func TestValidateFallsBackToRulesWhenProviderFails(t *testing.T) {
	v, err := svc(fixture.New()).Validate(context.Background(),
		domain.Address{Line1: "123 Main St", City: "Houston", State: "tx", PostalCode: "77002"})
	if err != nil {
		t.Fatal(err)
	}
	if v.Status != domain.StatusUnverified || v.Standard.State != "TX" {
		t.Fatalf("got %+v", v)
	}
}

func TestValidateRejectsBadFormat(t *testing.T) {
	_, err := svc(fixture.New()).Validate(context.Background(), domain.Address{Line1: "1", State: "ZZ", PostalCode: "7700"})
	if !errors.Is(err, domain.ErrInvalidAddress) {
		t.Fatalf("expected ErrInvalidAddress, got %v", err)
	}
}

func TestLookupZip(t *testing.T) {
	a, err := svc(fixture.New()).LookupZip(context.Background(), "08820")
	if err != nil {
		t.Fatal(err)
	}
	if a.City != "Edison" || a.State != "NJ" {
		t.Fatalf("got %+v", a)
	}
}

// S139: suggestions from the first character; the service issues a session token when the client has none; ZIP and
// city fields get only their kind; a bias point ranks nearby places first; picking fills every field.
func TestAutocompleteFromTheFirstCharacterWithSessionKindAndBias(t *testing.T) {
	s := svc(fixture.New())
	ctx := context.Background()
	res, err := s.Autocomplete(ctx, domain.AutocompleteQuery{Input: "1"})
	if err != nil || len(res.Suggestions) == 0 {
		t.Fatalf("one character must suggest: %+v %v", res, err)
	}
	if len(res.Session) < 16 {
		t.Fatalf("a session token must be issued: %q", res.Session)
	}
	again, _ := s.Autocomplete(ctx, domain.AutocompleteQuery{Input: "11", SessionToken: res.Session})
	if again.Session != res.Session {
		t.Fatal("the client's session token is kept")
	}

	zips, err := s.Autocomplete(ctx, domain.AutocompleteQuery{Input: "770", Kind: domain.KindPostalCode})
	if err != nil || len(zips.Suggestions) == 0 {
		t.Fatalf("zips: %+v %v", zips, err)
	}
	for _, z := range zips.Suggestions {
		if z.Kind != domain.KindPostalCode || !strings.HasPrefix(z.MainText, "770") {
			t.Fatalf("ZIP field suggestion %+v", z)
		}
	}

	brooklyn := &domain.Point{Lat: 40.6527, Lng: -73.9592}
	near, err := s.Autocomplete(ctx, domain.AutocompleteQuery{Input: "1", Near: brooklyn})
	if err != nil || len(near.Suggestions) == 0 || near.Suggestions[0].DistanceMeters == nil {
		t.Fatalf("biased: %+v %v", near, err)
	}
	for i := 1; i < len(near.Suggestions); i++ {
		if *near.Suggestions[i].DistanceMeters < *near.Suggestions[i-1].DistanceMeters {
			t.Fatalf("nearest first: %+v", near.Suggestions)
		}
	}
	a, err := s.Resolve(ctx, near.Suggestions[0].ID, near.Session)
	if err != nil || a.Line1 == "" || a.City == "" || a.State == "" || a.PostalCode == "" || a.Lat == 0 || a.Lon == 0 {
		t.Fatalf("picking a suggestion fills street, city, state, ZIP and coordinates: %+v %v", a, err)
	}

	for _, bad := range []domain.AutocompleteQuery{
		{Input: "1", SessionToken: "bad token!"},
		{Input: "1", Country: "USA"},
		{Input: "1", Kind: "street"},
		{Input: "1", Near: &domain.Point{Lat: 123, Lng: 0}},
		{Input: "1", Language: "en_US!"},
	} {
		if _, err := s.Autocomplete(ctx, bad); !errors.Is(err, domain.ErrInvalidQuery) {
			t.Errorf("%+v must be refused, got %v", bad, err)
		}
	}
}
