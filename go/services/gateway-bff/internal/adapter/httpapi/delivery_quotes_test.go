package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// "Ask for a delivery quote" (listing rules D20) from the storefront: signed-in shoppers only, validated here, the same
// request for the same cart and address, only their own requests, and plain refusals. Every response is validated
// against contracts/openapi/gateway-bff.yaml.
func TestDeliveryQuoteRoutesConformAndKeepToTheShopper(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)

	// A signed-out shopper signs in first.
	ask := map[string]any{"addressId": "addr-too-far", "contact": "EMAIL", "note": "Gate code 4821"}
	mustStatus(t, b.do("POST", "/v1/me/delivery-quotes", ask), 401)
	mustStatus(t, b.do("GET", "/v1/me/delivery-quotes", nil), 401)
	mustStatus(t, b.do("GET", "/v1/me/delivery-quotes/0e5a6c2c-0000-4000-8000-000000000001", nil), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)

	// Bad input is refused here, with the fields named.
	for name, body := range map[string]map[string]any{
		"no address":  {"contact": "EMAIL"},
		"bad contact": {"addressId": "a", "contact": "PIGEON"},
		"long note":   {"addressId": "a", "note": strings.Repeat("x", 501)},
		"bad line":    {"addressId": "a", "lines": []any{map[string]any{"variantAtin": "", "quantity": 0}}},
	} {
		r := b.do("POST", "/v1/me/delivery-quotes", body)
		if r.status != 422 {
			t.Fatalf("%s: status %d: %s", name, r.status, r.body)
		}
	}

	// Asking returns the request, plainly; asking again for the same cart and address returns the same one.
	r := b.do("POST", "/v1/me/delivery-quotes", ask)
	mustStatus(t, r, 200)
	first := r.json(t)
	if first["status"] != "REQUESTED" || first["statusLabel"] != "Requested" || first["place"] != "Marfa, TX 79843" || first["amountMinor"] != nil {
		t.Fatalf("request = %s", r.body)
	}
	if strings.Contains(string(r.body), "agreedBy") || strings.Contains(string(r.body), "reason") {
		t.Fatalf("the shopper sees internals: %s", r.body)
	}
	again := b.do("POST", "/v1/me/delivery-quotes", ask)
	mustStatus(t, again, 200)
	if again.json(t)["id"] != first["id"] {
		t.Fatalf("a repeat made another request: %s", again.body)
	}

	// My quote requests, and one of them; anyone else's (or an unknown one) is not found.
	list := b.do("GET", "/v1/me/delivery-quotes", nil)
	mustStatus(t, list, 200)
	if items := list.json(t)["items"].([]any); len(items) != 1 {
		t.Fatalf("my requests = %s", list.body)
	}
	mustStatus(t, b.do("GET", "/v1/me/delivery-quotes/"+first["id"].(string), nil), 200)
	mustStatus(t, b.do("GET", "/v1/me/delivery-quotes/not-mine", nil), 404)

	// Too many requests: orders' answer comes through as 429 with Retry-After.
	limited := b.do("POST", "/v1/me/delivery-quotes", map[string]any{"addressId": "addr-too-far-rate-limited"})
	mustStatus(t, limited, 429)
	if limited.header.Get("Retry-After") == "" {
		t.Fatalf("no Retry-After: %v", limited.header)
	}

	for _, op := range []string{"requestDeliveryQuote", "listMyDeliveryQuotes", "getMyDeliveryQuote"} {
		if validated[op] == 0 {
			t.Errorf("%s was never validated against the contract", op)
		}
	}
}
