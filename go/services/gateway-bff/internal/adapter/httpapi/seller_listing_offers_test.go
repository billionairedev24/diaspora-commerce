package httpapi_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// exerciseListingOffers drives the Listings table with offers on other sellers' pages and the edit of an offer's price and
// quantity through the real handlers; the caller validates every answer against gateway-bff-seller.yaml (S382).
func exerciseListingOffers(t *testing.T, bff *testkit.BFF, b *browser) {
	t.Helper()
	rows := func(path string) map[string]map[string]any {
		r := b.do("GET", path, nil)
		if r.status != 200 {
			t.Fatalf("%s = %d %s", path, r.status, r.body)
		}
		out := map[string]map[string]any{}
		for _, it := range r.json(t)["items"].([]any) {
			row := it.(map[string]any)
			key, _ := row["offerId"].(string)
			if key == "" {
				key = "product:" + row["productId"].(string)
			}
			out[key] = row
		}
		return out
	}

	all := rows("/v1/seller/listings?page=0&size=100")
	held := all["of-3"]
	if held == nil || held["kind"] != "OFFER" || held["title"] != "Raw Shea Butter (8 oz)" || held["priceMinor"] != float64(1100) || held["quantity"] != float64(7) ||
		held["offerVersion"] != float64(1) || held["status"] != "IN_REVIEW" || held["statusLabel"] != "We are checking the tax details for this item." ||
		held["moderationNote"] != "We are checking the tax details for this item." || held["imageUrl"] == nil {
		t.Fatalf("held offer row = %v", held)
	}
	// A page whose name cannot be read still has a row, named by the seller's SKU rather than an id.
	if other := all["of-2"]; other == nil || other["kind"] != "OFFER" || other["title"] != "Your offer OTHER" || other["status"] != "LIVE" {
		t.Fatalf("offer on an unknown page = %v", other)
	}
	// The offer on the organisation's own page is not an offer-only row; the page keeps its PRODUCT row.
	if own := all["of-1"]; own != nil {
		t.Fatalf("an offer on an own page must not be a separate row: %v", own)
	}
	for key, row := range all {
		if row["offerId"] == nil && row["kind"] != "PRODUCT" {
			t.Fatalf("%s kind = %v", key, row["kind"])
		}
	}
	// Search reaches the name; a status filter keeps both kinds; sorting orders them together.
	if got := rows("/v1/seller/listings?q=shea"); got["of-3"] == nil || len(got) != 1 {
		t.Fatalf("q=shea = %v", got)
	}
	if got := rows("/v1/seller/listings?status=IN_REVIEW"); got["of-3"] == nil || got["of-2"] != nil {
		t.Fatalf("status filter = %v", got)
	}
	if r := b.do("GET", "/v1/seller/listings?sort=priceMinor,desc&size=100", nil); r.status != 200 {
		t.Fatalf("sorted = %d %s", r.status, r.body)
	}

	// Edit the price and the quantity of the offer: the row shows the new terms and the next version.
	r := b.do("PATCH", "/v1/seller/offers/of-3", map[string]any{"priceMinor": 1199, "quantity": 9, "version": 1})
	if r.status != 200 || r.json(t)["priceMinor"] != float64(1199) || r.json(t)["quantity"] != float64(9) || r.json(t)["version"] != float64(2) {
		t.Fatalf("edit = %d %s", r.status, r.body)
	}
	if after := rows("/v1/seller/listings?page=0&size=100")["of-3"]; after == nil || after["priceMinor"] != float64(1199) || after["quantity"] != float64(9) {
		t.Fatalf("row after the edit = %v", after)
	}
	// Plain refusals: nothing to change, a bad price, a bad quantity, an offer that is not the organisation's.
	mustStatus(t, b.do("PATCH", "/v1/seller/offers/of-3", map[string]any{}), 422)
	mustStatus(t, b.do("PATCH", "/v1/seller/offers/of-3", map[string]any{"priceMinor": 0}), 422)
	mustStatus(t, b.do("PATCH", "/v1/seller/offers/of-3", map[string]any{"quantity": -1}), 422)
	mustStatus(t, b.do("PATCH", "/v1/seller/offers/not-mine", map[string]any{"priceMinor": 100}), 404)

	// Pause and resume the offer-only row (offer of-2 is on a page this organisation does not own): the row follows, and the
	// page is never written (the fake upstream has no page-pause route to answer).
	mustStatus(t, b.do("POST", "/v1/seller/offers/of-3/pause", nil), 409) // held for tax: not on sale, so not pausable
	p := b.do("POST", "/v1/seller/offers/of-2/pause", map[string]any{"version": 1})
	if p.status != 200 || p.json(t)["status"] != "PAUSED" || p.json(t)["version"] != float64(2) {
		t.Fatalf("pause = %d %s", p.status, p.body)
	}
	if after := rows("/v1/seller/listings?page=0&size=100")["of-2"]; after == nil || after["status"] != "PAUSED" || after["kind"] != "OFFER" {
		t.Fatalf("row after the pause = %v", after)
	}
	mustStatus(t, b.do("POST", "/v1/seller/offers/of-2/pause", nil), 409)
	r2 := b.do("POST", "/v1/seller/offers/of-2/resume", nil)
	if r2.status != 200 || r2.json(t)["status"] != "ACTIVE" {
		t.Fatalf("resume = %d %s", r2.status, r2.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/offers/of-2/resume", nil), 409)
	// Another organisation's offer (an id this organisation's offers do not list) is a 404, never a call to offers.
	mustStatus(t, b.do("POST", "/v1/seller/offers/not-mine/pause", nil), 404)
	mustStatus(t, b.do("POST", "/v1/seller/offers/not-mine/resume", nil), 404)
	if n := bff.Upstreams.CallCount("POST /v1/offers/not-mine/pause") + bff.Upstreams.CallCount("POST /v1/offers/not-mine/resume"); n != 0 {
		t.Fatalf("offers was called for an offer that is not the organisation's: %d", n)
	}
}
