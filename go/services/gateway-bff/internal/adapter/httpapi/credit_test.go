package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestCreditAndGiftCardRoutesConformAndNeverMoveCreditOut drives every credit and gift card operation, with the
// answers a shopper can get into, through the real handlers over the fake payments and orders, and validates each
// response against contracts/openapi/gateway-bff.yaml (S320, S321).
func TestCreditAndGiftCardRoutesConformAndNeverMoveCreditOut(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)
	u := bff.Upstreams

	// Guests are sent to sign in.
	mustStatus(t, b.do("GET", "/v1/account/credit", nil), 401)
	mustStatus(t, b.do("GET", "/v1/gift-cards/config", nil), 401)
	mustStatus(t, b.do("POST", "/v1/account/gift-cards/redeem", map[string]any{"code": testkit.GiftCardCode}), 401)
	mustStatus(t, signIn(t, bff, b, "/"), 302)

	// Balance and history: the shopper sees kinds, sources and amounts, never staff reasons or order ids.
	r := b.do("GET", "/v1/account/credit", nil)
	mustStatus(t, r, 200)
	acct := r.json(t)
	entries := acct["entries"].([]any)
	if num(acct, "balanceMinor") != testkit.CreditBalanceMinor || len(entries) != testkit.CreditEntriesTotal || acct["nextBefore"] != nil {
		t.Fatalf("credit account = %s", r.body)
	}
	if strings.Contains(string(r.body), "Internal reason") || strings.Contains(string(r.body), "orderId") || strings.Contains(string(r.body), "actor") {
		t.Fatalf("history leaks internals: %s", r.body)
	}
	first := entries[0].(map[string]any)
	if first["kind"] != "SPEND" || first["source"] != "ORDER" || num(first, "amountMinor") != -5000 {
		t.Fatalf("newest entry = %v", first)
	}
	r = b.do("GET", "/v1/account/credit?limit=2", nil)
	mustStatus(t, r, 200)
	page := r.json(t)
	next, _ := page["nextBefore"].(string)
	if len(page["entries"].([]any)) != 2 || next == "" {
		t.Fatalf("first page = %s", r.body)
	}
	r = b.do("GET", "/v1/account/credit?limit=2&before="+next, nil)
	mustStatus(t, r, 200)
	if rest := r.json(t); len(rest["entries"].([]any)) != 1 || rest["nextBefore"] != nil {
		t.Fatalf("second page = %s", r.body)
	}
	mustStatus(t, b.do("GET", "/v1/account/credit?before=yesterday", nil), 422)

	// Gift cards: limits, my cards, buying (idempotent, validated), confirming.
	r = b.do("GET", "/v1/gift-cards/config", nil)
	mustStatus(t, r, 200)
	if cfg := r.json(t); num(cfg, "minMinor") != 1000 || num(cfg, "maxMinor") != 50000 || len(cfg["presetsMinor"].([]any)) != 4 {
		t.Fatalf("config = %s", r.body)
	}
	mustStatus(t, b.do("GET", "/v1/account/gift-cards", nil), 200)
	buy := map[string]any{"amountMinor": 5000, "recipientEmail": "ama@example.com", "recipientName": "Ama", "senderName": "Kofi",
		"message": "Happy birthday!", "sendAt": "2026-11-01T09:00:00Z", "locale": "en"}
	mustStatus(t, b.do("POST", "/v1/account/gift-cards", buy), 422) // no Idempotency-Key
	b.headers = map[string]string{"Idempotency-Key": "gift-key-00000000001"}
	mustStatus(t, b.do("POST", "/v1/account/gift-cards", map[string]any{"amountMinor": 500, "recipientEmail": "ama@example.com"}), 422)
	r = b.do("POST", "/v1/account/gift-cards", map[string]any{"amountMinor": 500, "recipientEmail": "ama@example.com"})
	if e, _ := r.json(t)["errors"].(map[string]any); e["amountMinor"] == nil {
		t.Fatalf("amount limits are payments' answer, shown on the field: %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/gift-cards", map[string]any{"amountMinor": 5000, "recipientEmail": "not-an-email"}), 422)
	r = b.do("POST", "/v1/account/gift-cards", buy)
	mustStatus(t, r, 201)
	bought := r.json(t)
	if bought["status"] != "PENDING_PAYMENT" || bought["clientSecret"] != "pi_gift_secret_test" || bought["publishableKey"] != testkit.TestPublishableKey ||
		strings.Contains(string(r.body), "code") {
		t.Fatalf("purchase = %s", r.body)
	}
	id := bought["id"].(string)
	if again := b.do("POST", "/v1/account/gift-cards", buy).json(t); again["id"] != id {
		t.Fatalf("the same Idempotency-Key is the same card: %v", again)
	}
	b.headers = nil
	mustStatus(t, b.do("POST", "/v1/account/gift-cards/0f0f0f0f-0000-4000-8000-0000000000ff/confirm", nil), 404)
	r = b.do("POST", "/v1/account/gift-cards/"+id+"/confirm", nil)
	mustStatus(t, r, 200)
	if r.json(t)["status"] != "ACTIVE" {
		t.Fatalf("confirm = %s", r.body)
	}

	// Redeeming: a wrong code, the rate limit, the right code once.
	mustStatus(t, b.do("POST", "/v1/account/gift-cards/redeem", map[string]any{"code": ""}), 422)
	r = b.do("POST", "/v1/account/gift-cards/redeem", map[string]any{"code": "AAAA-BBBB-CCCC-DDDD"})
	mustStatus(t, r, 404)
	if c := problemCode(t, r); c != "GIFT_CARD_INVALID" {
		t.Fatalf("code = %s", c)
	}
	r = b.do("POST", "/v1/account/gift-cards/redeem", map[string]any{"code": testkit.GiftCardRateLimitedCode})
	mustStatus(t, r, 429)
	if r.header.Get("Retry-After") == "" || problemCode(t, r) != "RATE_LIMITED" {
		t.Fatalf("rate limit = %d %v %s", r.status, r.header, r.body)
	}
	r = b.do("POST", "/v1/account/gift-cards/redeem", map[string]any{"code": strings.ToLower(testkit.GiftCardCode)})
	mustStatus(t, r, 200)
	if red := r.json(t); num(red, "redeemedMinor") != testkit.CreditGiftCardMinor || num(red, "balanceMinor") != testkit.CreditBalanceMinor+testkit.CreditGiftCardMinor {
		t.Fatalf("redemption = %s", r.body)
	}
	r = b.do("POST", "/v1/account/gift-cards/redeem", map[string]any{"code": testkit.GiftCardCode})
	mustStatus(t, r, 409)
	if c := problemCode(t, r); c != "GIFT_CARD_REDEEMED" {
		t.Fatalf("code = %s", c)
	}

	// The card part of an order's refunds as credit: one way; nothing to choose when credit paid it all.
	r = b.do("POST", "/v1/account/orders/"+testkit.ShopperOrderNumber+"/credit-refunds", nil)
	mustStatus(t, r, 200)
	if ch := r.json(t); ch["cardRefundsAsCredit"] != true || num(ch, "cardPartMinor") != 1353 || ch["orderNumber"] != testkit.ShopperOrderNumber {
		t.Fatalf("choice = %s", r.body)
	}
	r = b.do("POST", "/v1/account/orders/"+testkit.CreditOnlyOrderNumber+"/credit-refunds", nil)
	mustStatus(t, r, 409)
	if c := problemCode(t, r); c != "NO_CARD_PORTION" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, b.do("POST", "/v1/account/orders/AT-2026-NOPE/credit-refunds", nil), 404)

	// The order shows what credit paid.
	r = b.do("GET", "/v1/account/orders/"+testkit.ShopperOrderNumber, nil)
	mustStatus(t, r, 200)
	if pay := r.json(t)["payment"].(map[string]any); num(pay, "creditMinor") != 500 || pay["cardRefundsAsCredit"] != false {
		t.Fatalf("order payment = %v", pay)
	}

	// Checkout: the page previews what credit pays, and an order that credit pays in full needs no payment method.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 1}), 200)
	r = b.do("GET", "/v1/checkout/summary", nil)
	mustStatus(t, r, 200)
	sum := r.json(t)
	credit, _ := sum["credit"].(map[string]any)
	total := num(sum, "totalMinor")
	wantApplied := min(testkit.CreditBalanceMinor, total)
	if credit == nil || num(credit, "availableMinor") != testkit.CreditBalanceMinor || num(credit, "appliedMinor")+num(credit, "cardDueMinor") != total ||
		(total-wantApplied >= 50 && num(credit, "appliedMinor") != wantApplied) {
		t.Fatalf("checkout credit = %v of total %d", credit, total)
	}
	pkgID := sum["packages"].([]any)[0].(map[string]any)["packageId"]
	b.headers = map[string]string{"Idempotency-Key": "checkout-credit-0000001"}
	mustStatus(t, b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "addr-1",
		"deliverySelections": []map[string]any{{"packageId": pkgID, "option": "carrier"}}}), 422) // no payment source, no credit
	r = b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "addr-1", "useCredit": true,
		"deliverySelections": []map[string]any{{"packageId": pkgID, "option": "carrier"}}})
	mustStatus(t, r, 201)
	conf := r.json(t)
	if conf["paymentClientSecret"] != nil || num(conf, "creditMinor") != 1250 {
		t.Fatalf("a credit order has no client secret: %s", r.body)
	}
	if sent := u.LastCheckout(); sent["useCredit"] != true || sent["paymentMethodId"] != nil || sent["wallet"] != nil {
		t.Fatalf("orders checkout body: %v", sent)
	}
	b.headers = nil

	// Nothing in the API turns credit into cash: no route to withdraw, pay out, refund to a card or transfer it.
	for _, p := range []string{"/v1/account/credit/withdraw", "/v1/account/credit/payout", "/v1/account/credit/transfer", "/v1/account/credit/refund"} {
		if s := b.do("POST", p, map[string]any{"amountMinor": 100}).status; s != 404 && s != 405 {
			t.Fatalf("POST %s answered %d: there must be no such route", p, s)
		}
	}

	for _, op := range []string{"getAccountCredit", "chooseCreditRefunds", "getGiftCardConfig", "listMyGiftCards", "buyGiftCard", "confirmGiftCard",
		"redeemGiftCard"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
