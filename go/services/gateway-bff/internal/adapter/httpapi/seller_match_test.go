package httpapi_test

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// exerciseSellerMatch drives the barcode step, offers on an existing page, change requests and image tasks through the
// real handlers; the caller validates every answer against gateway-bff-seller.yaml (S382, S391).
func exerciseSellerMatch(t *testing.T, bff *testkit.BFF, b *browser) {
	t.Helper()
	post := func(path string, body any) resp { return b.do("POST", path, body) }

	// MATCH: the existing page as a plain card (the seller count is tested in TestSellerMatchCountsSellers).
	m := post("/v1/seller/catalog/matches", map[string]any{"gtin": "0123 4567-8905"})
	mj := m.json(t)
	if m.status != 200 || mj["result"] != "MATCH" || mj["canOffer"] != true || mj["title"] != "Raw Shea Butter, 8 oz" || mj["atin"] != "ATSHEAV001" {
		t.Fatalf("match = %d %s", m.status, m.body)
	}
	// Brand plus part number finds the same page; neither a barcode nor a pair is a 422.
	if r := post("/v1/seller/catalog/matches", map[string]any{"brand": "Kente Naturals", "mpn": "SB-8"}); r.status != 200 || r.json(t)["matchedOn"] != "BRAND_MPN" {
		t.Fatalf("brand+mpn = %d %s", r.status, r.body)
	}
	mustStatus(t, post("/v1/seller/catalog/matches", map[string]any{"brand": "Kente Naturals"}), 422)
	mustStatus(t, post("/v1/seller/catalog/matches", map[string]any{}), 422)
	// Not a page yet: images and the full form follow. A blocked page says why in plain words.
	if r := post("/v1/seller/catalog/matches", map[string]any{"gtin": "9780306406157"}); r.status != 200 || r.json(t)["result"] != "NO_MATCH" || r.json(t)["canCreate"] != true {
		t.Fatalf("no match = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/catalog/matches", map[string]any{"gtin": testkit.MatchGTINBlocked}); r.status != 200 || r.json(t)["canOffer"] != false ||
		len(r.json(t)["blockers"].([]any)) != 1 {
		t.Fatalf("blocked = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/catalog/matches", map[string]any{"gtin": testkit.MatchGTINWrongDigit}); r.json(t)["result"] != "INVALID" || r.json(t)["suggestion"] != testkit.MatchGTINLive {
		t.Fatalf("invalid = %s", r.body)
	}
	if r := post("/v1/seller/catalog/matches", map[string]any{"gtin": testkit.MatchGTINStore}); r.json(t)["result"] != "RESTRICTED" || r.json(t)["message"] == nil {
		t.Fatalf("restricted = %s", r.body)
	}
	// Too short: an answer, not an error, and catalog is not asked.
	calls := bff.Upstreams.CallCount("POST /v1/catalog/matches")
	if r := post("/v1/seller/catalog/matches", map[string]any{"gtin": "12345"}); r.status != 200 || r.json(t)["result"] != "INVALID" || r.json(t)["message"] == nil {
		t.Fatalf("short = %d %s", r.status, r.body)
	}
	if bff.Upstreams.CallCount("POST /v1/catalog/matches") != calls {
		t.Fatal("a barcode that is too short must not reach catalog")
	}

	// Offer only: terms go to offers (never an organisation id), the SKU is generated, the offer is live.
	offer := map[string]any{"atin": "ATSHEAV001", "priceMinor": 1250, "listPriceMinor": 1500, "quantity": 12, "condition": "NEW", "fulfilment": "SELLER", "handlingDays": 3}
	o := post("/v1/seller/offers", offer)
	if o.status != 201 || o.json(t)["status"] != "ACTIVE" || o.json(t)["sellerSku"] != "ATSHEAV001-NEW" {
		t.Fatalf("offer = %d %s", o.status, o.body)
	}
	sent := bff.Upstreams.LastOfferBody
	if sent["atin"] != "ATSHEAV001" || num(sent, "priceMinor") != 1250 || sent["currency"] != "USD" || sent["sellerOrgId"] != nil || sent["conditionNote"] != nil {
		t.Fatalf("offer sent to offers = %v", sent)
	}
	// Held for tax: plain words, no ids to the seller.
	held := post("/v1/seller/offers", map[string]any{"atin": testkit.MatchATINHeld, "priceMinor": 900, "quantity": 3, "fulfilment": "SELLER"})
	if held.status != 201 || held.json(t)["status"] != "TAX_HELD" || held.json(t)["message"] != "We are checking the tax details for this item." || held.json(t)["heldReason"] != "TAX_DECISION_MISSING" {
		t.Fatalf("held offer = %d %s", held.status, held.body)
	}
	// Selling twice, and without approval, are answers with their own codes.
	if r := post("/v1/seller/offers", offer); r.status != 409 || r.json(t)["code"] != "OFFER_EXISTS" || !strings.Contains(r.json(t)["detail"].(string), "already sell") {
		t.Fatalf("second offer = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/offers", map[string]any{"atin": "ATNOTLIVE1", "priceMinor": 900, "quantity": 3, "fulfilment": "SELLER"}); r.status != 422 || r.json(t)["code"] != "SELLING_APPROVAL_REQUIRED" {
		t.Fatalf("no approval = %d %s", r.status, r.body)
	}
	// Page content is refused by name; so is anything else that is not an offer detail.
	for _, field := range []string{"title", "images", "attributes", "brand", "category", "gtin", "description", "productType", "taxFacts"} {
		bad := map[string]any{"atin": "ATSHEAV002", "priceMinor": 900, "quantity": 3, "fulfilment": "SELLER", field: "x"}
		if r := post("/v1/seller/offers", bad); r.status != 422 || r.json(t)["code"] != "PAGE_CONTENT_LOCKED" || r.json(t)["errors"].(map[string]any)[field] == nil ||
			r.json(t)["errors"].(map[string]any)[field] != "That detail belongs to the product page. Suggest a change instead." {
			t.Fatalf("content field %s = %d %s", field, r.status, r.body)
		}
	}
	if r := post("/v1/seller/offers", map[string]any{"atin": "ATSHEAV002", "priceMinor": 900, "quantity": 3, "fulfilment": "SELLER", "sellerOrgId": "other"}); r.status != 422 {
		t.Fatalf("sellerOrgId = %d %s", r.status, r.body)
	}
	// Terms are checked before offers is asked.
	for name, bad := range map[string]map[string]any{
		"price":         {"atin": "ATSHEAV002", "priceMinor": 0, "quantity": 1, "fulfilment": "SELLER"},
		"list price":    {"atin": "ATSHEAV002", "priceMinor": 900, "listPriceMinor": 800, "quantity": 1, "fulfilment": "SELLER"},
		"atin":          {"atin": "nope", "priceMinor": 900, "quantity": 1, "fulfilment": "SELLER"},
		"note":          {"atin": "ATSHEAV002", "priceMinor": 900, "quantity": 1, "fulfilment": "SELLER", "condition": "USED_GOOD"},
		"quantity":      {"atin": "ATSHEAV002", "priceMinor": 900, "fulfilment": "SELLER"},
		"fulfilment":    {"atin": "ATSHEAV002", "priceMinor": 900, "quantity": 1, "fulfilment": "DRONE"},
		"pickup place":  {"atin": "ATSHEAV002", "priceMinor": 900, "fulfilment": "AFROTRANSACT_DELIVERY"},
		"package":       {"atin": "ATSHEAV002", "priceMinor": 900, "quantity": 1, "fulfilment": "SELLER", "packaging": map[string]any{"lengthIn": 1, "widthIn": 1, "heightIn": 1, "weightLb": 1}},
		"handling days": {"atin": "ATSHEAV002", "priceMinor": 900, "quantity": 1, "fulfilment": "SELLER", "handlingDays": 31},
	} {
		if r := post("/v1/seller/offers", bad); r.status != 422 || r.json(t)["errors"] == nil {
			t.Fatalf("bad %s = %d %s", name, r.status, r.body)
		}
	}
	used := post("/v1/seller/offers", map[string]any{"atin": "ATSHEAV003", "priceMinor": 900, "quantity": 1, "fulfilment": "SELLER", "condition": "USED_GOOD", "conditionNote": "Box dented"})
	if used.status != 201 || bff.Upstreams.LastOfferBody["conditionNote"] != "Box dented" {
		t.Fatalf("used offer = %d %s", used.status, used.body)
	}
	mustStatus(t, post("/v1/seller/offers", "[1]"), 422)

	// Change requests: Suggest a change, wrong product, duplicate; once each while waiting.
	title := map[string]any{"type": "TITLE", "payload": map[string]any{"value": "Raw Shea Butter, 8 oz jar"}, "note": "The jar is 8 oz"}
	c := post("/v1/seller/products/ATSHEA0001/change-requests", title)
	if c.status != 201 || c.json(t)["status"] != "PENDING" || c.json(t)["type"] != "TITLE" {
		t.Fatalf("change request = %d %s", c.status, c.body)
	}
	id := c.json(t)["id"].(string)
	if r := post("/v1/seller/products/ATSHEA0001/change-requests", title); r.status != 409 || r.json(t)["code"] != "CHANGE_REQUEST_EXISTS" {
		t.Fatalf("again = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/products/ATSHEAV001/change-requests", map[string]any{"type": "WRONG_MATCH", "payload": map[string]any{"variantAtin": "ATSHEAV001"}}); r.status != 201 {
		t.Fatalf("wrong product = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/products/ATWRONGOPN/change-requests", map[string]any{"type": "WRONG_MATCH", "payload": map[string]any{"variantAtin": "ATWRONGOPN"}}); r.status != 409 || r.json(t)["code"] != "WRONG_MATCH_OPEN" {
		t.Fatalf("wrong product open = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/products/ATSHEA0001/change-requests", map[string]any{"type": "DUPLICATE_PAGE", "payload": map[string]any{"duplicateOfAtin": "ATSHEA0002"}}); r.status != 201 {
		t.Fatalf("duplicate = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/products/ATNOOFFER1/change-requests", title); r.status != 403 || r.json(t)["code"] != "NOT_YOUR_OFFER" {
		t.Fatalf("no offer = %d %s", r.status, r.body)
	}
	mustStatus(t, post("/v1/seller/products/ATSHEA0001/change-requests", map[string]any{"type": "TITLE", "payload": map[string]any{}}), 422)
	mustStatus(t, post("/v1/seller/products/ATSHEA0001/change-requests", map[string]any{"type": "SELL_IT_FOR_ME"}), 422)
	mustStatus(t, post("/v1/seller/products/ATSHEA0001/change-requests", map[string]any{"type": "DUPLICATE_PAGE", "payload": map[string]any{"duplicateOfAtin": "x"}}), 422)
	mustStatus(t, post("/v1/seller/products/not-an-atin/change-requests", title), 404)
	if r := b.do("GET", "/v1/seller/change-requests?status=PENDING", nil); r.status != 200 || num(r.json(t), "total") != 3 {
		t.Fatalf("requests = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/change-requests?status=DONE", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/change-requests?size=500", nil), 422)
	if r := post("/v1/seller/change-requests/"+id+"/withdraw", nil); r.status != 200 || r.json(t)["status"] != "WITHDRAWN" {
		t.Fatalf("withdraw = %d %s", r.status, r.body)
	}
	if r := post("/v1/seller/change-requests/"+id+"/withdraw", nil); r.status != 409 || r.json(t)["code"] != "CHANGE_REQUEST_DECIDED" {
		t.Fatalf("withdraw twice = %d %s", r.status, r.body)
	}
	mustStatus(t, post("/v1/seller/change-requests/not-a-uuid/withdraw", nil), 404)

	// Improve your image: failing first, with the end of the grace period.
	tasks := b.do("GET", "/v1/seller/image-tasks", nil)
	items, _ := tasks.json(t)["items"].([]any)
	if tasks.status != 200 || len(items) != 2 || items[0].(map[string]any)["state"] != "FAIL" || num(items[0].(map[string]any), "daysLeft") != 48 {
		t.Fatalf("image tasks = %d %s", tasks.status, tasks.body)
	}

	// The image slot, title, brand and product type reach media; the verdict comes back with the asset.
	up := post("/v1/seller/uploads", map[string]any{"purpose": "product-image", "fileName": "reject-main.jpg", "contentType": "image/jpeg", "size": 1000,
		"slot": "MAIN", "title": "Raw Shea Butter", "brand": "Kente Naturals", "productTypeCode": "beauty"})
	if up.status != 201 || up.json(t)["assetId"] != "asset-reject" {
		t.Fatalf("upload = %d %s", up.status, up.body)
	}
	img := bff.Upstreams.LastImageUpload
	if img["slot"] != "MAIN" || img["title"] != "Raw Shea Butter" || img["brand"] != "Kente Naturals" || img["productTypeCode"] != "beauty" {
		t.Fatalf("media saw %v", img)
	}
	done := post("/v1/seller/uploads/asset-reject/complete", nil)
	chk := done.json(t)["checks"].([]any)
	if done.status != 200 || done.json(t)["verdict"] != "REJECT" || done.json(t)["slot"] != "MAIN" || len(chk) != 1 || chk[0].(map[string]any)["code"] != "MAIN_TOO_SMALL" ||
		num(chk[0].(map[string]any)["params"].(map[string]any), "w") != 640 {
		t.Fatalf("rejected asset = %d %s", done.status, done.body)
	}
	if r := post("/v1/seller/uploads/asset-warn/complete", nil); r.json(t)["verdict"] != "WARN" || r.json(t)["status"] != "READY" {
		t.Fatalf("warned asset = %s", r.body)
	}
	if r := post("/v1/seller/uploads/asset-held/complete", nil); r.json(t)["verdict"] != "HOLD" || r.json(t)["status"] != "FLAGGED" {
		t.Fatalf("held asset = %s", r.body)
	}
	// A plain upload keeps working: no slot, no verdict.
	plain := post("/v1/seller/uploads", map[string]any{"purpose": "product-image", "fileName": "a.jpg", "contentType": "image/jpeg", "size": 1000})
	if plain.status != 201 || bff.Upstreams.LastImageUpload["slot"] != nil {
		t.Fatalf("plain upload = %d %s", plain.status, plain.body)
	}
	mustStatus(t, post("/v1/seller/uploads", map[string]any{"purpose": "product-image", "fileName": "a.jpg", "contentType": "image/jpeg", "size": 1000, "slot": "HERO"}), 422)
	mustStatus(t, post("/v1/seller/uploads", map[string]any{"purpose": "storefront", "fileName": "a.jpg", "contentType": "image/jpeg", "size": 1000, "slot": "MAIN"}), 422)
}

// TestSellerMatchNeedsTheListingsModule: a role that may only view listings (fulfilment) can read its requests and tasks
// but cannot look up, offer or report; a guest gets 401.
func TestSellerMatchNeedsTheListingsModule(t *testing.T) {
	bff, b := setup(t, testkit.Options{SellerPending: true})
	b.origin = testkit.SellerCenterOrigin
	bff.Upstreams.MemberOrgs = []testkit.MemberOrg{{ID: testkit.SellerOrgID, Kind: "SELLER", Name: "Ade's Kitchen", Status: "APPROVED", Role: "seller.fulfilment"}}
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	mustStatus(t, b.do("POST", "/v1/seller/catalog/matches", map[string]any{"gtin": testkit.MatchGTINLive}), 403)
	mustStatus(t, b.do("POST", "/v1/seller/offers", map[string]any{"atin": "ATSHEAV001", "priceMinor": 900, "quantity": 1, "fulfilment": "SELLER"}), 403)
	mustStatus(t, b.do("POST", "/v1/seller/products/ATSHEA0001/change-requests", map[string]any{"type": "DUPLICATE_PAGE", "payload": map[string]any{"duplicateOfAtin": "ATSHEA0002"}}), 403)
	mustStatus(t, b.do("POST", "/v1/seller/change-requests/c0c0c0c0-0000-4000-8000-000000000001/withdraw", nil), 403)
	mustStatus(t, b.do("GET", "/v1/seller/change-requests", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/image-tasks", nil), 200)
	if n := bff.Upstreams.CallCount("POST /v1/catalog/matches") + bff.Upstreams.CallCount("POST /v1/offers"); n != 0 {
		t.Fatalf("a refused request must not reach catalog or offers (%d calls)", n)
	}

	_, guest := setup(t, testkit.Options{SellerPending: true})
	guest.origin = testkit.SellerCenterOrigin
	mustStatus(t, guest.do("POST", "/v1/seller/catalog/matches", map[string]any{"gtin": testkit.MatchGTINLive}), 401)
	mustStatus(t, guest.do("GET", "/v1/seller/image-tasks", nil), 401)
}

// TestSellerMatchCountsSellers reads the number of sellers of a matched item from offers' public buy box, and leaves
// it out for a barcode that has no page.
func TestSellerMatchCountsSellers(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{Offers: true, SellerPending: true})
	m := b.do("POST", "/v1/seller/catalog/matches", map[string]any{"gtin": testkit.MatchGTINLive})
	if m.status != 200 || num(m.json(t), "sellerCount") != 2 {
		t.Fatalf("match = %d %s", m.status, m.body)
	}
	if r := b.do("POST", "/v1/seller/catalog/matches", map[string]any{"gtin": "9780306406157"}); r.json(t)["sellerCount"] != nil {
		t.Fatalf("no match has no seller count: %s", r.body)
	}
}
