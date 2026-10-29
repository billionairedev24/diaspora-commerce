package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3filter"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestPreorderStorefrontRoutes drives the storefront pre-order routes (resolve from the session's delivery location
// or an explicit place, the open list, the waitlist and the cart's pre-order fulfilment) against the fake
// delivery-runs and cart services and validates every answer against gateway-bff.yaml.
func TestPreorderStorefrontRoutes(t *testing.T) {
	router := loadRouter(t)
	bff, b := setup(t, testkit.Options{})
	validated := map[string]int{}
	b.onResponse = func(req *http.Request, rec *httptest.ResponseRecorder) {
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Errorf("%s %s is not in the contract: %v", req.Method, req.URL.Path, err)
			return
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", req.Method, req.URL.Path, rec.Code, rec.Body.Bytes())
			return
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", req.Method, req.URL.Path, rec.Code, err, rec.Body.Bytes())
			return
		}
		validated[route.Operation.OperationID]++
	}

	// Default location (Houston): no pre-order, SHIP fallback.
	r := b.do("GET", "/v1/preorders/resolve", nil)
	mustStatus(t, r, 200)
	if v := r.json(t); v["on"] != false || v["fallback"] != "SHIP" || v["place"].(map[string]any)["city"] != "Houston" {
		t.Fatalf("houston = %v", v)
	}
	// The shopper's delivery location (Lubbock ZIP) drives the resolution.
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "79401"}), 200)
	r = b.do("GET", "/v1/preorders/resolve", nil)
	mustStatus(t, r, 200)
	v := r.json(t)
	place := v["place"].(map[string]any)
	if v["on"] != true || place["source"] != "session" || place["region"] != "TX" || place["country"] != "US" {
		t.Fatalf("lubbock = %v", v)
	}
	if w := v["window"].(map[string]any); w["doorFeeMinor"] != float64(799) || w["currency"] != "USD" || v["covered"] != true || v["fallback"] != "SHIP" {
		t.Fatalf("effective fees must be relayed: %v", v)
	}
	if !strings.Contains(r.header.Get("Cache-Control"), "max-age=30") {
		t.Fatalf("cache-control = %q", r.header.Get("Cache-Control"))
	}
	// An explicit place overrides the session.
	r = b.do("GET", "/v1/preorders/resolve?country=us&region=tx&city=Lubbock", nil)
	mustStatus(t, r, 200)
	if v := r.json(t); v["place"].(map[string]any)["source"] != "query" || v["on"] != true {
		t.Fatalf("query = %v", v)
	}
	r = b.do("GET", "/v1/preorders/resolve?city=Lubbock", nil)
	mustStatus(t, r, 422)
	r = b.do("GET", "/v1/preorders/resolve?country=USA&region=TX", nil)
	mustStatus(t, r, 422)

	r = b.do("GET", "/v1/preorders/open", nil)
	mustStatus(t, r, 200)
	if open := r.json(t); len(open["windows"].([]any)) != 1 || open["windows"].([]any)[0].(map[string]any)["code"] != "LBK-7" || open["placements"] == nil {
		t.Fatalf("open = %v", open)
	}

	mustStatus(t, b.do("POST", "/v1/preorders/waitlist", map[string]any{"email": "nope"}), 422)
	r = b.do("POST", "/v1/preorders/waitlist", map[string]any{"email": "ada@example.com", "place": map[string]any{"country": "US", "region": "TX", "city": "Midland"}})
	mustStatus(t, r, 202)
	if v := r.json(t); v["area"] != "US/TX/Midland" || v["status"] != "subscribed" || v["city"] != "Midland" {
		t.Fatalf("waitlist = %v", v)
	}
	if w := bff.Upstreams.LastWaitlist; w["area"] != "US/TX/Midland" || w["source"] != "preorder-waitlist" || w["email"] != "ada@example.com" {
		t.Fatalf("delivery-runs body = %v", w)
	}

	// Waitlist unsubscribe link (no sign-in): the token goes to delivery-runs; unknown or empty tokens are a 422 on token.
	r = b.do("POST", "/v1/preorders/waitlist/unsubscribe", map[string]any{"token": "wl-token-1"})
	mustStatus(t, r, 200)
	if v := r.json(t); v["status"] != "unsubscribed" {
		t.Fatalf("unsubscribe = %v", v)
	}
	r = b.do("POST", "/v1/preorders/waitlist/unsubscribe", map[string]any{"token": "stale"})
	mustStatus(t, r, 422)
	if e, _ := r.json(t)["errors"].(map[string]any); e["token"] == nil {
		t.Fatalf("unsubscribe errors = %v", r.json(t))
	}
	mustStatus(t, b.do("POST", "/v1/preorders/waitlist/unsubscribe", map[string]any{"token": "  "}), 422)

	// Cart: choose pickup for the pre-order package; the block is relayed and the package id marks it.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEA0001", "quantity": 1}), 200)
	mustStatus(t, b.do("PUT", "/v1/cart/preorder-fulfilment", map[string]any{"windowId": "LBK-7", "fulfilment": "COURIER"}), 422)
	r = b.do("PUT", "/v1/cart/preorder-fulfilment", map[string]any{"windowId": "LBK-7", "fulfilment": "pickup"})
	mustStatus(t, r, 200)
	pkg := r.json(t)["packages"].([]any)[0].(map[string]any)
	pre, _ := pkg["preorder"].(map[string]any)
	if pre == nil || pre["fulfilment"] != "PICKUP" || pre["doorFeeMinor"] != float64(799) || !strings.HasSuffix(pkg["packageId"].(string), ":preorder") ||
		len(pkg["deliveryOptions"].([]any)) != 0 {
		t.Fatalf("pre-order package = %v", pkg)
	}
	// ADR 0005 §3: the cart shows the delivery fee rule behind the chosen fee and behind each option.
	rule, _ := pre["feeRule"].(map[string]any)
	if rule == nil || rule["method"] != "PICKUP" || rule["ruleVersion"] != float64(2) || rule["amountMinor"] != float64(0) {
		t.Fatalf("chosen fee rule = %v", pre["feeRule"])
	}
	door, _ := pre["options"].([]any)[0].(map[string]any)["rule"].(map[string]any)
	if door == nil || door["method"] != "AFROTRANSACT_DELIVERY" || door["band"] != "10–40 mi" || door["ruleVersion"] != float64(3) {
		t.Fatalf("door option rule = %v", pre["options"])
	}
	r = b.do("GET", "/v1/cart", nil)
	mustStatus(t, r, 200)
	mustStatus(t, b.do("PUT", "/v1/cart/preorder-fulfilment", map[string]any{"windowId": "NOPE-1", "fulfilment": "DOOR"}), 404)

	for _, op := range []string{"resolvePreorder", "listOpenPreorders", "joinPreorderWaitlist", "setPreorderFulfilment"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
