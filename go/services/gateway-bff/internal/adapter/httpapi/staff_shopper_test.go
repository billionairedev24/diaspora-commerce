package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

const staffSub = "staff-1"

// A staff or platform admin account shops from the storefront as themselves: the cart read, the cart add and the
// checkout all resolve the same customer (their own user id), and the token's staff permissions never reach the
// customer endpoints.
func TestStaffShopperCartAndCheckoutUseTheSameCustomer(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	u := bff.Upstreams
	u.MemberOrgs, u.ActiveOrgID = nil, ""
	u.StaffSubjects = []string{staffSub}
	signInAs(t, bff, b, staffSub)
	if c := u.AccessClaimsOf(staffSub); c["pt"] != "STAFF" {
		t.Fatalf("the test account must be staff, got %v", c)
	}

	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 1}), 200)
	if got := lines(t, b.do("GET", "/v1/cart", nil).json(t)); len(got) != 1 {
		t.Fatalf("cart lines = %v", got)
	}
	r := b.do("GET", "/v1/checkout/summary", nil)
	mustStatus(t, r, 200)
	pkgs, _ := r.json(t)["packages"].([]any)
	if len(pkgs) == 0 {
		t.Fatalf("summary = %s", r.body)
	}
	b.headers = map[string]string{"Idempotency-Key": "staff-shopper-checkout-1"}
	mustStatus(t, b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "addr-1", "newPaymentMethod": true,
		"deliverySelections": []map[string]any{{"packageId": pkgs[0].(map[string]any)["packageId"], "option": "carrier"}}}), 201)
	b.headers = map[string]string{}
	mustStatus(t, b.do("GET", "/v1/account/orders", nil), 200)

	if len(u.CartOwners) == 0 {
		t.Fatal("no cart request was resolved to a customer: the staff cart fell back to a guest cart")
	}
	for _, o := range u.CartOwners {
		if o != "user:"+staffSub {
			t.Fatalf("cart owner = %q, want user:%s (all of %v)", o, staffSub, u.CartOwners)
		}
	}
	if len(u.CheckoutCustomers) != 1 || u.CheckoutCustomers[0] != staffSub {
		t.Fatalf("checkout customer = %v, want [%s]: cart and checkout must use the same customer id", u.CheckoutCustomers, staffSub)
	}
	for _, call := range []string{"GET /v1/cart", "POST /v1/cart/items", "POST /shopper/v1/checkout", "GET /shopper/v1/orders"} {
		if v, ok := u.ActingAsSent(call); !ok || v != "CUSTOMER" {
			t.Errorf("%s: X-Acting-As = %q (sent %v), want CUSTOMER", call, v, ok)
		}
	}
}

// Staff and customers each see only their own cart: acting as a shopper is not a way into someone else's.
func TestStaffShopperCannotSeeAnotherShoppersCart(t *testing.T) {
	bff, staff := setup(t, testkit.Options{})
	u := bff.Upstreams
	u.MemberOrgs, u.ActiveOrgID = nil, ""
	u.StaffSubjects = []string{staffSub}
	customer := newBrowser(t, bff.Handler)
	signInAs(t, bff, staff, staffSub)
	signInAs(t, bff, customer, "cust-2")

	mustStatus(t, customer.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 2}), 200)
	if got := lines(t, staff.do("GET", "/v1/cart", nil).json(t)); len(got) != 0 {
		t.Fatalf("staff see %v from another shopper's cart", got)
	}
	mustStatus(t, staff.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 1}), 200)
	if got := lines(t, customer.do("GET", "/v1/cart", nil).json(t)); len(got) != 1 || got[0]["quantity"] != float64(2) {
		t.Fatalf("customer cart changed by staff: %v", got)
	}
}
