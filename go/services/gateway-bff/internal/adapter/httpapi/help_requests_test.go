package httpapi_test

import (
	"net/url"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

const helpKey1 = "help-key-0000001"

func helpBody(over map[string]any) map[string]any {
	m := map[string]any{"orderNumber": testkit.ShopperOrderNumber, "shipmentId": testkit.ShopperShipmentID, "type": "DAMAGED_ITEM",
		"description": "The seasoning jar arrived cracked and leaking.", "evidenceAssetIds": []string{}}
	for k, v := range over {
		m[k] = v
	}
	return m
}

func withKey(b *browser, key string, fn func()) {
	if key != "" {
		b.headers["Idempotency-Key"] = key
	}
	defer delete(b.headers, "Idempotency-Key")
	fn()
}

// exerciseHelpRequests drives every help-request operation through the handlers (the caller validates the responses
// against the contract) as the signed-in customer.
func exerciseHelpRequests(t *testing.T, b *browser, u *testkit.Upstreams) {
	t.Helper()
	before := len(u.OpenedClaims())

	// Evidence uploads: signed, direct to media; only photos up to 10 MB.
	r := b.do("POST", "/v1/me/help-requests/uploads", map[string]any{"fileName": "cracked.heic", "contentType": "image/heic", "size": 2_000_000})
	mustStatus(t, r, 201)
	if up := r.json(t); up["assetId"] != "asset-1" || num(up, "maxBytes") != 10<<20 || u.LastUploadPurpose != "return-photo" {
		t.Fatalf("upload instructions: %s (purpose %s)", r.body, u.LastUploadPurpose)
	}
	mustStatus(t, b.do("POST", "/v1/me/help-requests/uploads", map[string]any{"fileName": "invoice.pdf", "contentType": "application/pdf", "size": 1000}), 422)
	mustStatus(t, b.do("POST", "/v1/me/help-requests/uploads", map[string]any{"fileName": "huge.png", "contentType": "image/png", "size": 11 << 20}), 422)
	mustStatus(t, b.do("POST", "/v1/me/help-requests/uploads/asset-1/complete", nil), 200)
	mustStatus(t, b.do("POST", "/v1/me/help-requests/uploads/"+testkit.MissingAssetID+"/complete", nil), 404) // never requested by this customer

	// Opening: validated before anything leaves the BFF.
	withKey(b, helpKey1, func() {
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"description": "short"})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"type": "REFUND_ME"})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"type": "BOOKING_NO_SHOW"})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"orderNumber": ""})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"evidenceAssetIds": []string{"a", "b", "c", "d", "e", "f"}})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"evidenceAssetIds": []string{"asset-1", "asset-1"}})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"evidenceAssetIds": []string{"asset-from-someone-else"}})), 422)
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"shipmentId": "not-in-this-order"})), 422)
	})
	mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(nil)), 422) // no Idempotency-Key
	// Ownership: an order the customer does not own reads as not found and never reaches trust-ops.
	withKey(b, helpKey1, func() {
		mustStatus(t, b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"orderNumber": "AT-2026-OTHER"})), 404)
	})
	if n := len(u.OpenedClaims()); n != before {
		t.Fatalf("rejected requests must not open claims, got %d new", n-before)
	}

	var created map[string]any
	withKey(b, helpKey1, func() {
		r = b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"evidenceAssetIds": []string{"asset-1"}}))
		mustStatus(t, r, 201)
		created = r.json(t)
		// A retry with the same key gives back the same request.
		again := b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"evidenceAssetIds": []string{"asset-1"}}))
		mustStatus(t, again, 201)
		if again.json(t)["id"] != created["id"] {
			t.Fatalf("replay opened another request: %s vs %s", again.body, r.body)
		}
	})
	claims := u.OpenedClaims()
	if len(claims) != before+1 {
		t.Fatalf("one claim expected, got %d", len(claims)-before)
	}
	sent := claims[before]
	// Amount, currency, order and seller come from the order the customer owns, not from the request.
	if sent["amountMinor"] != float64(2500) || sent["currency"] != "USD" || sent["orderId"] != testkit.ShopperOrderID ||
		sent["sellerOrgId"] != testkit.ShopperSellerOrgID || sent["shipmentId"] != testkit.ShopperShipmentID || sent["type"] != "DAMAGED_ITEM" {
		t.Fatalf("claim sent to trust-ops: %v", sent)
	}
	if created["status"] != "RECEIVED" || created["reference"] == "" || num(created, "evidenceCount") != 1 || created["resolution"] != nil ||
		created["orderNumber"] != testkit.ShopperOrderNumber {
		t.Fatalf("created request: %v", created)
	}
	assertCustomerSafe(t, created)

	// Listing and reading.
	r = b.do("GET", "/v1/me/help-requests?limit=10", nil)
	mustStatus(t, r, 200)
	if items := r.json(t)["items"].([]any); len(items) != before+1 || items[0].(map[string]any)["id"] != created["id"] {
		t.Fatalf("list: %s", r.body)
	}
	mustStatus(t, b.do("GET", "/v1/me/help-requests?limit=0", nil), 422)
	mustStatus(t, b.do("GET", "/v1/me/help-requests?cursor=abc", nil), 422)
	r = b.do("GET", "/v1/me/help-requests/"+created["id"].(string), nil)
	mustStatus(t, r, 200)
	mustStatus(t, b.do("GET", "/v1/me/help-requests/0e0e0e0e-0000-4000-8000-00000000ffff", nil), 404)

	// Staff resolve it in the claims tooling; the customer sees the outcome and the staff note, never who decided.
	u.DecideClaim(created["id"].(string), "REFUND", "Sorry about the damage. We refunded the seasoning.", 2500)
	r = b.do("GET", "/v1/me/help-requests/"+created["id"].(string), nil)
	mustStatus(t, r, 200)
	got := r.json(t)
	res, _ := got["resolution"].(map[string]any)
	if got["status"] != "RESOLVED" || res == nil || res["outcome"] != "REFUNDED" || num(res, "refundMinor") != 2500 ||
		res["note"] != "Sorry about the damage. We refunded the seasoning." {
		t.Fatalf("resolved request: %s", r.body)
	}
	assertCustomerSafe(t, got)

	// A request staff turn down reads as closed with their reason.
	var second map[string]any
	withKey(b, "help-key-0000002", func() {
		r = b.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"type": "OTHER", "shipmentId": ""}))
		mustStatus(t, r, 201)
		second = r.json(t)
	})
	if sent := u.OpenedClaims()[before+1]; sent["amountMinor"] != float64(2706) || sent["shipmentId"] != nil {
		t.Fatalf("order-wide claim: %v", sent)
	}
	u.DecideClaim(second["id"].(string), "DENY", "The photos show normal wear.", 0)
	r = b.do("GET", "/v1/me/help-requests/"+second["id"].(string), nil)
	mustStatus(t, r, 200)
	if got := r.json(t); got["status"] != "CLOSED" || got["resolution"].(map[string]any)["outcome"] != "NOT_REFUNDED" || got["resolution"].(map[string]any)["refundMinor"] != nil {
		t.Fatalf("denied request: %s", r.body)
	}
}

// assertCustomerSafe fails when a help request carries internal ids or staff and seller identities.
func assertCustomerSafe(t *testing.T, m map[string]any) {
	t.Helper()
	for _, k := range []string{"orderId", "customerId", "sellerOrgId", "shipmentId", "decidedBy", "refundId", "version", "sla", "evidence"} {
		if _, found := m[k]; found {
			t.Fatalf("help request leaks %q: %v", k, m)
		}
	}
	for _, v := range m {
		if s, ok := v.(string); ok && (strings.Contains(s, "staff-jane") || strings.Contains(s, testkit.ShopperOrderID)) {
			t.Fatalf("help request leaks an internal value: %v", m)
		}
	}
}

func signInAs(t *testing.T, bff *testkit.BFF, b *browser, user string) {
	t.Helper()
	r := b.do("GET", "/v1/auth/login?returnTo=%2F", nil)
	mustStatus(t, r, 302)
	loc, err := url.Parse(r.header.Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	q := loc.Query()
	code := bff.Upstreams.IssueCode(q.Get("code_challenge"), q.Get("nonce"), user, testkit.RedirectURI)
	mustStatus(t, b.do("GET", "/v1/auth/oidc/callback?code="+url.QueryEscape(code)+"&state="+url.QueryEscape(q.Get("state")), nil), 302)
}

func TestHelpRequestsAreOnlyVisibleToTheirCustomer(t *testing.T) {
	bff, ada := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	mustStatus(t, ada.do("GET", "/v1/me/help-requests", nil), 401)
	mustStatus(t, ada.do("POST", "/v1/me/help-requests/uploads", map[string]any{"fileName": "a.png", "contentType": "image/png", "size": 10}), 401)
	mustStatus(t, ada.do("GET", "/v1/me/help-requests/0e0e0e0e-0000-4000-8000-000000000001", nil), 401)

	signInAs(t, bff, ada, "user-1")
	var id string
	withKey(ada, helpKey1, func() {
		r := ada.do("POST", "/v1/me/help-requests", helpBody(nil))
		mustStatus(t, r, 201)
		id = r.json(t)["id"].(string)
	})

	bola := newBrowser(t, bff.Handler)
	signInAs(t, bff, bola, "user-2")
	mustStatus(t, bola.do("GET", "/v1/me/help-requests/"+id, nil), 404)
	r := bola.do("GET", "/v1/me/help-requests", nil)
	mustStatus(t, r, 200)
	if items := r.json(t)["items"].([]any); len(items) != 0 {
		t.Fatalf("another customer's requests are listed: %s", r.body)
	}
	// Bola cannot finalise an upload Ada requested, and Ada's key does not replay for Bola.
	mustStatus(t, ada.do("POST", "/v1/me/help-requests/uploads", map[string]any{"fileName": "a.png", "contentType": "image/png", "size": 10}), 201)
	mustStatus(t, bola.do("POST", "/v1/me/help-requests/uploads/asset-1/complete", nil), 404)
	withKey(bola, helpKey1, func() {
		r := bola.do("POST", "/v1/me/help-requests", helpBody(map[string]any{"evidenceAssetIds": []string{"asset-1"}}))
		mustStatus(t, r, 422)
	})
	if n := len(bff.Upstreams.OpenedClaims()); n != 1 {
		t.Fatalf("claims = %d", n)
	}
}
