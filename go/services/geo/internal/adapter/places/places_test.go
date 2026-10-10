package places_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

func TestAutocompleteAndResolve(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Goog-Api-Key") != "k" {
			http.Error(w, "no key", http.StatusForbidden)
			return
		}
		switch r.URL.Path {
		case "/places:autocomplete":
			_, _ = w.Write([]byte(`{"suggestions":[{"placePrediction":{"placeId":"abc","text":{"text":"123 Main St, Houston, TX"},
				"structuredFormat":{"mainText":{"text":"123 Main St"},"secondaryText":{"text":"Houston, TX"}}}}]}`))
		case "/places/abc":
			if r.Header.Get("X-Goog-FieldMask") == "" || r.URL.Query().Get("sessionToken") != "s" {
				http.Error(w, "missing field mask or session", http.StatusBadRequest)
				return
			}
			_, _ = w.Write([]byte(`{"addressComponents":[
				{"longText":"123","shortText":"123","types":["street_number"]},
				{"longText":"Main Street","shortText":"Main St","types":["route"]},
				{"longText":"Houston","shortText":"Houston","types":["locality"]},
				{"longText":"Texas","shortText":"TX","types":["administrative_area_level_1"]},
				{"longText":"77002","shortText":"77002","types":["postal_code"]},
				{"longText":"United States","shortText":"US","types":["country"]}],
				"location":{"latitude":29.75,"longitude":-95.36}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	p := places.New("k", srv.URL, srv.Client())
	sugs, err := p.Suggest(context.Background(), domain.AutocompleteQuery{Input: "123 Main", SessionToken: "s",
		Country: "US", Limit: 5})
	if err != nil || len(sugs) != 1 || sugs[0].ID != "g:abc" {
		t.Fatalf("suggest %+v %v", sugs, err)
	}
	a, err := p.Resolve(context.Background(), sugs[0].ID, "s")
	if err != nil {
		t.Fatal(err)
	}
	if a.Line1 != "123 Main St" || a.State != "TX" || a.PostalCode != "77002" || a.Lat == 0 {
		t.Fatalf("resolved %+v", a)
	}
}

// S139: the request carries the session token, country, the form's kind, the location bias and the language; the
// distance and kind come back on each suggestion.
func TestAutocompleteSendsKindBiasLanguageAndSession(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&got)
		_, _ = w.Write([]byte(`{"suggestions":[{"placePrediction":{"placeId":"z1","text":{"text":"77036, Houston, TX"},
			"types":["postal_code","geocode"],"distanceMeters":5230,
			"structuredFormat":{"mainText":{"text":"77036"},"secondaryText":{"text":"Houston, TX"}}}}]}`))
	}))
	defer srv.Close()
	p := places.New("k", srv.URL, srv.Client())
	sugs, err := p.Suggest(context.Background(), domain.AutocompleteQuery{Input: "7", SessionToken: "sess-1234",
		Country: "US", Kind: domain.KindPostalCode, Near: &domain.Point{Lat: 29.76, Lng: -95.37}, RadiusMeters: 30000,
		Language: "fr", Limit: 5})
	if err != nil {
		t.Fatal(err)
	}
	if got["sessionToken"] != "sess-1234" || got["languageCode"] != "fr" || got["input"] != "7" {
		t.Fatalf("request %v", got)
	}
	if types := got["includedPrimaryTypes"].([]any); len(types) != 1 || types[0] != "postal_code" {
		t.Fatalf("a ZIP field asks for postal codes only: %v", types)
	}
	bias := got["locationBias"].(map[string]any)["circle"].(map[string]any)
	if bias["radius"].(float64) != 30000 || bias["center"].(map[string]any)["latitude"].(float64) != 29.76 {
		t.Fatalf("location bias %v", bias)
	}
	if regions := got["includedRegionCodes"].([]any); regions[0] != "US" {
		t.Fatalf("country restriction %v", regions)
	}
	if len(sugs) != 1 || sugs[0].Kind != domain.KindPostalCode || sugs[0].DistanceMeters == nil || *sugs[0].DistanceMeters != 5230 {
		t.Fatalf("suggestions %+v", sugs)
	}
	if !strings.HasPrefix(sugs[0].ID, "g:") {
		t.Fatalf("id %s", sugs[0].ID)
	}
}
