package httpapi_test

import (
	"net/url"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// TestReferralPageConformsAndShowsNamesNotIds drives "Refer a friend" through the real handlers over the fake payments
// and validates the response against contracts/openapi/gateway-bff.yaml (S322).
func TestReferralPageConformsAndShowsNamesNotIds(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)

	mustStatus(t, b.do("GET", "/v1/account/referral", nil), 401) // guests sign in first
	mustStatus(t, signIn(t, bff, b, "/"), 302)

	r := b.do("GET", "/v1/account/referral", nil)
	mustStatus(t, r, 200)
	page := r.json(t)
	if page["code"] != testkit.ReferralCode || page["displayCode"] != testkit.ReferralDisplayCode ||
		page["shareUrl"] != testkit.TestStorefrontOrigin+"/r/"+testkit.ReferralCode {
		t.Fatalf("code and link = %s", r.body)
	}
	program := page["program"].(map[string]any)
	referrer := program["referrerReward"].(map[string]any)
	if program["enabled"] != true || num(program, "minOrderMinor") != 3000 || num(program, "returnWindowDays") != 30 ||
		referrer["type"] != "CREDIT" || num(referrer, "creditMinor") != 1000 {
		t.Fatalf("program = %v", program)
	}
	earnings := page["earnings"].(map[string]any)
	if num(earnings, "invited") != 3 || num(earnings, "rewarded") != 1 || num(earnings, "earnedMinor") != testkit.ReferralEarnedMinor {
		t.Fatalf("earnings = %v", earnings)
	}

	// Friends are shown by name, with the status and the shopper's reward; no ids of other customers, flags or reasons.
	friends := page["friends"].([]any)
	if len(friends) != 3 {
		t.Fatalf("friends = %s", r.body)
	}
	first := friends[0].(map[string]any)
	if first["friendName"] != "Amara O." || first["status"] != "REWARDED" || first["reward"].(map[string]any)["granted"] != true {
		t.Fatalf("first friend = %v", first)
	}
	if friends[2].(map[string]any)["reward"] != nil || friends[2].(map[string]any)["eligibleAt"] != nil {
		t.Fatalf("a friend who has not ordered yet has no reward: %v", friends[2])
	}
	for _, leak := range []string{"referrerId", "referredId", "flags", "decision", "decidedBy", "SAME_DEVICE", "IN_REVIEW"} {
		if strings.Contains(string(r.body), leak) {
			t.Fatalf("the shopper's page leaks %q: %s", leak, r.body)
		}
	}
	// The shopper's own referral, when a friend referred them.
	own := page["asFriend"].(map[string]any)
	if own["friendName"] != nil || own["status"] != "WAITING" {
		t.Fatalf("asFriend = %v", own)
	}

	// Nothing here moves credit: there is no route to claim, withdraw or transfer a reward.
	for _, p := range []string{"/v1/account/referral/claim", "/v1/account/referral/withdraw", "/v1/account/referral/rewards"} {
		if s := b.do("POST", p, map[string]any{}).status; s != 404 && s != 405 {
			t.Fatalf("POST %s answered %d: there must be no such route", p, s)
		}
	}
	if validated["getAccountReferral"] == 0 {
		t.Errorf("operation getAccountReferral was not validated")
	}
}

// A friend's link opens identity's sign-up page with the code filled in; anything that is not a code is dropped.
func TestHostedLoginPassesTheReferralCodeToIdentity(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("GET", "/v1/auth/login?returnTo=%2F&prompt=create&referral=KDM7-XR3Q", nil)
	mustStatus(t, r, 302)
	q, _ := url.Parse(r.header.Get("Location"))
	if q.Query().Get("referral") != "KDM7-XR3Q" || q.Query().Get("prompt") != "create" {
		t.Fatalf("authorize = %s", q)
	}
	for _, bad := range []string{"%3Cscript%3E", "KDM7-XR3Q-KDM7-XR3Q-TOO-LONG", "a%26b%3Dc"} {
		r = b.do("GET", "/v1/auth/login?prompt=create&referral="+bad, nil)
		q, _ = url.Parse(r.header.Get("Location"))
		if q.Query().Has("referral") {
			t.Fatalf("referral %q must be dropped: %s", bad, q)
		}
	}
}
