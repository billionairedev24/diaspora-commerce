package places_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

func TestReverseGeocode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/geocode/json" || r.URL.Query().Get("key") != "k" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if r.URL.Query().Get("latlng") == "0,0" {
			_, _ = w.Write([]byte(`{"status":"ZERO_RESULTS","results":[]}`))
			return
		}
		_, _ = w.Write([]byte(`{"status":"OK","results":[{"address_components":[
			{"long_name":"123","short_name":"123","types":["street_number"]},
			{"long_name":"Main Street","short_name":"Main St","types":["route"]},
			{"long_name":"Houston","short_name":"Houston","types":["locality","political"]},
			{"long_name":"Texas","short_name":"TX","types":["administrative_area_level_1","political"]},
			{"long_name":"United States","short_name":"US","types":["country","political"]},
			{"long_name":"77002","short_name":"77002","types":["postal_code"]}],
			"geometry":{"location":{"lat":29.7589,"lng":-95.3677}}}]}`))
	}))
	defer srv.Close()
	g := places.NewGeocoder("k", srv.URL, srv.Client())
	a, err := g.Reverse(context.Background(), 29.7589, -95.3677)
	if err != nil {
		t.Fatal(err)
	}
	if a.Line1 != "123 Main St" || a.City != "Houston" || a.State != "TX" || a.PostalCode != "77002" || a.Country != "US" {
		t.Fatalf("got %+v", a)
	}
	if _, err := g.Reverse(context.Background(), 0, 0); !errors.Is(err, domain.ErrNoAddress) {
		t.Fatalf("expected ErrNoAddress, got %v", err)
	}
}
