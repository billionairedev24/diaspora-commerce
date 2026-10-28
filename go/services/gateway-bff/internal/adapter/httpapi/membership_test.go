package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestMembershipRoutesConformAndApplyBenefits drives every membership operation through the real handlers over the fake
// payments, validates each response against contracts/openapi/gateway-bff.yaml, and checks the checkout: the upsell for
// a shopper who is not a member and "fee waived: membership" once they join (S323).
func TestMembershipRoutesConformAndApplyBenefits(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)

	// Visitors see the tiers; everything about me needs sign-in.
	r := b.do("GET", "/v1/membership/tiers", nil)
	mustStatus(t, r, 200)
	offer := r.json(t)["tiers"].([]any)
	if len(offer) != 2 || offer[0].(map[string]any)["name"] != "Plus" || num(offer[0].(map[string]any), "monthlyPriceMinor") != testkit.MembershipMonthlyMinor {
		t.Fatalf("offer = %s", r.body)
	}
	mustStatus(t, b.do("GET", "/v1/account/membership", nil), 401)
	mustStatus(t, b.do("POST", "/v1/account/membership/cancel", nil), 401)
	mustStatus(t, b.do("GET", "/v1/cart/membership", nil), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)

	// Not a member: a trial is available, there is no membership, no tier.
	r = b.do("GET", "/v1/account/membership", nil)
	mustStatus(t, r, 200)
	acct := r.json(t)
	if acct["member"] != false || acct["membership"] != nil || acct["trialAvailable"] != true || len(acct["tiers"].([]any)) != 2 {
		t.Fatalf("account = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/membership/cancel", nil), 404)
	mustStatus(t, b.do("POST", "/v1/account/membership/resume", nil), 404)
	mustStatus(t, b.do("PUT", "/v1/account/membership", map[string]any{"tierCode": "plus", "interval": "MONTHLY"}), 422) // no Idempotency-Key

	// The cart and the checkout page: the upsell says what joining would save on this order.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEA0001", "quantity": 3}), 200)
	r = b.do("GET", "/v1/cart/membership", nil)
	mustStatus(t, r, 200)
	cartSav := r.json(t)
	up, _ := cartSav["upsell"].(map[string]any)
	if cartSav["member"] != false || up == nil || up["tierName"] != "Plus" || num(up, "savingMinor") != 599 || num(up, "trialDays") != 14 || len(cartSav["waivers"].([]any)) != 0 {
		t.Fatalf("cart upsell = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/addresses", map[string]any{"name": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd",
		"city": "Houston", "state": "TX", "zip": "77042"}), 201)
	r = b.do("GET", "/v1/checkout/summary", nil)
	mustStatus(t, r, 200)
	sum := r.json(t)
	mem, _ := sum["membership"].(map[string]any)
	totalBefore := num(sum, "totalMinor")
	if mem == nil || mem["upsell"] == nil || num(sum, "membershipWaivedMinor") != 0 || num(sum, "shippingMinor") != 599 {
		t.Fatalf("checkout upsell = %s", r.body)
	}

	// Joining: validated, idempotent, one membership.
	b.headers = map[string]string{"Idempotency-Key": "membership-join-000001"}
	mustStatus(t, b.do("POST", "/v1/account/membership", map[string]any{"tierCode": "", "interval": "WEEKLY"}), 422)
	mustStatus(t, b.do("POST", "/v1/account/membership", map[string]any{"tierCode": "nope", "interval": "MONTHLY"}), 409)
	r = b.do("POST", "/v1/account/membership", map[string]any{"tierCode": testkit.MembershipDeclinedTier, "interval": "MONTHLY"})
	mustStatus(t, r, 422)
	if c := problemCode(t, r); c != "MEMBERSHIP_CHARGE_FAILED" {
		t.Fatalf("declined card code = %s", c)
	}
	r = b.do("POST", "/v1/account/membership", map[string]any{"tierCode": "plus", "interval": "MONTHLY"})
	mustStatus(t, r, 201)
	joined := r.json(t)
	m := joined["membership"].(map[string]any)
	if joined["member"] != true || m["status"] != "TRIALING" || m["tierName"] != "Plus" || strings.Contains(string(r.body), "periodStart") {
		t.Fatalf("joined = %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/membership", map[string]any{"tierCode": "plus", "interval": "MONTHLY"}), 201) // same key: same result

	// Fee waived: membership. The fee stays as priced, the total is lower, the waiver names the seller and the benefit.
	r = b.do("GET", "/v1/checkout/summary", nil)
	mustStatus(t, r, 200)
	sum = r.json(t)
	mem, _ = sum["membership"].(map[string]any)
	if num(sum, "membershipWaivedMinor") != 599 || num(sum, "shippingMinor") != 599 || num(sum, "totalMinor") != totalBefore-599 ||
		mem == nil || mem["member"] != true || mem["tierName"] != "Plus" || mem["upsell"] != nil {
		t.Fatalf("checkout as a member = %s", r.body)
	}
	if w := mem["waivers"].([]any); len(w) != 1 || w[0].(map[string]any)["seller"] != "Kente Naturals" || w[0].(map[string]any)["benefit"] != "FREE_SHIPPING" {
		t.Fatalf("waivers = %v", w)
	}
	if pk := sum["packages"].([]any)[0].(map[string]any); num(pk, "membershipWaivedMinor") != 599 {
		t.Fatalf("package waiver = %v", pk)
	}
	r = b.do("GET", "/v1/cart/membership", nil)
	mustStatus(t, r, 200)
	if cs := r.json(t); cs["member"] != true || cs["tierName"] != "Plus" || num(cs, "totalWaivedMinor") != 599 || cs["upsell"] != nil {
		t.Fatalf("cart as a member = %s", r.body)
	}
	// A small order stays below the order minimum: nothing is waived and there is nothing to sell.
	mustStatus(t, b.do("PATCH", "/v1/cart/items/ATSHEAV001", map[string]any{"quantity": 1}), 200)
	if cs := b.do("GET", "/v1/cart/membership", nil).json(t); num(cs, "totalWaivedMinor") != 0 || len(cs["waivers"].([]any)) != 0 {
		t.Fatalf("a small order is not waived: %v", cs)
	}
	b.headers = map[string]string{"Idempotency-Key": "membership-join-000002"}
	if c := problemCode(t, b.do("POST", "/v1/account/membership", map[string]any{"tierCode": "plus", "interval": "YEARLY"})); c != "MEMBERSHIP_EXISTS" {
		t.Fatalf("second join code = %s", c)
	}

	// Changing plan: a lower or other plan is scheduled; naming the current plan takes it back.
	b.headers = map[string]string{"Idempotency-Key": "membership-change-00001"}
	r = b.do("PUT", "/v1/account/membership", map[string]any{"tierCode": "premium", "interval": "MONTHLY"})
	mustStatus(t, r, 200)
	if pt, _ := r.json(t)["pendingTier"].(map[string]any); pt == nil || pt["name"] != "Premium" {
		t.Fatalf("pending tier = %s", r.body)
	}
	b.headers = map[string]string{"Idempotency-Key": "membership-change-00002"}
	mustStatus(t, b.do("PUT", "/v1/account/membership", map[string]any{"tierCode": "plus", "interval": "MONTHLY"}), 200)
	b.headers = map[string]string{"Idempotency-Key": "membership-change-00003"}
	mustStatus(t, b.do("PUT", "/v1/account/membership", map[string]any{"tierCode": "plus", "interval": "MONTHLY"}), 409) // nothing to change
	b.headers = nil

	// Cancel keeps the benefits to the end of the period; the refund rule can say no; resume takes it back.
	r = b.do("POST", "/v1/account/membership/cancel", map[string]any{"refund": true})
	mustStatus(t, r, 409)
	if c := problemCode(t, r); c != "MEMBERSHIP_REFUND_NOT_ELIGIBLE" {
		t.Fatalf("refund code = %s", c)
	}
	r = b.do("POST", "/v1/account/membership/cancel", nil)
	mustStatus(t, r, 200)
	if c := r.json(t)["membership"].(map[string]any); c["cancelAtPeriodEnd"] != true || r.json(t)["member"] != true {
		t.Fatalf("canceled = %s", r.body)
	}
	r = b.do("POST", "/v1/account/membership/resume", nil)
	mustStatus(t, r, 200)
	if r.json(t)["membership"].(map[string]any)["cancelAtPeriodEnd"] != false {
		t.Fatalf("resumed = %s", r.body)
	}

	// Benefits used: names and amounts, never ids.
	r = b.do("GET", "/v1/account/membership/benefits?limit=1", nil)
	mustStatus(t, r, 200)
	if items := r.json(t)["items"].([]any); len(items) != 1 || items[0].(map[string]any)["benefit"] != "CASHBACK" {
		t.Fatalf("benefits = %s", r.body)
	}
	mustStatus(t, b.do("GET", "/v1/account/membership/benefits", nil), 200)

	// The order shows the fee the membership waived, with the tier and the benefit (names, no ids).
	r = b.do("GET", "/v1/account/orders/"+testkit.ShopperOrderNumber, nil)
	mustStatus(t, r, 200)
	ship := r.json(t)["shipments"].([]any)[0].(map[string]any)
	if w, _ := ship["membershipWaiver"].(map[string]any); w == nil || w["tierName"] != "Plus" || w["benefit"] != "FREE_SHIPPING" ||
		num(w, "waivedMinor") != testkit.MembershipWaivedOnOrderMinor || w["tierCode"] != nil {
		t.Fatalf("order waiver = %v", ship["membershipWaiver"])
	}

	for _, op := range []string{"getMembershipOffer", "getAccountMembership", "joinAccountMembership", "changeAccountMembership",
		"cancelAccountMembership", "resumeAccountMembership", "listAccountMembershipBenefits", "getCartMembershipSavings"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
