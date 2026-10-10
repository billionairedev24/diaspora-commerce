package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/fixture"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// fakeReverse is an in-memory reverse geocoder: it records the point it was asked for and answers addr or err.
type fakeReverse struct {
	name     string
	addr     domain.Address
	err      error
	lat, lon float64
	calls    int
}

func (f *fakeReverse) Name() string { return f.name }

func (f *fakeReverse) Reverse(_ context.Context, lat, lon float64) (domain.Address, error) {
	f.calls++
	f.lat, f.lon = lat, lon
	return f.addr, f.err
}

func reverseSvc(p ...port.ReverseGeocoder) app.ReverseService { return app.NewReverseService(p, quiet) }

func TestReverseUsesGoogleFirst(t *testing.T) {
	google := &fakeReverse{name: "google-geocoding", addr: domain.Address{Line1: "123 Main St", City: "Houston", State: "TX", PostalCode: "77002-1234", Country: "us"}}
	osm := &fakeReverse{name: "osm-nominatim", addr: domain.Address{City: "Elsewhere", PostalCode: "99999"}}
	a, err := reverseSvc(google, osm).Reverse(context.Background(), 29.758912345, -95.367712345)
	if err != nil {
		t.Fatal(err)
	}
	if a.PostalCode != "77002" || a.City != "Houston" || a.State != "TX" || a.Country != "US" || osm.calls != 0 {
		t.Fatalf("got %+v (osm calls %d)", a, osm.calls)
	}
	if google.lat != 29.7589 || google.lon != -95.3677 {
		t.Fatalf("coordinates not rounded: %v,%v", google.lat, google.lon)
	}
	if a.Lat != 29.7589 || a.Lon != -95.3677 {
		t.Fatalf("point not echoed: %+v", a)
	}
}

func TestReverseFallsBackToNominatim(t *testing.T) {
	google := &fakeReverse{name: "google-geocoding", err: errors.New("OVER_QUERY_LIMIT")}
	osm := &fakeReverse{name: "osm-nominatim", addr: domain.Address{City: "Edison", State: "NJ", PostalCode: "08820", Country: "US", Lat: 40.5, Lon: -74.3}}
	a, err := reverseSvc(google, osm).Reverse(context.Background(), 40.5781, -74.3532)
	if err != nil {
		t.Fatal(err)
	}
	if a.PostalCode != "08820" || osm.calls != 1 {
		t.Fatalf("got %+v", a)
	}
}

func TestReverseNoAddressIsNotFound(t *testing.T) {
	google := &fakeReverse{name: "google-geocoding", err: domain.ErrNoAddress}
	osm := &fakeReverse{name: "osm-nominatim", addr: domain.Address{City: "Nowhere"}} // no ZIP: not usable
	_, err := reverseSvc(google, osm).Reverse(context.Background(), 0, -30)
	if !errors.Is(err, app.ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func TestReverseAllProvidersDown(t *testing.T) {
	_, err := reverseSvc(&fakeReverse{name: "google-geocoding", err: errors.New("down")}).Reverse(context.Background(), 1, 1)
	if err == nil || errors.Is(err, app.ErrNotFound) {
		t.Fatalf("expected a provider error, got %v", err)
	}
}

func TestReverseRejectsBadPoint(t *testing.T) {
	_, err := reverseSvc(fixture.New()).Reverse(context.Background(), 91, 0)
	if !errors.Is(err, domain.ErrInvalidPoint) {
		t.Fatalf("expected ErrInvalidPoint, got %v", err)
	}
}

func TestReverseFixtureNearestStreet(t *testing.T) {
	a, err := reverseSvc(fixture.New()).Reverse(context.Background(), 29.76, -95.37)
	if err != nil {
		t.Fatal(err)
	}
	if a.PostalCode != "77002" || a.City != "Houston" {
		t.Fatalf("got %+v", a)
	}
	if _, err := reverseSvc(fixture.New()).Reverse(context.Background(), 0, 0); !errors.Is(err, app.ErrNotFound) {
		t.Fatalf("expected ErrNotFound in the ocean, got %v", err)
	}
}
