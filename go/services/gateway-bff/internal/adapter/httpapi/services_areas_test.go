package httpapi_test

import (
	"strconv"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// Services near the visitor: the deliver-to location decides, in-person only, a designed answer per coverage.
func TestServiceSearchByPlaceAndCoverage(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true})
	validated := validateAll(t, b)

	// Local matches: distance, zone, the cities served, a photo, the delivery mode and per-category counts.
	r := b.do("GET", "/v1/services/search?zip=78626", nil)
	mustStatus(t, r, 200)
	got := r.json(t)
	if got["coverage"] != "LOCAL" || got["city"] != "Georgetown" || got["state"] != "TX" || got["zone"] != "Greater Austin" {
		t.Fatalf("search = %v", got)
	}
	hit := got["services"].([]any)[0].(map[string]any)
	if hit["delivery"] != "BOTH" || hit["imageUrl"] == "" {
		t.Fatalf("listing = %v", hit)
	}
	area := hit["area"].(map[string]any)
	if area["baseCity"] != "Round Rock" || area["distanceMiles"] != 9.4 || len(area["serves"].([]any)) != 4 {
		t.Fatalf("area = %v", area)
	}
	cats := got["categories"].([]any)
	if len(cats) != 1 || cats[0].(map[string]any)["category"] != "hair & beauty" {
		t.Fatalf("a category with no services is never listed: %v", cats)
	}

	if got["radiusMiles"] != 25.0 || got["radiusDefault"] != true {
		t.Fatalf("no choice = the category default radius: %v", got)
	}
	if _, has := got["nearest"]; has {
		t.Fatalf("there is no nearest list any more: %v", got)
	}

	// Nobody serves New York in the fixture: NONE, no providers, no categories.
	none := b.do("GET", "/v1/services/search?zip=10001", nil).json(t)
	if none["coverage"] != "NONE" || len(none["services"].([]any)) != 0 || len(none["categories"].([]any)) != 0 {
		t.Fatalf("none = %v", none)
	}

	// The shopper decides how far to go: nothing at the default distance, found when they widen it, and any distance works.
	narrow := b.do("GET", "/v1/services/search?zip=78701&category=tailoring", nil).json(t)
	if narrow["coverage"] != "NONE" || narrow["radiusMiles"] != 25.0 || narrow["radiusDefault"] != true {
		t.Fatalf("default radius = %v", narrow)
	}
	for _, radius := range []string{"50", "100", "any", "0", "1000"} {
		wide := b.do("GET", "/v1/services/search?zip=78701&category=tailoring&radiusMiles="+radius, nil).json(t)
		want := 0.0
		if radius != "any" && radius != "0" {
			want = float64(mustAtoi(t, radius))
		}
		if wide["coverage"] != "LOCAL" || len(wide["services"].([]any)) != 1 || wide["radiusMiles"] != want || wide["radiusDefault"] != false {
			t.Fatalf("radius %s = %v", radius, wide)
		}
		if a := wide["services"].([]any)[0].(map[string]any)["area"].(map[string]any); a["distanceMiles"] != 28.0 {
			t.Fatalf("distance is shown: %v", a)
		}
	}
	if small := b.do("GET", "/v1/services/search?zip=78701&radiusMiles=10", nil).json(t); small["coverage"] != "NONE" || small["radiusMiles"] != 10.0 {
		t.Fatalf("10 miles = %v", small)
	}
	for _, bad := range []string{"-1", "1001", "far", "2.5", "99999999999999999999"} {
		mustStatus(t, b.do("GET", "/v1/services/search?zip=78626&radiusMiles="+bad, nil), 422)
	}

	mustStatus(t, b.do("GET", "/v1/services/search?zip=78626&sort=CHEAPEST", nil), 422)
	mustStatus(t, b.do("GET", "/v1/services/search?zip=78626&sort=soonest", nil), 200)
	if validated["searchServices"] < 3 {
		t.Fatalf("search responses validated against the contract: %v", validated)
	}
}

func TestServiceSuggestionsAndAlerts(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true})
	validated := validateAll(t, b)

	r := b.do("GET", "/v1/services/suggest?q=bra", nil)
	mustStatus(t, r, 200)
	if got := string(r.body); got != "[\"Knotless braids\",\"Braids by Ama\"]\n" {
		t.Fatalf("suggestions = %q", got)
	}
	if got := string(b.do("GET", "/v1/services/suggest?q=b", nil).body); got != "[]\n" {
		t.Fatalf("one letter suggests nothing: %q", got)
	}

	// "Tell me when one joins" works signed out; the place is the deliver-to location.
	mustStatus(t, b.do("POST", "/v1/services/alerts", map[string]any{"email": "ada@example.com", "category": "Tailoring"}), 202)
	mustStatus(t, b.do("POST", "/v1/services/alerts", map[string]any{"email": "ada@example.com"}), 202)
	mustStatus(t, b.do("POST", "/v1/services/alerts", map[string]any{"email": "not-an-email"}), 422)
	mustStatus(t, b.do("POST", "/v1/services/alerts", map[string]any{}), 422)
	if validated["alertServices"] < 2 || validated["suggestServices"] < 1 {
		t.Fatalf("validated = %v", validated)
	}
}

func mustAtoi(t *testing.T, s string) int {
	t.Helper()
	n, err := strconv.Atoi(s)
	if err != nil {
		t.Fatal(err)
	}
	return n
}
