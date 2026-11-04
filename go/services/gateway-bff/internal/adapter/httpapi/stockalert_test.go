package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

const alertPath = "/v1/products/ATGARI0001/stock-alerts"

// A visitor who is not signed in subscribes with just an email: no 401, the BFF's own service token goes upstream.
func TestGuestStockAlert(t *testing.T) {
	bff, b := setup(t, testkit.Options{Notifications: true})
	b.headers["Accept-Language"] = "fr-CA,fr;q=0.9,en;q=0.5"
	r := b.do("POST", alertPath, map[string]any{"email": "  Ada.Obi@Example.COM "})
	mustStatus(t, r, 201)
	if j := r.json(t); j["status"] != "subscribed" || j["atin"] != "ATGARI0001" {
		t.Fatalf("%v", j)
	}
	if strings.Contains(string(r.body), "example.com") {
		t.Fatalf("the answer must not echo the address: %s", r.body)
	}
	got := bff.Upstreams.GuestStockAlerts
	if len(got) != 1 || got[0]["email"] != "ada.obi@example.com" || got[0]["atin"] != "ATGARIV001" || got[0]["locale"] != "fr" ||
		got[0]["productTitle"] != "Ijebu Garri" {
		t.Fatalf("upstream got %v", got)
	}
	if reqs := bff.Upstreams.ServiceTokenRequests; len(reqs) == 0 || reqs[len(reqs)-1] != app.GuestStockAlertScope {
		t.Fatalf("service token scope = %v", reqs)
	}
	// Repeating, or a different spelling, is the same quiet 201 (the upstream decides what is new).
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "ada.obi@example.com"}), 201)
	// A guest has no account list.
	mustStatus(t, b.do("GET", "/v1/account/stock-alerts", nil), 401)
}

func TestGuestStockAlertValidation(t *testing.T) {
	bff, b := setup(t, testkit.Options{Notifications: true})
	for name, body := range map[string]any{"missing": map[string]any{}, "blank": map[string]any{"email": "  "}, "no at": map[string]any{"email": "nope"},
		"display name": map[string]any{"email": "Ada <a@example.com>"}, "two": map[string]any{"email": "a@example.com,b@example.com"},
		"crlf": map[string]any{"email": "a@example.com\r\nBcc: v@example.com"}, "quoted": map[string]any{"email": `"a b"@example.com`},
		"long": map[string]any{"email": strings.Repeat("a", 70) + "@example.com"}} {
		r := b.do("POST", alertPath, body)
		mustStatus(t, r, 422)
		if code := problemCode(t, r); code != "VALIDATION_FAILED" {
			t.Errorf("%s: %s", name, code)
		}
		if _, ok := r.json(t)["errors"].(map[string]any)["email"]; !ok {
			t.Errorf("%s: no email field error: %s", name, r.body)
		}
	}
	mustStatus(t, b.do("POST", "/v1/products/ATNOPE0000/stock-alerts", map[string]any{"email": "a@example.com"}), 404)
	if n := len(bff.Upstreams.GuestStockAlerts); n != 0 {
		t.Fatalf("nothing may reach notifications for a bad request: %d", n)
	}
}

func TestStockAlertRateLimits(t *testing.T) {
	u := testkit.NewUpstreams(testkit.Options{Notifications: true})
	t.Cleanup(u.Close)
	bff := testkit.NewBFF(u, testkit.Config{StockAlertLimits: app.StockAlertLimits{PerIPPerHour: 6, PerEmailPerHour: 2, PerATINPerHour: 100}})
	b := newBrowser(t, bff.Handler)

	// Per email: the third request for one address in the hour is a 429 with Retry-After, whoever asks.
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "victim@example.com"}), 201)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "Victim@example.com"}), 201)
	r := b.do("POST", alertPath, map[string]any{"email": "victim@example.com"})
	mustStatus(t, r, 429)
	if code := problemCode(t, r); code != "RATE_LIMITED" || r.header.Get("Retry-After") == "" {
		t.Fatalf("%s %v", code, r.header)
	}
	if strings.Contains(string(r.body), "victim") {
		t.Fatalf("the 429 must not echo the address: %s", r.body)
	}
	// Per client IP: other addresses count against the same visitor (3 requests so far, 6 allowed).
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "b1@example.com"}), 201)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "b2@example.com"}), 201)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "b3@example.com"}), 201)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "b4@example.com"}), 429)
	if n := len(u.GuestStockAlerts); n != 5 {
		t.Fatalf("limited requests must not reach notifications: %d", n)
	}
}

func TestStockAlertRateLimitPerATIN(t *testing.T) {
	u := testkit.NewUpstreams(testkit.Options{Notifications: true})
	t.Cleanup(u.Close)
	bff := testkit.NewBFF(u, testkit.Config{StockAlertLimits: app.StockAlertLimits{PerIPPerHour: 100, PerEmailPerHour: 100, PerATINPerHour: 2}})
	b := newBrowser(t, bff.Handler)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "a1@example.com"}), 201)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "a2@example.com"}), 201)
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "a3@example.com"}), 429)
}

// Signed-in shoppers are unchanged: their own token, their account, listed and removable.
func TestSignedInStockAlertUnchanged(t *testing.T) {
	bff, b := setup(t, testkit.Options{Notifications: true})
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	mustStatus(t, b.do("POST", alertPath, map[string]any{}), 201) // the account's email is used
	mustStatus(t, b.do("POST", alertPath, map[string]any{}), 201) // 200 upstream: still subscribed
	mustStatus(t, b.do("POST", alertPath, map[string]any{"email": "not an email"}), 422)
	if n := len(bff.Upstreams.GuestStockAlerts); n != 0 {
		t.Fatalf("a signed-in shopper must not go through the guest route: %d", n)
	}
	items := b.do("GET", "/v1/account/stock-alerts", nil).json(t)["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["atin"] != "ATGARIV001" || items[0].(map[string]any)["productTitle"] != "Ijebu Garri" ||
		items[0].(map[string]any)["status"] != "active" {
		t.Fatalf("alerts = %v", items)
	}
	mustStatus(t, b.do("DELETE", "/v1/account/stock-alerts/ATGARIV001", nil), 204)
	mustStatus(t, b.do("DELETE", "/v1/account/stock-alerts/ATGARIV001", nil), 404)
}
