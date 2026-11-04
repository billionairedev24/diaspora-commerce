package httpapi_test

import (
	"fmt"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// A person who signed up as a customer, went through "Sell on AfroTransact" and was approved: identity's token now
// carries pt SELLER_MEMBER and their organisation, whichever app asked for it (one gateway-bff client). The
// storefront still lets them shop as themselves: every storefront route relays X-Acting-As: CUSTOMER, so payments,
// orders, cart and bookings (which only serve shoppers) answer for them. Seller Center routes keep the organisation.
func TestDualRoleSellerShopsAsThemselvesOnTheStorefront(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	u := bff.Upstreams
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	if c := u.AccessClaimsOf("user-1"); c["pt"] != "SELLER_MEMBER" || c["org"] != testkit.SellerOrgID {
		t.Fatalf("the test account must be an approved seller (org-scoped token), got %v", c)
	}

	// Cart: the line lands in the person's own cart (not a throwaway guest cart) and stays there.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 1}), 200)
	if got := lines(t, b.do("GET", "/v1/cart", nil).json(t)); len(got) != 1 {
		t.Fatalf("cart lines = %v", got)
	}
	// Account: me (with payment method counts and the back offices for the account menu), addresses, payment methods.
	me := b.do("GET", "/v1/me", nil).json(t)
	if counts, _ := me["counts"].(map[string]any); counts == nil || num(counts, "paymentMethods") == 0 {
		t.Fatalf("me counts = %v", me)
	}
	if got := fmt.Sprint(me["user"].(map[string]any)["backOffices"]); got != "[map[app:seller-center state:active] map[app:provider-hub state:active]]" {
		t.Fatalf("back offices of an approved seller and provider = %s", got)
	}
	mustStatus(t, b.do("POST", "/v1/account/addresses", map[string]any{"name": "Adaeze Nwosu", "phone": "+17135550142",
		"line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "zip": "77042", "isDefault": true}), 201)
	mustStatus(t, b.do("GET", "/v1/account/addresses", nil), 200)
	mustStatus(t, b.do("GET", "/v1/account/payment-methods", nil), 200)
	mustStatus(t, b.do("POST", "/v1/account/payment-methods/setup-intents", nil), 201)
	// Checkout: the summary (the reported "Checkout is unavailable" 403) and placing the order.
	r := b.do("GET", "/v1/checkout/summary", nil)
	mustStatus(t, r, 200)
	pkgs, _ := r.json(t)["packages"].([]any)
	if len(pkgs) == 0 {
		t.Fatalf("summary = %s", r.body)
	}
	pkg := pkgs[0].(map[string]any)
	b.headers = map[string]string{"Idempotency-Key": "dual-role-checkout-0001"}
	mustStatus(t, b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "addr-1", "newPaymentMethod": true,
		"deliverySelections": []map[string]any{{"packageId": pkg["packageId"], "option": "carrier"}}}), 201)
	b.headers = map[string]string{}
	// Orders, bookings as a customer.
	mustStatus(t, b.do("GET", "/v1/account/orders", nil), 200)
	mustStatus(t, b.do("GET", "/v1/account/orders/"+testkit.ShopperOrderNumber, nil), 200)
	mustStatus(t, b.do("POST", "/v1/services/bookings", map[string]any{"serviceId": testkit.ShopperServiceID,
		"startAt": "2026-10-20T15:00:00Z", "mode": "IN_PERSON"}), 201)
	mustStatus(t, b.do("GET", "/v1/account/bookings", nil), 200)

	for _, call := range []string{
		"POST /v1/cart/items", "GET /v1/cart", "GET /v1/me/addresses", "GET /v1/customers/me/payment-methods",
		"POST /shopper/v1/customers/me/setup-intents", "POST /shopper/v1/checkout", "GET /shopper/v1/orders",
		"GET /shopper/v1/orders/" + testkit.ShopperOrderNumber, "POST /shopper/v1/bookings", "GET /shopper/v1/bookings/mine",
	} {
		if v, ok := u.ActingAsSent(call); !ok || v != "CUSTOMER" {
			t.Errorf("%s: X-Acting-As = %q (sent %v), want CUSTOMER", call, v, ok)
		}
	}

	// Seller Center: the same session acts for the organisation (no acting context).
	mustStatus(t, b.do("GET", "/v1/seller/session", nil), 200)
	if v, ok := u.ActingAsSent("GET /v1/orgs/" + testkit.SellerOrgID); !ok || v != "" {
		t.Fatalf("Seller Center must relay the organisation context, got X-Acting-As %q (sent %v)", v, ok)
	}
}

// The account menu's back offices follow the person's organisations: an application in review continues onboarding,
// and a shopper with no organisation has none.
func TestMeBackOfficesFollowTheOrganisations(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	u := bff.Upstreams
	u.MemberOrgs = []testkit.MemberOrg{{ID: testkit.PendingOrgID, Kind: "SELLER", Name: "Malo Branding", Status: "PENDING", Role: "seller.owner"}}
	u.ActiveOrgID = testkit.PendingOrgID
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	user := b.do("GET", "/v1/me", nil).json(t)["user"].(map[string]any)
	if got := fmt.Sprint(user["backOffices"]); got != "[map[app:seller-center state:application]]" {
		t.Fatalf("application in review: back offices = %s", got)
	}

	bff, b = setup(t, testkit.Options{})
	bff.Upstreams.MemberOrgs, bff.Upstreams.ActiveOrgID = nil, ""
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	user = b.do("GET", "/v1/me", nil).json(t)["user"].(map[string]any)
	if got, ok := user["backOffices"].([]any); !ok || len(got) != 0 {
		t.Fatalf("shopper only: back offices = %v, want []", user["backOffices"])
	}
}
