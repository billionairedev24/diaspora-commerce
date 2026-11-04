package places_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

// Fixtures in testdata/ have the exact shape of Places API (New) answers: POST /v1/places:autocomplete and
// GET /v1/places/{id} with the field mask addressComponents,formattedAddress,location. Refresh one with
//
//	curl -s -H "X-Goog-Api-Key: $GOOGLE_MAPS_API_KEY" -H 'X-Goog-FieldMask: addressComponents,formattedAddress,location' \
//	  https://places.googleapis.com/v1/places/<placeId> > testdata/details_<case>.json

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// googleStub answers autocomplete with autocomplete_us.json and details with details_<placeId>.json, and records
// what each call sent.
type googleStub struct {
	autocompleteBody map[string]any
	detailsQuery     string
	fieldMask        string
}

func (g *googleStub) server(t *testing.T, details map[string]string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Goog-Api-Key") != "test-key" {
			http.Error(w, `{"error":{"code":403,"status":"PERMISSION_DENIED"}}`, http.StatusForbidden)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/places:autocomplete":
			_ = json.NewDecoder(r.Body).Decode(&g.autocompleteBody)
			_, _ = w.Write(fixture(t, "autocomplete_us.json"))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/places/"):
			g.detailsQuery, g.fieldMask = r.URL.RawQuery, r.Header.Get("X-Goog-FieldMask")
			name, ok := details[strings.TrimPrefix(r.URL.Path, "/places/")]
			if !ok {
				http.NotFound(w, r)
				return
			}
			_, _ = w.Write(fixture(t, name))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestAutocompleteFixtureRestrictsCountryAndKeepsSession(t *testing.T) {
	g := &googleStub{}
	srv := g.server(t, nil)
	p := places.New("test-key", srv.URL, srv.Client())
	sugs, err := p.Suggest(context.Background(), domain.AutocompleteQuery{Input: "9800 Westh", SessionToken: "tok_12345678",
		Country: "US", Kind: domain.KindAddress, Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if g.autocompleteBody["sessionToken"] != "tok_12345678" {
		t.Fatalf("session token not sent: %v", g.autocompleteBody)
	}
	if regions := g.autocompleteBody["includedRegionCodes"].([]any); len(regions) != 1 || regions[0] != "US" {
		t.Fatalf("country restriction %v", g.autocompleteBody["includedRegionCodes"])
	}
	// Query predictions (no placeId) are skipped; place predictions keep their main and secondary text.
	if len(sugs) != 2 || sugs[0].ID != "g:ChIJ7Y7Q6xnDQIYRk3o0Z7Yq1mE" || sugs[0].MainText != "9800 Westheimer Rd" ||
		sugs[0].SecondaryText != "Houston, TX, USA" || sugs[0].Provider != "google-places" || sugs[1].Kind != domain.KindAddress {
		t.Fatalf("suggestions %+v", sugs)
	}
}

func TestPlaceDetailsFixturesMapToPostalAddresses(t *testing.T) {
	cases := []struct {
		name, fixture string
		want          domain.Address
	}{
		{"US street number + route, subpremise as line 2, ZIP without the +4 suffix", "details_us_subpremise.json",
			domain.Address{Line1: "9800 Westheimer Rd", Line2: "#210", City: "Houston", State: "TX", PostalCode: "77042", Country: "US",
				Lat: 29.7365, Lon: -95.5541}},
		{"New York borough: no locality, the city is the sublocality", "details_nyc_sublocality.json",
			domain.Address{Line1: "345 Flatbush Ave", City: "Brooklyn", State: "NY", PostalCode: "11238", Country: "US",
				Lat: 40.6745, Lon: -73.9705}},
		{"UK: postal_town is the city, a worded subpremise stays as written", "details_uk_postal_town.json",
			domain.Address{Line1: "27 Peckham High St", Line2: "Flat 3", City: "London", State: "England", PostalCode: "SE15 5EB",
				Country: "GB", Lat: 51.4737, Lon: -0.0691}},
		{"no street: the premise is line 1, locality beats sublocality, state short name", "details_ng_premise.json",
			domain.Address{Line1: "Ikeja City Mall", City: "Ikeja", State: "LA", PostalCode: "101233", Country: "NG",
				Lat: 6.6142, Lon: 3.3581}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			g := &googleStub{}
			srv := g.server(t, map[string]string{"place-1": tc.fixture})
			p := places.New("test-key", srv.URL, srv.Client())
			got, err := p.Resolve(context.Background(), "g:place-1", "tok_12345678")
			if err != nil {
				t.Fatal(err)
			}
			if got != tc.want {
				t.Fatalf("got  %+v\nwant %+v", got, tc.want)
			}
			// The details call closes the autocomplete session (one billable session) and asks for Essentials fields only.
			if g.detailsQuery != "sessionToken=tok_12345678" || g.fieldMask != "addressComponents,formattedAddress,location" {
				t.Fatalf("details call: query %q, field mask %q", g.detailsQuery, g.fieldMask)
			}
		})
	}
}

func TestPlacesErrorsAreReported(t *testing.T) {
	g := &googleStub{}
	srv := g.server(t, nil)
	p := places.New("wrong-key", srv.URL, srv.Client())
	if _, err := p.Suggest(context.Background(), domain.AutocompleteQuery{Input: "9800", Country: "US", Limit: 5}); err == nil ||
		!strings.Contains(err.Error(), "status 403") {
		t.Fatalf("want a 403 error, got %v", err)
	}
}
