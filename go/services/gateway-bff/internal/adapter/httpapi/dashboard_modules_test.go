package httpapi_test

import (
	"sort"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

func modulesOf(t *testing.T, d map[string]any) map[string]map[string]any {
	t.Helper()
	out := map[string]map[string]any{}
	for _, m := range d["modules"].([]any) {
		mm := m.(map[string]any)
		out[mm["id"].(string)] = mm
	}
	return out
}

func ids(mods map[string]map[string]any) []string {
	out := []string{}
	for k := range mods {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func TestSellerHomeModules(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{})
	mods := modulesOf(t, b.do("GET", "/v1/seller/dashboard?rangeDays=30", nil).json(t))
	if got := strings.Join(ids(mods), ","); got != "health,inventory,listings,payouts,promotions,reviews,sales" {
		t.Fatalf("seller modules = %s", got)
	}
	// Orders analytics is not answered by the fake: the module says so instead of showing zeros.
	if mods["sales"]["state"] != "unavailable" || mods["sales"]["headline"] != nil {
		t.Fatalf("sales = %v", mods["sales"])
	}
	rev := mods["reviews"]
	if rev["state"] != "ready" || rev["headline"].(map[string]any)["value"] != "4.0" {
		t.Fatalf("reviews = %v", rev)
	}
	for id, m := range mods {
		if n := len(m["actions"].([]any)); n > 3 {
			t.Fatalf("%s has %d quick actions", id, n)
		}
		if m["state"] == "empty" && (m["empty"] == nil || m["headline"] != nil) {
			t.Fatalf("%s: an empty module carries a prompt and no figures: %v", id, m)
		}
	}
	// A bad range is still refused.
	mustStatus(t, b.do("GET", "/v1/seller/dashboard?rangeDays=12", nil), 422)
}

func TestProviderHomeModules(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{})
	mustStatus(t, b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.ProviderOrgID}), 200)
	mods := modulesOf(t, b.do("GET", "/v1/seller/dashboard?rangeDays=7", nil).json(t))
	if got := strings.Join(ids(mods), ","); got != "bookings,health,payouts,revenue" {
		t.Fatalf("provider modules = %s", got)
	}
	// Bookings analytics is not answered by the fake: unavailable, never zeros.
	if mods["bookings"]["state"] != "unavailable" || mods["revenue"]["state"] != "unavailable" {
		t.Fatalf("bookings = %v, revenue = %v", mods["bookings"], mods["revenue"])
	}
	if mods["payouts"]["state"] != "ready" || mods["health"]["headline"].(map[string]any)["value"] != "Good" {
		t.Fatalf("payouts = %v, health = %v", mods["payouts"], mods["health"])
	}
}
