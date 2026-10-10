package nominatim_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/nominatim"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

func TestReverse(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != "/reverse" || r.Header.Get("User-Agent") != "test-agent" || r.URL.Query().Get("format") != "jsonv2" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if r.URL.Query().Get("lat") == "0" {
			_, _ = w.Write([]byte(`{"error":"Unable to geocode"}`))
			return
		}
		_, _ = w.Write([]byte(`{"osm_type":"way","osm_id":42,"lat":"40.5781","lon":"-74.3532","display_name":"Oak Tree Road",
			"address":{"house_number":"1600","road":"Oak Tree Road","town":"Edison","state":"New Jersey","postcode":"08820","country_code":"us"}}`))
	}))
	defer srv.Close()
	n := nominatim.New(srv.URL, "test-agent", srv.Client())
	a, err := n.Reverse(context.Background(), 40.5781, -74.3532)
	if err != nil {
		t.Fatal(err)
	}
	if a.City != "Edison" || a.State != "NJ" || a.PostalCode != "08820" || a.Country != "US" || a.Line1 != "1600 Oak Tree Road" {
		t.Fatalf("got %+v", a)
	}
	if _, err := n.Reverse(context.Background(), 40.5781, -74.3532); err != nil || calls != 1 {
		t.Fatalf("expected a cached answer, calls=%d err=%v", calls, err)
	}
	if _, err := n.Reverse(context.Background(), 0, 0); !errors.Is(err, domain.ErrNoAddress) {
		t.Fatalf("expected ErrNoAddress, got %v", err)
	}
}

// S139: kind, location bias (viewbox, not bounded) and language reach Nominatim; street fields keep street results.
func TestSuggestKindBiasAndLanguage(t *testing.T) {
	var query map[string][]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.Query()
		_, _ = w.Write([]byte(`[
			{"osm_type":"way","osm_id":7,"lat":"40.65","lon":"-73.96","display_name":"740 Flatbush Avenue",
			 "address":{"house_number":"740","road":"Flatbush Avenue","city":"New York","state":"New York","postcode":"11226","country_code":"us"}},
			{"osm_type":"relation","osm_id":8,"lat":"40.65","lon":"-73.95","display_name":"11226",
			 "address":{"city":"New York","state":"New York","postcode":"11226","country_code":"us"}}]`))
	}))
	defer srv.Close()
	n := nominatim.New(srv.URL, "test-agent", srv.Client())
	sugs, err := n.Suggest(context.Background(), domain.AutocompleteQuery{Input: "7", Country: "US", Kind: domain.KindAddress,
		Near: &domain.Point{Lat: 40.65, Lng: -73.95}, RadiusMeters: 10000, Language: "fr", Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if query["accept-language"][0] != "fr" || query["bounded"][0] != "0" || query["viewbox"][0] == "" ||
		query["countrycodes"][0] != "us" {
		t.Fatalf("query %v", query)
	}
	if len(sugs) != 1 || sugs[0].Kind != domain.KindAddress || sugs[0].DistanceMeters == nil {
		t.Fatalf("street field keeps street results only: %+v", sugs)
	}
}
