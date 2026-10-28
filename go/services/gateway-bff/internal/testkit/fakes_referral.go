package testkit

import (
	"net/http"
	"strconv"
)

// Referral fixtures (S322): the fake shopper has a code, one friend who earned them credit and one still waiting.
const (
	// ReferralCode is the fake shopper's code in storage form; ReferralDisplayCode is how it is shown.
	ReferralCode        = "KDM7XR3Q"
	ReferralDisplayCode = "KDM7-XR3Q"
	// ReferralEarnedMinor is the credit the fake shopper earned from the rewarded friend.
	ReferralEarnedMinor = 1000
)

// referralRoutes serves payments' referral routes for the shopper fakes. Payments answers what a customer may see: the
// fake leaves out flags, staff decisions and ids of other customers, as the real service does.
func (u *Upstreams) referralRoutes(mux *http.ServeMux) {
	reward := func(granted bool) map[string]any {
		out := map[string]any{"terms": map[string]any{"type": "CREDIT", "creditMinor": 1000, "membershipDays": 0}, "granted": granted, "grantedAt": nil}
		if granted {
			out["grantedAt"] = "2026-10-20T10:00:00Z"
		}
		return out
	}
	friends := []map[string]any{
		{"id": "7a1b2c3d-0000-4000-8000-000000000001", "friendName": "Amara O.", "status": "REWARDED", "signedUpAt": "2026-09-01T10:00:00Z",
			"expiresAt": "2026-11-30T10:00:00Z", "eligibleAt": "2026-10-20T10:00:00Z", "reward": reward(true)},
		{"id": "7a1b2c3d-0000-4000-8000-000000000002", "friendName": "Kofi B.", "status": "WAITING", "signedUpAt": "2026-10-05T10:00:00Z",
			"expiresAt": "2027-01-03T10:00:00Z", "eligibleAt": "2026-11-12T10:00:00Z", "reward": reward(false)},
		{"id": "7a1b2c3d-0000-4000-8000-000000000003", "friendName": "Zainab K.", "status": "PENDING", "signedUpAt": "2026-10-20T10:00:00Z",
			"expiresAt": "2027-01-18T10:00:00Z", "eligibleAt": nil, "reward": nil},
	}
	mux.HandleFunc("GET /v1/referrals/me", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"code": ReferralCode, "displayCode": ReferralDisplayCode,
			"program": map[string]any{"enabled": true,
				"referrerReward": map[string]any{"type": "CREDIT", "creditMinor": 1000, "membershipDays": 0},
				"referredReward": map[string]any{"type": "CREDIT", "creditMinor": 1000, "membershipDays": 0},
				"minOrderMinor":  3000, "returnWindowDays": 30, "validityDays": 90, "currency": "USD"},
			"earnings": map[string]any{"invited": 3, "open": 2, "rewarded": 1, "earnedMinor": ReferralEarnedMinor, "membershipDays": 0, "pendingMinor": 1000},
			"asFriend": map[string]any{"id": "7a1b2c3d-0000-4000-8000-000000000009", "friendName": nil, "status": "WAITING",
				"signedUpAt": "2026-08-01T10:00:00Z", "expiresAt": "2026-10-30T10:00:00Z", "eligibleAt": "2026-11-02T10:00:00Z", "reward": reward(false)}})
	}))
	mux.HandleFunc("GET /v1/referrals/me/friends", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > len(friends) {
			limit = len(friends)
		}
		writeJSON(w, 200, friends[:limit])
	}))
}
