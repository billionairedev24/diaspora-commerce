package httpapi_test

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"
)

// payoutAtStripe fakes one in-transit payout request (pay-2) and what Stripe says about it. The shared fake platform
// answers everything else (seller, account, compliance).
type payoutAtStripe struct {
	mu          sync.Mutex
	status      string // our record
	stripe      string // what Stripe says
	syncCalls   int
	syncCloseTo string
}

func (p *payoutAtStripe) override(w http.ResponseWriter, r *http.Request, _ map[string]any) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	path := r.URL.Path
	switch {
	case path == "/v1/payouts/requests/pay-2/sync" && r.Method == http.MethodPost:
		p.syncCalls++
		if p.syncCloseTo != "" {
			p.status = p.syncCloseTo
		}
		writeJ(w, 200, p.record())
	case path == "/v1/payouts/requests/pay-2":
		writeJ(w, 200, p.record())
	case path == "/v1/stripe/lookup" && r.URL.Query().Get("ref") == "po_pay2":
		writeJ(w, 200, map[string]any{"ref": "po_pay2", "object": map[string]any{"id": "po_pay2", "kind": "Payout", "status": p.stripe, "amountMinor": 50000, "links": map[string]any{}},
			"journals": []any{}, "connectedAccount": "acct_1"})
	default:
		return false
	}
	return true
}

func (p *payoutAtStripe) record() map[string]any {
	return map[string]any{"id": "pay-2", "orgId": "org-1", "amountMinor": 50000, "status": p.status, "stripePayoutId": "po_pay2", "requestedAt": "2026-10-20T10:00:00Z"}
}

func payoutJSON(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatalf("not JSON: %v: %s", err, body)
	}
	return out
}

// attention is the "Needs attention" field of the sheet's "Live from Stripe" section, or "".
func payoutAttention(sheet map[string]any) string {
	for _, s := range sheet["sections"].([]any) {
		sec := s.(map[string]any)
		if sec["title"] != "Live from Stripe" {
			continue
		}
		for _, f := range sec["fields"].([]any) {
			if fm := f.(map[string]any); fm["label"] == "Needs attention" {
				return fm["value"].(string)
			}
		}
	}
	return ""
}

func payoutAction(sheet map[string]any, key string) map[string]any {
	for _, a := range sheet["actions"].([]any) {
		if m := a.(map[string]any); m["key"] == key {
			return m
		}
	}
	return nil
}

// A payout that Stripe has already closed but that still shows "on the way" is flagged on its sheet, and "Check with
// Stripe" (payouts:approve, only while in transit) asks payments to sync it.
func TestPayoutStripeMismatchFlagAndCheckWithStripeAction(t *testing.T) {
	p := &payoutAtStripe{status: "IN_TRANSIT", stripe: "paid", syncCloseTo: "PAID"}
	f := &fakePlatform{payoutSt: "REQUESTED", override: p.override}
	raw := staffBFF(t, f)
	get := func(token string) map[string]any {
		t.Helper()
		rec := raw(token, "GET", "/v1/staff/resources/payouts/pay-2", nil)
		if rec.Code != 200 {
			t.Fatalf("GET pay-2 as %s = %d: %s", token, rec.Code, rec.Body.String())
		}
		return payoutJSON(t, rec.Body.Bytes())
	}

	// Stripe says paid, we say in transit: flagged, with the way out named.
	sheet := get("admin")
	if note := payoutAttention(sheet); !strings.Contains(note, "Stripe says this payout was paid") || !strings.Contains(note, "Check with Stripe") {
		t.Fatalf("needs-attention note = %q", note)
	}
	check := payoutAction(sheet, "sync-stripe")
	if check == nil || check["label"] != "Check with Stripe" || check["allowed"] != true || check["permission"] != "payouts:approve" {
		t.Fatalf("sync-stripe = %v", check)
	}
	if !strings.Contains(check["confirm"].(string), "closed to match") {
		t.Fatalf("confirm text = %v", check["confirm"])
	}

	// Stripe failed it: the note says the money goes back to the seller.
	p.mu.Lock()
	p.stripe = "failed"
	p.mu.Unlock()
	if note := payoutAttention(get("admin")); !strings.Contains(note, "this payout failed") || !strings.Contains(note, "give the seller the money back") {
		t.Fatalf("failed note = %q", note)
	}

	// Our paid, Stripe not: check the dashboard instead.
	p.mu.Lock()
	p.status, p.stripe = "PAID", "pending"
	p.mu.Unlock()
	sheet = get("admin")
	if note := payoutAttention(sheet); !strings.Contains(note, "We show this payout as paid, but Stripe says pending") {
		t.Fatalf("paid mismatch note = %q", note)
	}
	// Closed payouts cannot be checked again (the action explains why).
	if a := payoutAction(sheet, "sync-stripe"); a["allowed"] != false || !strings.Contains(a["reason"].(string), "Not possible while paid") {
		t.Fatalf("sync-stripe on a paid payout = %v", a)
	}

	// Both sides agree: nothing to flag.
	p.mu.Lock()
	p.status, p.stripe = "IN_TRANSIT", "in_transit"
	p.mu.Unlock()
	if note := payoutAttention(get("admin")); note != "" {
		t.Fatalf("agreeing payout flagged: %q", note)
	}

	// Support may look but not act; the server refuses it too.
	if a := payoutAction(get("support"), "sync-stripe"); a == nil || a["allowed"] != false {
		t.Fatalf("support sync-stripe = %v", a)
	}
	if rec := raw("support", "POST", "/v1/staff/resources/payouts/pay-2/actions/sync-stripe", nil); rec.Code != 403 {
		t.Fatalf("support POST = %d: %s", rec.Code, rec.Body.String())
	}
	if p.syncCalls != 0 {
		t.Fatalf("payments was asked to sync %d time(s) before an allowed request", p.syncCalls)
	}

	// Running it asks payments to sync; the payout closes to match.
	p.mu.Lock()
	p.stripe = "paid"
	p.mu.Unlock()
	rec := raw("admin", "POST", "/v1/staff/resources/payouts/pay-2/actions/sync-stripe", nil)
	if rec.Code != 200 {
		t.Fatalf("admin POST = %d: %s", rec.Code, rec.Body.String())
	}
	if p.syncCalls != 1 || payoutJSON(t, rec.Body.Bytes())["status"] != "PAID" {
		t.Fatalf("sync calls = %d, answer = %s", p.syncCalls, rec.Body.String())
	}
	// A webhook already closed it: pressing the button again reports where it stands instead of failing.
	if again := raw("admin", "POST", "/v1/staff/resources/payouts/pay-2/actions/sync-stripe", nil); again.Code != 200 {
		t.Fatalf("second check = %d: %s", again.Code, again.Body.String())
	}
	// A payout that was never sent cannot be checked.
	if rec := raw("admin", "POST", "/v1/staff/resources/payouts/pay-1/actions/sync-stripe", nil); rec.Code != 409 {
		t.Fatalf("check on a requested payout = %d: %s", rec.Code, rec.Body.String())
	}
}
