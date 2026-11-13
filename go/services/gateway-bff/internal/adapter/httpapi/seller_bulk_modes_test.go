package httpapi_test

import (
	"net/url"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// exerciseSellerBulkModes drives the offer upload and the price and quantity feed (catalog imports with the OFFER and
// PRICE_QTY modes, S393, S394) and the stock CSV import (inventory, S395) through the real handlers; the caller validates
// every answer against gateway-bff-seller.yaml.
func exerciseSellerBulkModes(t *testing.T, bff *testkit.BFF, b *browser) {
	t.Helper()
	up := bff.Upstreams

	// Templates of the new modes are asked of catalog with the mode, whatever product type came along.
	for mode, file := range map[string]string{"OFFER": "afrotransact-offer.csv", "PRICE_QTY": "afrotransact-price-qty.csv"} {
		r := b.do("GET", "/v1/seller/imports/template?format=csv&mode="+mode+"&productType=grocery", nil)
		if r.status != 200 || !strings.Contains(r.header.Get("Content-Disposition"), file) {
			t.Fatalf("%s template = %d %v", mode, r.status, r.header)
		}
		if q, _ := url.ParseQuery(up.LastImportQuery); q.Get("mode") != mode {
			t.Fatalf("catalog was asked %q, want mode=%s", up.LastImportQuery, mode)
		}
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports/template?mode=MERGE", nil), 422)

	// Offer upload: relayed with its mode; the organisation is the token's, so nothing about an organisation is sent.
	body, ct := multipartFile(t, "offers.csv", "barcode,price,quantity\n4006381333931,24.99,10", map[string]string{"mode": "OFFER", "orgId": "org_other", "sellerOrg": "org_other"})
	b.headers["Content-Type"] = ct
	r := b.do("POST", "/v1/seller/imports?orgId=org_other&ownerOrg=org_other", body)
	if r.status != 201 || r.json(t)["mode"] != "OFFER" || r.json(t)["id"] != testkit.ImportOffers {
		t.Fatalf("offer upload = %d %s", r.status, r.body)
	}
	if q, _ := url.ParseQuery(up.LastUpload["rawQuery"]); q.Get("mode") != "OFFER" || q.Has("orgId") || q.Has("ownerOrg") || q.Has("sellerOrg") {
		t.Fatalf("relayed query = %q", up.LastUpload["rawQuery"])
	}

	// Price and quantity feed.
	body, ct = multipartFile(t, "feed.csv", "seller_sku,price,quantity\nOFADA-5LB,24.99,10", map[string]string{"mode": "PRICE_QTY"})
	b.headers["Content-Type"] = ct
	if r := b.do("POST", "/v1/seller/imports", body); r.status != 201 || r.json(t)["mode"] != "PRICE_QTY" {
		t.Fatalf("feed upload = %d %s", r.status, r.body)
	}
	// A feed may be 25 MB, an offer file only 10 MB.
	body, ct = multipartFile(t, "feed.csv", strings.Repeat("x", 12<<20), map[string]string{"mode": "PRICE_QTY"})
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/imports", body), 201)
	body, ct = multipartFile(t, "offers.csv", strings.Repeat("x", 12<<20), map[string]string{"mode": "OFFER"})
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/imports", body), 413)
	body, ct = multipartFile(t, "feed.csv", strings.Repeat("x", 26<<20), map[string]string{"mode": "PRICE_QTY"})
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/imports", body), 413)
	delete(b.headers, "Content-Type")

	// The offer upload's result: file hash, same-file hint, counts, and its rows with ATIN, held and image findings.
	g := b.do("GET", "/v1/seller/imports/"+testkit.ImportOffers, nil)
	if g.status != 200 || g.json(t)["sameFileAs"] != "imp-earlier" || num(g.json(t), "heldRows") != 1 || num(g.json(t), "offersCreated") != 1 || g.json(t)["fileSha256"] != "ab12" {
		t.Fatalf("offer upload result = %d %s", g.status, g.body)
	}
	rows := b.do("GET", "/v1/seller/imports/"+testkit.ImportOffers+"/rows?outcome=HELD", nil)
	if rows.status != 200 {
		t.Fatalf("rows = %d %s", rows.status, rows.body)
	}
	items := rows.json(t)["items"].([]any)
	offer, held, bad := items[0].(map[string]any), items[1].(map[string]any), items[2].(map[string]any)
	if offer["outcome"] != "OFFER_CREATED" || offer["atin"] != "ATADEV0001" || offer["offerId"] != "off-1" {
		t.Fatalf("offer row = %v", offer)
	}
	if held["outcome"] != "HELD" || held["outcomeNote"] != "We are checking the tax details for this item." {
		t.Fatalf("held row = %v", held)
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports/"+testkit.ImportOffers+"/rows?outcome=NEVER", nil), 422)

	// A preview image says what is wrong with the form's own codes (translated by Seller Center under sc.img.check.<CODE>).
	want := []struct{ code, level string }{
		{"FETCH_PRIVATE", "REJECT"}, // a bad link whose message says private address
		{"FETCH_FAILED", "REJECT"},  // any other bad link
		{"HELD_REVIEW", "HOLD"},     // held by moderation
		{"NOT_VERIFIED", "REJECT"},  // a library file that is not there (extra photo)
		{"NO_MAIN_IMAGE", "REJECT"}, // the main photo is missing
		{"", ""},                    // a link that will be copied: nothing to say
		{"BLUR", "WARN"},            // catalog sent its own check: kept as it came
	}
	imgs := bad["images"].([]any)
	if len(imgs) != len(want) {
		t.Fatalf("images = %v", imgs)
	}
	for i, w := range want {
		c, _ := imgs[i].(map[string]any)["check"].(map[string]any)
		if w.code == "" {
			if c != nil {
				t.Errorf("image %d must have no check, got %v", i, c)
			}
			continue
		}
		if c == nil || c["code"] != w.code || c["level"] != w.level {
			t.Errorf("image %d check = %v, want %s %s", i, c, w.code, w.level)
		}
	}

	// Starting a feed needs the same access as adjusting stock (the member here owns the organisation).
	mustStatus(t, b.do("POST", "/v1/seller/imports/"+testkit.ImportFeed+"/start", map[string]any{"validOnly": false}), 202)

	// ---- stock CSV import
	for mode, file := range map[string]string{"ABSOLUTE": "stock-absolute.csv", "DELTA": "stock-delta.csv"} {
		r := b.do("GET", "/v1/seller/stock/imports/template?mode="+mode, nil)
		if r.status != 200 || !strings.Contains(r.header.Get("Content-Disposition"), file) || r.header.Get("Cache-Control") != "no-store" {
			t.Fatalf("stock template %s = %d %v", mode, r.status, r.header)
		}
	}
	if r := b.do("GET", "/v1/seller/stock/imports/template", nil); r.status != 200 || !strings.Contains(r.header.Get("Content-Disposition"), "stock-absolute.csv") {
		t.Fatalf("default template = %d %v", r.status, r.header)
	}
	mustStatus(t, b.do("GET", "/v1/seller/stock/imports/template?mode=SET", nil), 422)

	// The upload names no organisation: inventory reads it from the token, and the BFF sends only the one it resolved.
	body, ct = multipartFile(t, "count.csv", "Seller SKU,Location,On hand\nOFADA-5LB,HOU-FC,120", map[string]string{"mode": "DELTA", "ownerOrg": "org_other"})
	b.headers["Content-Type"] = ct
	r = b.do("POST", "/v1/seller/stock/imports?ownerOrg=org_other", body)
	if r.status != 201 || r.json(t)["id"] != testkit.StockImportWithErrors || r.json(t)["reportUrl"] != "/api/bff/v1/seller/stock/imports/"+testkit.StockImportWithErrors+"/report" ||
		num(r.json(t), "errorRows") != 1 {
		t.Fatalf("stock upload = %d %s", r.status, r.body)
	}
	if last := up.LastStockUpload; last["mode"] != "DELTA" || last["ownerOrg"] != testkit.SellerOrgID || last["name"] != "count.csv" || last["contentType"] != "text/csv" ||
		!strings.HasPrefix(last["authorization"], "Bearer ") || strings.Contains(last["rawQuery"], "org_other") {
		t.Fatalf("relayed stock upload = %v", last)
	}
	for name, content := range map[string]struct {
		file, body string
		status     int
	}{
		"no header": {"count.csv", "NO-HEADER", 422}, "retail": {"count.csv", "RETAIL", 403}, "running": {"count.csv", "RUNNING", 409},
		"xlsx": {"count.xlsx", "PK", 422}, "empty": {"count.csv", "", 422},
	} {
		body, ct = multipartFile(t, content.file, content.body, nil)
		b.headers["Content-Type"] = ct
		if r := b.do("POST", "/v1/seller/stock/imports", body); r.status != content.status {
			t.Fatalf("stock upload (%s) = %d %s, want %d", name, r.status, r.body, content.status)
		}
	}
	body, ct = multipartFile(t, "count.csv", "x", map[string]string{"mode": "SET"})
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/stock/imports", body), 422)
	body, ct = multipartFile(t, "count.csv", strings.Repeat("x", 25<<20+1), nil)
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/stock/imports", body), 413)
	delete(b.headers, "Content-Type")

	id := "/v1/seller/stock/imports/" + testkit.StockImportWithErrors
	if r := b.do("GET", id, nil); r.status != 200 || r.json(t)["status"] != "VALIDATED" || r.json(t)["mode"] != "ABSOLUTE" {
		t.Fatalf("stock import = %d %s", r.status, r.body)
	}
	// Another organisation's file is not found, exactly like an unknown one.
	mustStatus(t, b.do("GET", "/v1/seller/stock/imports/"+testkit.StockImportOther, nil), 404)
	mustStatus(t, b.do("GET", "/v1/seller/stock/imports/"+testkit.StockImportOther+"/rows", nil), 404)
	mustStatus(t, b.do("GET", "/v1/seller/stock/imports/"+testkit.StockImportOther+"/report", nil), 404)
	mustStatus(t, b.do("POST", "/v1/seller/stock/imports/"+testkit.StockImportOther+"/start", map[string]any{"validOnly": true}), 404)
	if r := b.do("GET", id+"/rows?status=ERROR", nil); r.status != 200 || len(r.json(t)["items"].([]any)) != 1 ||
		!strings.Contains(string(r.body), "reserved for orders") {
		t.Fatalf("stock error rows = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", id+"/rows?status=BROKEN", nil), 422)
	mustStatus(t, b.do("GET", id+"/rows?outcome=APPLIED&size=500", nil), 422)
	if rep := b.do("GET", id+"/report", nil); rep.status != 200 || !strings.Contains(rep.header.Get("Content-Disposition"), "count-report.csv") ||
		rep.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("stock report = %d %v", rep.status, rep.header)
	}
	if r := b.do("POST", id+"/start", nil); r.status != 409 || r.json(t)["code"] != "CONFLICT" {
		t.Fatalf("start with errors = %d %s", r.status, r.body)
	}
	if r := b.do("POST", id+"/start", map[string]any{"validOnly": true}); r.status != 202 || r.json(t)["status"] != "QUEUED" {
		t.Fatalf("start valid rows = %d %s", r.status, r.body)
	}
	if r := b.do("GET", "/v1/seller/stock/imports?page=0&size=10", nil); r.status != 200 || num(r.json(t), "total") != 1 {
		t.Fatalf("stock history = %d %s", r.status, r.body)
	}
	if q, _ := url.ParseQuery(up.LastStockUpload["listQuery"]); q.Get("ownerOrg") != testkit.SellerOrgID {
		t.Fatalf("stock history asked inventory %q, want the resolved organisation", up.LastStockUpload["listQuery"])
	}
	mustStatus(t, b.do("GET", "/v1/seller/stock/imports?size=500", nil), 422)
}

// Who may use the bulk routes: a role without the access gets 403 before anything reaches catalog or inventory.
func TestBulkModesNeedTheRightAccess(t *testing.T) {
	cases := []struct {
		role                                             string
		offerUpload, feedUpload, stockUpload, stockStart int
	}{
		// seller.catalog edits listings and inventory: everything.
		{"seller.catalog", 201, 201, 201, 202},
		// seller.fulfilment edits inventory but only views listings: stock files yes, product files no, may still read uploads.
		{"seller.fulfilment", 403, 403, 201, 202},
		// seller.support only views listings and has no inventory access.
		{"seller.support", 403, 403, 403, 403},
		// seller.finance has neither.
		{"seller.finance", 403, 403, 403, 403},
	}
	for _, c := range cases {
		t.Run(c.role, func(t *testing.T) {
			bff, b := sellerSetup(t, testkit.Options{})
			bff.Upstreams.MemberOrgs = []testkit.MemberOrg{{ID: testkit.SellerOrgID, Kind: "SELLER", Name: "Ade's Kitchen", Status: "APPROVED", Role: c.role}}
			bff.Upstreams.SetSellerPlan("seller_growth")
			post := func(path, file, content, mode string) int {
				body, ct := multipartFile(t, file, content, map[string]string{"mode": mode})
				b.headers["Content-Type"] = ct
				defer delete(b.headers, "Content-Type")
				return b.do("POST", path, body).status
			}
			if got := post("/v1/seller/imports", "o.csv", "barcode\n1", "OFFER"); got != c.offerUpload {
				t.Errorf("offer upload = %d, want %d", got, c.offerUpload)
			}
			if got := post("/v1/seller/imports", "f.csv", "seller_sku\nA", "PRICE_QTY"); got != c.feedUpload {
				t.Errorf("feed upload = %d, want %d", got, c.feedUpload)
			}
			if got := post("/v1/seller/stock/imports", "s.csv", "Seller SKU\nA", "ABSOLUTE"); got != c.stockUpload {
				t.Errorf("stock upload = %d, want %d", got, c.stockUpload)
			}
			start := b.do("POST", "/v1/seller/stock/imports/"+testkit.StockImportWithErrors+"/start", map[string]any{"validOnly": true}).status
			if start != c.stockStart {
				t.Errorf("stock start = %d, want %d", start, c.stockStart)
			}
		})
	}
}

// A plan without bulk upload cannot use the new modes or the stock import either (the same capability as the product upload).
func TestBulkModesNeedAPlanWithBulkUpload(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{})
	bff.Upstreams.SetSellerPlan("seller_essentials")
	for _, path := range []string{"/v1/seller/stock/imports", "/v1/seller/stock/imports/template", "/v1/seller/imports/template?mode=OFFER",
		"/v1/seller/stock/imports/" + testkit.StockImportWithErrors} {
		r := b.do("GET", path, nil)
		mustStatus(t, r, 403)
		if got := problemCode(t, r); got != "PLAN_FEATURE_REQUIRED" {
			t.Fatalf("%s = %s, want PLAN_FEATURE_REQUIRED", path, got)
		}
	}
}
