package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// A ZIP change must reach the home page: the next GET /v1/home (same cookie) is for the new place and is never cached.
func TestHomeFollowsTheDeliverToZIP(t *testing.T) {
	_, b := setup(t, testkit.Options{Content: true})
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}), 200)
	r := b.do("GET", "/v1/home", nil)
	if cc := r.header.Get("Cache-Control"); !strings.Contains(cc, "no-store") {
		t.Errorf("home must not be cached, Cache-Control = %q", cc)
	}
	bronx := r.json(t)["deliverTo"].(map[string]any)
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "77042"}), 200)
	houston := b.do("GET", "/v1/home", nil).json(t)["deliverTo"].(map[string]any)
	if bronx["zip"] != "10456" || houston["zip"] != "77042" || bronx["city"] == houston["city"] {
		t.Errorf("deliverTo did not follow the ZIP: %v then %v", bronx, houston)
	}
}
