package places_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
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
	sugs, err := p.Suggest(context.Background(), "123 Main", "s", "US", 5)
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
