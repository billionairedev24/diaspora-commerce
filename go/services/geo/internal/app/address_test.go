package app_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
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
func (failing) Suggest(context.Context, string, string, string, int) ([]domain.Suggestion, error) {
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
	out, err := svc(failing{}, fixture.New()).Autocomplete(context.Background(), "4521 Main", "s1", "US")
	if err != nil {
		t.Fatal(err)
	}
	if len(out) == 0 || out[0].MainText != "4521 Main St" || out[0].Provider != "fixture" {
		t.Fatalf("unexpected suggestions %+v", out)
	}
}

func TestResolveKeepsTypedHouseNumber(t *testing.T) {
	s := svc(fixture.New())
	sugs, err := s.Autocomplete(context.Background(), "4521 Main Houston", "", "US")
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
