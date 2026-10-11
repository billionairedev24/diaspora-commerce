package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// S278: help pages come from content, are public and cacheable, page by cursor and filter by topic; unknown pages
// are 404 and bad filters 422.
func TestHelpCenter(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true})
	r := b.do("GET", "/v1/help/articles?topic=orders_delivery&q=%20ship%20&limit=10", nil)
	mustStatus(t, r, 200)
	if cc := r.header.Get("Cache-Control"); !strings.HasPrefix(cc, "public") {
		t.Fatalf("Cache-Control = %q", cc)
	}
	page := r.json(t)
	if len(page["items"].([]any)) != 1 || page["nextCursor"] != "b2Ox" || len(page["topics"].([]any)) != 2 {
		t.Fatalf("page = %v", page)
	}
	if q := bff.Upstreams.LastHelpQuery; q.Get("topic") != "ORDERS_DELIVERY" || q.Get("q") != "ship" || q.Get("limit") != "10" {
		t.Fatalf("sent %v", q)
	}
	last := b.do("GET", "/v1/help/articles?cursor=b2Ox", nil).json(t)
	if last["nextCursor"] != nil || last["items"].([]any)[0].(map[string]any)["slug"] != "press" {
		t.Fatalf("last page = %v", last)
	}
	if q := bff.Upstreams.LastHelpQuery; q.Get("limit") != "20" {
		t.Fatalf("default limit not sent: %v", q)
	}
	mustStatus(t, b.do("GET", "/v1/help/articles?topic=gossip", nil), 422)
	mustStatus(t, b.do("GET", "/v1/help/articles?limit=500", nil), 422)
	mustStatus(t, b.do("GET", "/v1/help/articles?limit=x", nil), 422)
	mustStatus(t, b.do("GET", "/v1/help/articles?cursor=bad", nil), 422)

	hub := b.do("GET", "/v1/help/articles/help-center", nil)
	mustStatus(t, hub, 200)
	a := hub.json(t)
	if a["title"] != "Help center" || len(a["sections"].([]any)) != 1 || a["version"] != nil || a["status"] != nil {
		t.Fatalf("article = %v", a)
	}
	mustStatus(t, b.do("GET", "/v1/help/articles/nope", nil), 404)
	mustStatus(t, b.do("GET", "/v1/help/articles/NOT_A_SLUG!", nil), 404)
}
