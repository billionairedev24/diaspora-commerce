package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

func TestSearchAdsAndClicks(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)
	u := bff.Upstreams

	// Results pages ask for ads; the ad keeps slot 1 with its label, reason and click token, and the page is not cached.
	r := b.do("GET", "/v1/search?q=jollof", nil)
	mustStatus(t, r, 200)
	if q := u.LastSearchQuery(); q.Get("ads") != "true" || q.Get("track") == "false" {
		t.Fatalf("results page should send ads=true and stay tracked: %v", q)
	}
	page := r.json(t)
	if page["searchId"] != testkit.TestSearchID {
		t.Fatalf("searchId = %v", page["searchId"])
	}
	hits := page["hits"].([]any)
	sp, _ := hits[0].(map[string]any)["sponsor"].(map[string]any)
	if sp == nil || sp["advertiser"] != "Mama Tee" || sp["adToken"] != testkit.TestAdToken || sp["slot"] != float64(1) {
		t.Fatalf("first hit should be the sponsored product: %v", hits[0])
	}
	if reason, _ := sp["reason"].(map[string]any); reason == nil || reason["targeting"] != "KEYWORD" || reason["summary"] == "" {
		t.Fatalf("sponsor reason = %v", sp["reason"])
	}
	if cc := r.header.Get("Cache-Control"); !strings.Contains(cc, "no-store") || !strings.Contains(cc, "private") {
		t.Fatalf("a page with ads must not be cached: %q", cc)
	}
	// Fastest sort keeps the ad in the slot it won.
	r = b.do("GET", "/v1/search?q=jollof&sort=fastest", nil)
	mustStatus(t, r, 200)
	if r.json(t)["hits"].([]any)[0].(map[string]any)["sponsor"] == nil {
		t.Fatal("fastest sort moved the ad out of slot 1")
	}

	// Opting out sends no ads parameter and yields organic cards only.
	r = b.do("GET", "/v1/search?q=jollof&ads=false", nil)
	mustStatus(t, r, 200)
	if u.LastSearchQuery().Has("ads") {
		t.Fatalf("ads=false should not ask search for ads: %v", u.LastSearchQuery())
	}
	for _, h := range r.json(t)["hits"].([]any) {
		if h.(map[string]any)["sponsor"] != nil {
			t.Fatal("organic page carried an ad")
		}
	}

	// Home rails are machine-made queries: track=false, no ads.
	mustStatus(t, b.do("GET", "/v1/home", nil), 200)
	if q := u.LastSearchQuery(); q.Get("track") != "false" || q.Has("ads") {
		t.Fatalf("rails should send track=false without ads: %v", q)
	}

	// Clicks: organic and sponsored, relayed with the market; validation is local.
	r = b.do("POST", "/v1/search/clicks", map[string]any{"searchId": testkit.TestSearchID, "q": "jollof", "atin": "AT000000P2", "position": 1, "adToken": testkit.TestAdToken})
	mustStatus(t, r, 202)
	if got := r.json(t); got["sponsored"] != true || got["adStatus"] != "BILLABLE" {
		t.Fatalf("click = %v", got)
	}
	if c := u.LastSearchClick(); c["market"] != "us" || c["adToken"] != testkit.TestAdToken || c["position"] != float64(1) {
		t.Fatalf("upstream click = %v", c)
	}
	r = b.do("POST", "/v1/search/clicks", map[string]any{"searchId": testkit.TestSearchID, "atin": "AT000000P2", "position": 3})
	mustStatus(t, r, 202)
	if r.json(t)["sponsored"] != false || u.LastSearchClick()["adToken"] != nil {
		t.Fatalf("organic click = %v", u.LastSearchClick())
	}
	mustStatus(t, b.do("POST", "/v1/search/clicks", map[string]any{"searchId": "", "atin": "nope", "position": 0}), 422)

	for _, op := range []string{"search", "recordSearchClick"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated against the contract", op)
		}
	}
}
