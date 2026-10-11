package httpapi_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/gazetteer"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
)

func placesMux(t *testing.T) *http.ServeMux {
	t.Helper()
	world, err := gazetteer.New()
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	pass := func(h http.Handler) http.Handler { return h }
	httpapi.NewPlaces(app.NewPlaceService(world)).Register(mux, pass, pass)
	return mux
}

func getJSON(t *testing.T, mux *http.ServeMux, path string, want int) map[string]any {
	t.Helper()
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil))
	if rec.Code != want {
		t.Fatalf("GET %s = %d, want %d: %s", path, rec.Code, want, rec.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestPlaces(t *testing.T) {
	mux := placesMux(t)
	if items := getJSON(t, mux, "/v1/places/countries", 200)["items"].([]any); len(items) < 200 {
		t.Fatalf("countries = %d", len(items))
	}
	us := getJSON(t, mux, "/v1/places/countries/US/regions", 200)
	if us["regionLabel"] != "State" || len(us["items"].([]any)) < 50 {
		t.Fatalf("US regions = %v", us["regionLabel"])
	}
	tx := getJSON(t, mux, "/v1/places/countries/US/regions/TX/cities?q=lub", 200)
	if tx["regionName"] != "Texas" || tx["total"].(float64) < 1 {
		t.Fatalf("TX cities ?q=lub = %v", tx)
	}
	found := false
	for _, c := range tx["items"].([]any) {
		found = found || c == "Lubbock"
	}
	if !found {
		t.Fatalf("Lubbock missing: %v", tx["items"])
	}
	if few := getJSON(t, mux, "/v1/places/countries/US/regions/TX/cities?limit=3", 200); len(few["items"].([]any)) != 3 || few["total"].(float64) < 500 {
		t.Fatalf("limit 3 = %v items of %v", len(few["items"].([]any)), few["total"])
	}
	getJSON(t, mux, "/v1/places/countries/ZZ/regions", 404)
	getJSON(t, mux, "/v1/places/countries/US/regions/ZZ/cities", 404)
	getJSON(t, mux, "/v1/places/countries/US/regions/TX/cities?limit=x", 400)
	getJSON(t, mux, "/v1/places/search?q=a", 400)
	hits := getJSON(t, mux, "/v1/places/search?q=el%20paso&country=US", 200)["items"].([]any)
	elPaso := false
	for _, h := range hits {
		elPaso = elPaso || h.(map[string]any)["key"] == "US/TX/El Paso"
	}
	if !elPaso {
		t.Fatalf("el paso = %v", hits)
	}
}
