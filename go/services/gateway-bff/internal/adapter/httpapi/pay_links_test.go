package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestPayLinksServeOurPayPageWithoutASession: the late pre-order pay-now page needs no sign-in (the SMS link's signed
// token is the credential), hands out the PaymentIntent client secret only while the order awaits payment, and refuses
// unknown, expired and paid links. Every answer is validated against the contract.
func TestPayLinksServeOurPayPageWithoutASession(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)
	path := "/v1/pay-links/" + testkit.PayLinkOrderNumber

	r := b.do("POST", path+"/lookup", map[string]any{"token": testkit.PayLinkToken})
	mustStatus(t, r, 200)
	v := r.json(t)
	if v["status"] != "awaiting_payment" || v["clientSecret"] != "pi_paylink_secret_test" || v["publishableKey"] != testkit.TestPublishableKey ||
		v["totalMinor"] != float64(4297) || len(v["lines"].([]any)) != 1 {
		t.Fatalf("pay link = %v", v)
	}
	for _, bad := range []map[string]any{{"token": "v1.1.forged"}, {"token": ""}} {
		r = b.do("POST", path+"/lookup", bad)
		mustStatus(t, r, 404)
		if r.json(t)["code"] != "PAY_LINK_NOT_FOUND" {
			t.Fatalf("bad token problem = %s", r.body)
		}
	}
	mustStatus(t, b.do("POST", "/v1/pay-links/AT-2026-OTHER1/lookup", map[string]any{"token": testkit.PayLinkToken}), 404)
	r = b.do("POST", path+"/lookup", map[string]any{"token": testkit.PayLinkExpiredToken})
	mustStatus(t, r, 410)
	if r.json(t)["code"] != "PAY_LINK_EXPIRED" {
		t.Fatalf("expired problem = %s", r.body)
	}

	// After stripe.confirmPayment: paid, and the secret is never handed out again.
	r = b.do("POST", path+"/confirm", map[string]any{"token": testkit.PayLinkToken})
	mustStatus(t, r, 200)
	if v = r.json(t); v["status"] != "paid" || v["clientSecret"] != nil {
		t.Fatalf("confirmed pay link = %v", v)
	}
	r = b.do("POST", path+"/lookup", map[string]any{"token": testkit.PayLinkToken})
	if v = r.json(t); v["status"] != "paid" || v["clientSecret"] != nil {
		t.Fatalf("paid pay link = %v", v)
	}
	if validated["lookupPayLink"] == 0 || validated["confirmPayLink"] == 0 {
		t.Fatalf("validated = %v", validated)
	}
}
