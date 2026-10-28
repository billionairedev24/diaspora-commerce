package testkit

import (
	"net/http"
	"strconv"
	"sync"
)

// Membership fixtures (S323).
const (
	// MembershipFreeFromMinor is the goods subtotal from which the fake Plus tier waives delivery and shipping fees.
	MembershipFreeFromMinor = 2500
	// MembershipMonthlyMinor and MembershipYearlyMinor are Plus's prices.
	MembershipMonthlyMinor = 999
	MembershipYearlyMinor  = 9900
	// MembershipDeclinedTier is a tier whose card is always declined.
	MembershipDeclinedTier = "declined"
	// MembershipWaivedOnOrderMinor is the shipping fee the membership waived on the fake delivered order.
	MembershipWaivedOnOrderMinor = 599
)

type membershipState struct {
	mu       sync.Mutex
	status   string // "" when not a member
	tier     string
	interval string
	cancel   bool
	pending  string
	joined   map[string]bool // Idempotency-Key → done
}

func fakeMembershipTier(code, name string, factor int) map[string]any {
	return map[string]any{"code": code, "name": name, "description": name + " membership", "status": "PUBLISHED", "sortOrder": factor * 10, "currency": "USD",
		"monthlyPriceMinor": MembershipMonthlyMinor * factor, "yearlyPriceMinor": MembershipYearlyMinor * factor, "trialDays": 14,
		"benefits": []map[string]any{
			{"code": "FREE_DELIVERY", "minOrderMinor": MembershipFreeFromMinor, "capMinor": nil, "percentBps": nil, "hours": nil, "amountMinor": nil},
			{"code": "FREE_SHIPPING", "minOrderMinor": MembershipFreeFromMinor, "capMinor": 1500, "percentBps": nil, "hours": nil, "amountMinor": nil},
			{"code": "CASHBACK", "minOrderMinor": nil, "capMinor": nil, "percentBps": 200, "hours": nil, "amountMinor": nil},
		}}
}

// membershipRoutes serves payments' membership routes for the shopper fakes. The shopper starts as a non-member, so the
// checkout shows the upsell; joining makes the fees of orders of 25.00 or more waived.
func (u *Upstreams) membershipRoutes(mux *http.ServeMux) {
	st := &membershipState{joined: map[string]bool{}}
	tiers := func() []map[string]any {
		return []map[string]any{fakeMembershipTier("plus", "Plus", 1), fakeMembershipTier("premium", "Premium", 2)}
	}
	tierOf := func(code string) map[string]any {
		for _, t := range tiers() {
			if t["code"] == code {
				return t
			}
		}
		return nil
	}
	me := func() map[string]any {
		out := map[string]any{"membership": nil, "tier": nil, "pendingTier": nil, "charges": []any{}, "usage": []any{},
			"refund":         map[string]any{"eligible": false, "reason": "NOT_PAID", "amountMinor": 0, "currency": "USD", "until": nil},
			"trialAvailable": st.status == "", "refundWindowDays": 7,
			"card": map[string]any{"brand": "visa", "last4": "4242", "expMonth": 8, "expYear": 2029}}
		if st.status == "" {
			return out
		}
		price := tierOf(st.tier)["monthlyPriceMinor"].(int)
		if st.interval == "YEARLY" {
			price = tierOf(st.tier)["yearlyPriceMinor"].(int)
		}
		m := map[string]any{"status": st.status, "source": "PAID", "tierCode": st.tier, "interval": st.interval, "priceMinor": price, "currency": "USD",
			"startedAt": "2026-10-20T10:00:00Z", "periodStart": "2026-10-20T10:00:00Z", "periodEnd": "2026-11-03T10:00:00Z", "trialEnd": nil,
			"cancelAtPeriodEnd": st.cancel, "endedAt": nil, "endReason": nil, "pendingTierCode": nil, "pendingInterval": nil, "retryCount": 0, "nextRetryAt": nil,
			"freeDaysGranted": 0}
		if st.status == "TRIALING" {
			m["trialEnd"] = "2026-11-03T10:00:00Z"
		}
		out["membership"], out["tier"] = m, tierOf(st.tier)
		if st.pending != "" {
			m["pendingTierCode"], m["pendingInterval"] = st.pending, st.interval
			out["pendingTier"] = tierOf(st.pending)
		}
		out["usage"] = []map[string]any{{"benefit": "FREE_DELIVERY", "uses": 2, "amountMinor": 1198, "currency": "USD"}}
		out["charges"] = []map[string]any{{"kind": "JOIN", "amountMinor": price, "currency": "USD", "status": "SUCCEEDED", "failureCode": nil,
			"periodStart": "2026-10-20T10:00:00Z", "periodEnd": "2026-11-03T10:00:00Z", "refundedMinor": 0, "createdAt": "2026-10-20T10:00:00Z"}}
		return out
	}
	move := func(w http.ResponseWriter, r *http.Request, join bool) {
		body := readJSON(r)
		code, _ := body["tierCode"].(string)
		interval, _ := body["interval"].(string)
		key := r.Header.Get("Idempotency-Key")
		st.mu.Lock()
		defer st.mu.Unlock()
		if st.joined[key] {
			writeJSON(w, 200, me())
			return
		}
		switch {
		case code == MembershipDeclinedTier:
			problem(w, 422, "payments.membership-charge-failed", "Declined", nil)
		case tierOf(code) == nil:
			problem(w, 409, "payments.membership-tier-unavailable", "That tier is not open", nil)
		case join && st.status != "":
			problem(w, 409, "payments.membership-exists", "Already a member", nil)
		case !join && st.status == "":
			problem(w, 404, "payments.membership-not-found", "Not a member", nil)
		case !join && code == st.tier && interval == st.interval && st.pending == "":
			problem(w, 409, "payments.membership-no-change", "Nothing to change", nil)
		default:
			st.joined[key] = true
			switch {
			case join:
				st.status, st.tier, st.interval = "TRIALING", code, interval
			case code == st.tier && interval == st.interval:
				st.pending = ""
			default:
				st.pending = code
			}
			writeJSON(w, 200, me())
		}
	}
	mux.HandleFunc("GET /v1/memberships/tiers", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); ok {
			writeJSON(w, 200, tiers())
		}
	})
	mux.HandleFunc("GET /v1/memberships/me", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, me())
	}))
	mux.HandleFunc("POST /v1/memberships/me/join", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) { move(w, r, true) }))
	mux.HandleFunc("POST /v1/memberships/me/change", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) { move(w, r, false) }))
	mux.HandleFunc("POST /v1/memberships/me/cancel", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		if st.status == "" {
			problem(w, 404, "payments.membership-not-found", "Not a member", nil)
			return
		}
		if readJSON(r)["refund"] == true {
			problem(w, 409, "payments.membership-refund-not-eligible", "Benefit used", nil)
			return
		}
		st.cancel = true
		writeJSON(w, 200, me())
	}))
	mux.HandleFunc("POST /v1/memberships/me/resume", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		if st.status == "" {
			problem(w, 404, "payments.membership-not-found", "Not a member", nil)
			return
		}
		st.cancel, st.pending = false, ""
		writeJSON(w, 200, me())
	}))
	mux.HandleFunc("GET /v1/memberships/me/uses", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		all := []map[string]any{
			{"benefit": "CASHBACK", "tierCode": "plus", "amountMinor": 54, "currency": "USD", "createdAt": "2026-10-26T10:00:00Z"},
			{"benefit": "FREE_DELIVERY", "tierCode": "plus", "amountMinor": 599, "currency": "USD", "createdAt": "2026-10-25T10:00:00Z"},
		}
		if n, _ := strconv.Atoi(r.URL.Query().Get("limit")); n > 0 && n < len(all) {
			all = all[:n]
		}
		writeJSON(w, 200, all)
	}))
	mux.HandleFunc("POST /v1/memberships/me/savings", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		body := readJSON(r)
		items, _ := body["itemsMinor"].(float64)
		fees, _ := body["fees"].([]any)
		st.mu.Lock()
		member := st.status != ""
		st.mu.Unlock()
		waivers, total := []map[string]any{}, int64(0)
		if items >= MembershipFreeFromMinor {
			for _, f := range fees {
				fee, _ := f.(map[string]any)
				kind, _ := fee["kind"].(string)
				amount, _ := fee["feeMinor"].(float64)
				benefit := map[string]string{"AFROTRANSACT_DELIVERY": "FREE_DELIVERY", "CARRIER": "FREE_SHIPPING"}[kind]
				if benefit == "" || amount <= 0 {
					continue
				}
				waivers = append(waivers, map[string]any{"key": fee["key"], "benefit": benefit, "waivedMinor": int64(amount)})
				total += int64(amount)
			}
		}
		out := map[string]any{"member": member, "tierCode": "", "tierName": "", "waivers": []map[string]any{}, "totalWaivedMinor": 0, "upsell": nil}
		if member {
			out["tierCode"], out["tierName"], out["waivers"], out["totalWaivedMinor"] = "plus", "Plus", waivers, total
		} else if total > 0 {
			out["upsell"] = map[string]any{"tierCode": "plus", "tierName": "Plus", "monthlyPriceMinor": MembershipMonthlyMinor, "yearlyPriceMinor": MembershipYearlyMinor,
				"trialDays": 14, "savingMinor": total, "currency": "USD"}
		}
		writeJSON(w, 200, out)
	}))
}
