package httpapi_test

import (
	"net/url"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// Address autocomplete (Google Places billing): the BFF issues a session token on the first keystroke, forwards the
// client's token to geo on the following ones, restricts suggestions to the market's country, never resolves Google
// suggestions per keystroke, and resolves the picked one with the same token (Place Details closes the session).
func TestAddressSuggestionsKeepOneGooglePlacesSession(t *testing.T) {
	bff, b := setup(t, testkit.Options{})

	first := b.do("GET", "/v1/geo/address-suggestions?q=Peckham", nil)
	mustStatus(t, first, 200)
	body := first.json(t)
	token, _ := body["sessionToken"].(string)
	if len(token) < 8 {
		t.Fatalf("no session token issued: %v", body)
	}
	s := body["suggestions"].([]any)[0].(map[string]any)
	if s["source"] != "google" || s["resolved"] != false || s["address"].(map[string]any)["line1"] != "27 Peckham High St" ||
		body["attribution"] != "Powered by Google" {
		t.Fatalf("google suggestion = %v", body)
	}
	if n := len(bff.Upstreams.GeoResolves); n != 0 {
		t.Fatalf("google suggestions must not be resolved per keystroke, got %d resolves", n)
	}

	next := b.do("GET", "/v1/geo/address-suggestions?q=Peckham+High&sessionToken="+url.QueryEscape(token), nil)
	mustStatus(t, next, 200)
	if got := next.json(t)["sessionToken"]; got != token {
		t.Fatalf("session token changed mid-entry: %v → %v", token, got)
	}
	qs := bff.Upstreams.GeoAutocompleteQueries
	if len(qs) != 2 || qs[0].Get("session") != token || qs[1].Get("session") != token || qs[0].Get("country") != "US" {
		t.Fatalf("geo autocomplete queries = %v", qs)
	}

	picked := b.do("GET", "/v1/geo/address-suggestions/"+url.PathEscape("g:ChIJpeckham")+"?sessionToken="+url.QueryEscape(token), nil)
	mustStatus(t, picked, 200)
	a := picked.json(t)
	if a["line1"] != "27 Peckham High St" || a["line2"] != "Flat 3" || a["city"] != "London" || a["zip"] != "SE15 5EB" || a["country"] != "GB" {
		t.Fatalf("resolved address = %v", a)
	}
	if r := bff.Upstreams.GeoResolves; len(r) != 1 || r[0] != "g:ChIJpeckham?session="+token {
		t.Fatalf("geo resolves = %v", r)
	}
}

func TestAddressSuggestionsRejectBadSessionTokenAndCountry(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	mustStatus(t, b.do("GET", "/v1/geo/address-suggestions?q=9800&sessionToken=bad%20token", nil), 422)
	mustStatus(t, b.do("GET", "/v1/geo/address-suggestions?q=9800&country=USA", nil), 422)
	mustStatus(t, b.do("GET", "/v1/geo/address-suggestions/o%3A1?sessionToken=x", nil), 422)
	// A client country overrides the market's.
	mustStatus(t, b.do("GET", "/v1/geo/address-suggestions?q=9800&country=gb", nil), 200)
}
