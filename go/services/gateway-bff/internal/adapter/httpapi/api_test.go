package httpapi_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

func setup(t *testing.T, o testkit.Options) (*testkit.BFF, *browser) {
	t.Helper()
	u := testkit.NewUpstreams(o)
	t.Cleanup(u.Close)
	bff := testkit.NewBFF(u, testkit.Config{})
	return bff, newBrowser(t, bff.Handler)
}

func TestCartFlowWithStockCapsAndSelection(t *testing.T) {
	_, b := setup(t, testkit.Options{})

	r := b.do("GET", "/v1/cart", nil)
	mustStatus(t, r, 200)
	if b.cookie != "" {
		t.Fatal("reading an empty cart must not create a session")
	}
	if cart := r.json(t); num(cart, "itemCount") != 0 || len(cart["packages"].([]any)) != 0 {
		t.Fatalf("empty cart = %v", cart)
	}

	// A search card's offerId is the product ATIN; it resolves to the in-stock variant.
	r = b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEA0001", "quantity": 2})
	mustStatus(t, r, 200)
	ck := r.header.Get("Set-Cookie")
	for _, attr := range []string{"at_session=", "HttpOnly", "Secure", "SameSite=Lax", "Path=/"} {
		if !strings.Contains(ck, attr) {
			t.Fatalf("Set-Cookie %q lacks %s", ck, attr)
		}
	}
	cart := r.json(t)
	ls := lines(t, cart)
	if len(ls) != 1 || ls[0]["lineId"] != "ATSHEAV001" || ls[0]["atin"] != "ATSHEA0001" || num(ls[0], "quantity") != 2 || ls[0]["selected"] != true {
		t.Fatalf("lines = %v", ls)
	}
	if ls[0]["department"] != "Beauty" || ls[0]["unit"] != "8 oz" || num(ls[0], "maxQuantity") != 40 || ls[0]["stockStatus"] != "IN_STOCK" {
		t.Fatalf("line enrichment = %v", ls[0])
	}
	pkg := cart["packages"].([]any)[0].(map[string]any)
	if pkg["packageId"] != "org_kente:SELLER" || pkg["heading"] != "Shipped by Kente Naturals" || pkg["freeShippingNudge"] == nil {
		t.Fatalf("package = %v", pkg)
	}

	// Stock caps: 3 left of the jollof seasoning.
	r = b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 4})
	mustStatus(t, r, 409)
	p := r.json(t)
	if p["code"] != "STOCK_LIMIT" || num(p, "available") != 3 || num(p, "inCart") != 0 || num(p, "requested") != 4 || num(p, "maxQuantity") != 3 {
		t.Fatalf("stock problem = %v", p)
	}
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 2}), 200)
	r = b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 2})
	mustStatus(t, r, 409)
	p = r.json(t)
	if num(p, "inCart") != 2 || num(p, "requested") != 4 || p["detail"] != "Only 3 available — 2 already in your cart." {
		t.Fatalf("stock problem with cart = %v", p)
	}
	r = b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATGARIV001", "quantity": 1})
	mustStatus(t, r, 409)
	if c := problemCode(t, r); c != "OUT_OF_STOCK" {
		t.Fatalf("code = %s", c)
	}
	if c := problemCode(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATZZZZZZZZ", "quantity": 1})); c != "OFFER_NOT_FOUND" {
		t.Fatalf("unknown offer code = %s", c)
	}
	r = b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 100})
	mustStatus(t, r, 422)
	if r.json(t)["errors"].(map[string]any)["quantity"] == nil {
		t.Fatal("quantity error missing")
	}

	// Selection lives in the BFF.
	r = b.do("PATCH", "/v1/cart/items/ATSHEAV001", map[string]any{"selected": false})
	mustStatus(t, r, 200)
	cart = r.json(t)
	if cart["allSelected"] != false || num(cart, "selectedCount") != 2 || num(cart, "itemCount") != 4 || num(cart, "subtotalMinor") != 2*599 {
		t.Fatalf("after deselect = %v", cart)
	}
	cart = b.do("GET", "/v1/cart", nil).json(t)
	if cart["allSelected"] != false {
		t.Fatal("selection must persist")
	}
	cart = b.do("PUT", "/v1/cart/selection", map[string]any{"selected": true}).json(t)
	if cart["allSelected"] != true || num(cart, "subtotalMinor") != 2*1250+2*599 || num(cart, "sellerCount") != 2 {
		t.Fatalf("after select all = %v", cart)
	}

	// Quantity changes are capped too.
	r = b.do("PATCH", "/v1/cart/items/ATJOLLOFV1", map[string]any{"quantity": 5})
	mustStatus(t, r, 409)
	if p := r.json(t); num(p, "inCart") != 2 || num(p, "requested") != 5 {
		t.Fatalf("patch stock problem = %v", p)
	}
	mustStatus(t, b.do("PATCH", "/v1/cart/items/ATJOLLOFV1", map[string]any{"quantity": 3}), 200)
	if c := problemCode(t, b.do("PATCH", "/v1/cart/items/ATNOPE0000", map[string]any{"quantity": 1})); c != "LINE_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, b.do("PATCH", "/v1/cart/items/ATJOLLOFV1", map[string]any{}), 422)

	// Save for later and back.
	cart = b.do("POST", "/v1/cart/items/ATSHEAV001/save-for-later", nil).json(t)
	saved := cart["saved"].([]any)
	if len(saved) != 1 || saved[0].(map[string]any)["savedId"] != "ATSHEAV001" || saved[0].(map[string]any)["available"] != true {
		t.Fatalf("saved = %v", saved)
	}
	if c := problemCode(t, b.do("POST", "/v1/cart/items/ATSHEAV001/save-for-later", nil)); c != "LINE_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
	cart = b.do("POST", "/v1/cart/saved/ATSHEAV001/move-to-cart", nil).json(t)
	for _, l := range lines(t, cart) {
		if l["lineId"] == "ATSHEAV001" && num(l, "quantity") != 1 {
			t.Fatalf("moved line quantity = %v, want 1", l["quantity"])
		}
	}
	if c := problemCode(t, b.do("DELETE", "/v1/cart/saved/ATSHEAV001", nil)); c != "SAVED_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
	cart = b.do("DELETE", "/v1/cart/items/ATSHEAV001", nil).json(t)
	if len(lines(t, cart)) != 1 {
		t.Fatalf("after delete = %v", cart)
	}
	if c := problemCode(t, b.do("DELETE", "/v1/cart/items/ATSHEAV001", nil)); c != "LINE_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
}

func TestCSRFOriginCheck(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}), 200) // creates the session
	if b.cookie == "" {
		t.Fatal("expected a session cookie")
	}
	b.origin = ""
	r := b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "77042"})
	mustStatus(t, r, 403)
	if c := problemCode(t, r); c != "FORBIDDEN" {
		t.Fatalf("code = %s", c)
	}
	b.origin = "https://evil.example"
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 1}), 403)

	// No cookie and no Origin (native client before sign-in) is allowed; bearer requests are not cookie-based.
	anon := newBrowser(t, b.h)
	anon.origin = ""
	mustStatus(t, anon.do("PUT", "/v1/delivery-location", map[string]any{"zip": "77042"}), 200)
}

func TestCORSPreflightAndHeaders(t *testing.T) {
	bff, _ := setup(t, testkit.Options{})
	req := httptest.NewRequestWithContext(context.Background(), "OPTIONS", "http://bff.internal/v1/cart/items", nil)
	req.Header.Set("Origin", testkit.WebOrigin)
	req.Header.Set("Access-Control-Request-Method", "POST")
	rec := httptest.NewRecorder()
	bff.Handler.ServeHTTP(rec, req)
	if rec.Code != 204 || rec.Header().Get("Access-Control-Allow-Origin") != testkit.WebOrigin || rec.Header().Get("Access-Control-Allow-Credentials") != "true" {
		t.Fatalf("preflight = %d %v", rec.Code, rec.Header())
	}
	req = httptest.NewRequestWithContext(context.Background(), "OPTIONS", "http://bff.internal/v1/cart/items", nil)
	req.Header.Set("Origin", "https://evil.example")
	req.Header.Set("Access-Control-Request-Method", "POST")
	rec = httptest.NewRecorder()
	bff.Handler.ServeHTTP(rec, req)
	if rec.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("foreign origins must not be allowed")
	}
}

func TestBodyLimitAndMalformedJSON(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/cart/items", `{"offerId":"`+strings.Repeat("x", 70<<10)+`","quantity":1}`)
	mustStatus(t, r, 413)
	r = b.do("POST", "/v1/cart/items", `{"offerId":`)
	mustStatus(t, r, 400)
	if c := problemCode(t, r); c != "MALFORMED_JSON" {
		t.Fatalf("code = %s", c)
	}
}

func TestDeliveryLocation(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	loc := b.do("GET", "/v1/delivery-location", nil).json(t)
	if loc["label"] != "Houston 77042" || loc["serviceable"] != true {
		t.Fatalf("default = %v", loc)
	}
	loc = b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}).json(t)
	if loc["city"] != "Bronx" || loc["state"] != "NY" {
		t.Fatalf("set = %v", loc)
	}
	if got := b.do("GET", "/v1/delivery-location", nil).json(t); got["label"] != "Bronx 10456" {
		t.Fatalf("session zip not kept: %v", got)
	}
	if got := b.do("GET", "/v1/search?q=shea", nil).json(t); got["deliverToLabel"] != "Bronx 10456" {
		t.Fatalf("search deliverTo = %v", got["deliverToLabel"])
	}
	r := b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "99999"})
	mustStatus(t, r, 422)
	if c := problemCode(t, r); c != "ZIP_NOT_SERVICEABLE" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "1234"}), 422)
	// Reverse geocoding is not served by geo yet.
	r = b.do("PUT", "/v1/delivery-location", map[string]any{"lat": 29.7, "lon": -95.5})
	mustStatus(t, r, 503)
	if c := problemCode(t, r); c != "UPSTREAM_UNAVAILABLE" {
		t.Fatalf("code = %s", c)
	}
	sugs := b.do("GET", "/v1/geo/address-suggestions?q=9800", nil).json(t)
	s := sugs["suggestions"].([]any)[0].(map[string]any)
	if s["source"] != "osm" || s["address"].(map[string]any)["zip"] != "77042" || sugs["attribution"] != "© OpenStreetMap contributors" {
		t.Fatalf("suggestions = %v", sugs)
	}
}

func TestSearchAndSuggest(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	page := b.do("GET", "/v1/search?q=jollof&size=12", nil).json(t)
	hits := page["hits"].([]any)
	if len(hits) != 1 {
		t.Fatalf("hits = %v", hits)
	}
	h := hits[0].(map[string]any)
	if h["offerId"] != "ATJOLLOF01" || h["stockStatus"] != "LOW_STOCK" || h["lowStock"] != true || h["department"] != "Groceries" ||
		h["fulfilment"] != "AFROTRANSACT" || h["delivery"].(map[string]any)["prefix"] != "FREE delivery" {
		t.Fatalf("hit = %v", h)
	}
	facets := page["facets"].(map[string]any)
	ff := facets["fulfilment"].([]any)[0].(map[string]any)
	if ff["label"] != "Fulfilled by AfroTransact" || facets["price"].([]any)[0].(map[string]any)["value"] != "u10" ||
		facets["community"].([]any)[0].(map[string]any)["value"] != "Nigerian" || facets["delivery"].([]any)[0].(map[string]any)["value"] != "1" {
		t.Fatalf("facets = %v", facets)
	}
	if num(page, "pageCount") != 1 || page["deliverToLabel"] != "Houston 77042" {
		t.Fatalf("page = %v", page)
	}
	for _, bad := range []string{"sort=cheapest", "size=61", "price=cheap", "minRating=7", "fulfilment=DRONE"} {
		r := b.do("GET", "/v1/search?"+bad, nil)
		mustStatus(t, r, 400)
		if c := problemCode(t, r); c != "INVALID_QUERY" {
			t.Fatalf("%s: code = %s", bad, c)
		}
	}
	sug := b.do("GET", "/v1/search/suggestions?q=shea", nil).json(t)
	if sug["query"] != "shea" || len(sug["products"].([]any)) != 1 || sug["phrases"].([]any)[0].(map[string]any)["department"] != "Groceries" {
		t.Fatalf("suggestions = %v", sug)
	}
	if sug := b.do("GET", "/v1/search/suggestions?q=", nil).json(t); len(sug["products"].([]any)) != 0 {
		t.Fatal("empty query must not hit search")
	}
}

func TestProductDetail(t *testing.T) {
	bff, b := setup(t, testkit.Options{Promise: true})
	pd := b.do("GET", "/v1/products/ATSHEA0001", nil).json(t)
	prod := pd["product"].(map[string]any)
	bb := pd["buyBox"].(map[string]any)
	if prod["brand"] != "Kente" || prod["department"] != "Beauty" || prod["origin"] != "Ghana" || len(prod["variants"].([]any)) != 2 {
		t.Fatalf("product = %v", prod)
	}
	if bb["offerId"] != "ATSHEAV001" || num(bb, "availableQty") != 40 || bb["stockStatus"] != "IN_STOCK" ||
		bb["seller"].(map[string]any)["name"] != "Kente Naturals" || bb["fulfilment"] != "SELLER" {
		t.Fatalf("buy box = %v", bb)
	}
	if d := bb["delivery"].(map[string]any); num(d, "days") != 1 || !strings.HasPrefix(d["label"].(string), "Tomorrow, ") {
		t.Fatalf("promise days not used: %v", d)
	}
	if pd["soldBy"] != "Kente Naturals" || pd["unitPriceLabel"] != "$12.50 / 8 oz" || pd["returnsPolicy"] != "Seller return policy, 14 days" {
		t.Fatalf("detail = %v", pd)
	}
	low := b.do("GET", "/v1/products/ATJOLLOF01", nil).json(t)
	if low["buyBox"].(map[string]any)["stockStatus"] != "LOW_STOCK" || low["shipsFrom"] != "AfroTransact" || low["product"].(map[string]any)["bullets"].([]any)[1] != "No MSG" {
		t.Fatalf("low stock = %v", low)
	}
	if out := b.do("GET", "/v1/products/ATGARI0001", nil).json(t); out["buyBox"] != nil {
		t.Fatalf("out of stock must have no buy box: %v", out["buyBox"])
	}
	v := b.do("GET", "/v1/products/ATSHEAV002", nil).json(t) // variant ATIN: that variant is selected
	for _, o := range v["product"].(map[string]any)["variants"].([]any) {
		o := o.(map[string]any)
		if o["atin"] == "ATSHEAV002" && (o["selected"] != true || o["available"] != false) {
			t.Fatalf("variant option = %v", o)
		}
	}
	r := b.do("GET", "/v1/products/ATNOPE0000", nil)
	mustStatus(t, r, 404)
	if c := problemCode(t, r); c != "PRODUCT_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
	// Catalog reads are cached for the upstream's max-age.
	before := bff.Upstreams.CallCount("GET /v1/public/products/ATSHEA0001")
	b.do("GET", "/v1/products/ATSHEA0001", nil)
	if after := bff.Upstreams.CallCount("GET /v1/public/products/ATSHEA0001"); after != before {
		t.Fatalf("catalog product not cached: %d → %d", before, after)
	}
	bff.Upstreams.FailInventory = true
	r = b.do("GET", "/v1/products/ATSHEA0001", nil)
	mustStatus(t, r, 503)
	if r.header.Get("Retry-After") == "" || r.json(t)["type"] != "https://errors.afrotransact.com/bff.upstream-unavailable" {
		t.Fatalf("unavailable problem = %v %s", r.header, r.body)
	}
	// Stock alerts need notifications (not built yet) after the product and email checks.
	mustStatus(t, b.do("POST", "/v1/products/ATNOPE0000/stock-alerts", map[string]any{"email": "a@example.com"}), 404)
	mustStatus(t, b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{}), 422)
	mustStatus(t, b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{"email": "a@example.com"}), 503)
}

func TestPendingUpstreamsAnswer503(t *testing.T) {
	_, b := setup(t, testkit.Options{}) // content, payments, orders, notifications, promise not served
	for _, tc := range []struct{ method, path string }{
		{"GET", "/v1/site-config"},
		{"POST", "/v1/newsletter/subscriptions"},
		{"GET", "/v1/careers/jobs"},
		{"POST", "/v1/careers/jobs/eng-1/applications"},
	} {
		var body any
		switch tc.path {
		case "/v1/newsletter/subscriptions":
			body = map[string]any{"email": "ada@example.com"}
		case "/v1/careers/jobs/eng-1/applications":
			body = map[string]any{"name": "Ada Obi", "email": "ada@example.com", "linkedin": "", "workAuthorization": "Yes", "resumeAssetId": "ast_123", "resumeFileName": "cv.pdf", "consent": true}
		}
		r := b.do(tc.method, tc.path, body)
		mustStatus(t, r, 503)
		if c := problemCode(t, r); c != "UPSTREAM_UNAVAILABLE" {
			t.Fatalf("%s: code = %s", tc.path, c)
		}
	}
	// Home degrades to default rails (search-backed) instead of failing.
	home := b.do("GET", "/v1/home", nil).json(t)
	if len(home["rails"].([]any)) == 0 || home["deliverTo"].(map[string]any)["zip"] != "77042" {
		t.Fatalf("home = %v", home)
	}
}

func TestContentBackedScreens(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true})
	r := b.do("GET", "/v1/site-config", nil)
	mustStatus(t, r, 200)
	etag := r.header.Get("ETag")
	if !strings.HasPrefix(etag, `"7-`) || !strings.Contains(r.header.Get("Cache-Control"), "max-age=60") {
		t.Fatalf("headers = %v", r.header)
	}
	cfg := r.json(t)
	idps := cfg["identityProviders"].([]any)
	if len(idps) != 2 || idps[0].(map[string]any)["id"] != "google" || idps[0].(map[string]any)["enabled"] != true || idps[1].(map[string]any)["enabled"] != false {
		t.Fatalf("identity providers (content order + identity enabled flags) = %v", idps)
	}
	if len(cfg["socials"].([]any)) != 1 || len(cfg["legal"].([]any)) != 2 || cfg["appLinks"].(map[string]any)["android"] != nil {
		t.Fatalf("site config = %v", cfg)
	}
	b.headers["If-None-Match"] = etag
	mustStatus(t, b.do("GET", "/v1/site-config", nil), http.StatusNotModified)
	delete(b.headers, "If-None-Match")
	if n := bff.Upstreams.CallCount("GET /v1/site-config"); n != 1 {
		t.Fatalf("site config fetched %d times; want 1 (cached)", n)
	}
	home := b.do("GET", "/v1/home", nil).json(t)
	rails := home["rails"].([]any)
	if len(home["announcements"].([]any)) != 1 || len(home["hero"].([]any)) != 1 || len(home["tiles"].([]any)) != 1 ||
		home["banners"].([]any)[0].(map[string]any)["slot"] != "home-mid" || len(rails) != 2 || rails[0].(map[string]any)["title"] != "Pantry staples" {
		t.Fatalf("home = %v", home)
	}
	if items := rails[0].(map[string]any)["items"].([]any); len(items) != 2 {
		t.Fatalf("OR rail should merge jollof and garri hits: %v", items)
	}
	if picked := rails[1].(map[string]any)["items"].([]any); len(picked) != 1 || picked[0].(map[string]any)["offerId"] != "ATSHEAV001" {
		t.Fatalf("atin rail = %v", picked)
	}
	if jobs := b.do("GET", "/v1/careers/jobs", nil).json(t)["jobs"].([]any); len(jobs) != 1 {
		t.Fatalf("closed roles must be hidden: %v", jobs)
	}
	// New York is switched off in content's markets fixture.
	if loc := b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "10456"}).json(t); loc["serviceable"] != false {
		t.Fatalf("eligibility = %v", loc)
	}
	r = b.do("POST", "/v1/newsletter/subscriptions", map[string]any{"email": "ada@example.com"})
	mustStatus(t, r, 202)
	mustStatus(t, b.do("POST", "/v1/newsletter/subscriptions", map[string]any{"email": "nope"}), 422)
	if jobs := b.do("GET", "/v1/careers/jobs", nil).json(t)["jobs"].([]any); len(jobs) != 1 {
		t.Fatalf("jobs = %v", jobs)
	}
	app := map[string]any{"name": "Ada Obi", "email": "ada@example.com", "linkedin": "", "workAuthorization": "Yes", "resumeAssetId": "ast_123", "resumeFileName": "cv.pdf", "consent": true}
	mustStatus(t, b.do("POST", "/v1/careers/jobs/eng-1/applications", app), 201)
	r = b.do("POST", "/v1/careers/jobs/eng-9/applications", app)
	mustStatus(t, r, 404)
	if c := problemCode(t, r); c != "JOB_NOT_FOUND" {
		t.Fatalf("code = %s", c)
	}
	r = b.do("POST", "/v1/careers/jobs/eng-1/applications", app)
	if rc := r.json(t); rc["reference"] != "CAR-100001" {
		t.Fatalf("receipt = %v", rc)
	}
	delete(app, "resumeAssetId")
	r = b.do("POST", "/v1/careers/jobs/eng-1/applications", app)
	mustStatus(t, r, 422)
	if r.json(t)["errors"].(map[string]any)["resumeAssetId"] == nil {
		t.Fatal("résumé asset id is required")
	}
}
