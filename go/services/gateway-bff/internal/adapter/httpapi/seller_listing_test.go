package httpapi_test

import (
	"bytes"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// Templates and error reports are CSV with "#" comment lines (one field) above the header: the contract checks
// them as text, not as a rectangular table.
func init() {
	openapi3filter.RegisterBodyDecoder("text/csv", func(r io.Reader, _ http.Header, _ *openapi3.SchemaRef,
		_ openapi3filter.EncodingFn,
	) (any, error) {
		b, err := io.ReadAll(r)
		return string(b), err
	})
}

// multipartFile builds a multipart/form-data body with one file part and form fields.
func multipartFile(t *testing.T, name, content string, fields map[string]string) (string, string) {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	part, err := w.CreateFormFile("file", name)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write([]byte(content))
	for k, v := range fields {
		_ = w.WriteField(k, v)
	}
	_ = w.Close()
	return buf.String(), w.FormDataContentType()
}

// exerciseSellerListing drives the one-step product form (S307) and the bulk upload (S308) through the real
// handlers; the caller validates every answer against gateway-bff-seller.yaml.
func exerciseSellerListing(t *testing.T, bff *testkit.BFF, b *browser) {
	t.Helper()
	// The product type carries attribute kinds, units, sections and traits for the conditional sections.
	if r := b.do("GET", "/v1/seller/catalog/product-types/grocery", nil); r.status != 200 ||
		!strings.Contains(string(r.body), `"traits":{"food":true`) || !strings.Contains(string(r.body), `"units":["days","months"]`) ||
		!strings.Contains(string(r.body), `"section":"FOOD"`) {
		t.Fatalf("product type = %d %s", r.status, r.body)
	}
	// Edit: load, save with the resolved ship-from snapshot, stale version.
	p := b.do("GET", "/v1/seller/listings/prod-1", nil)
	if p.status != 200 || p.json(t)["categoryPath"] != "Groceries › Grains" || p.json(t)["status"] != "REJECTED" ||
		p.json(t)["listing"].(map[string]any)["shipFromId"] != "addr-1" {
		t.Fatalf("product = %d %s", p.status, p.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/listings/nope", nil), 404)
	shipFrom := b.do("POST", "/v1/seller/ship-from-addresses", map[string]any{"label": "Store", "name": "Ade Okafor", "line1": "1 Main St",
		"city": "Houston", "state": "TX", "postalCode": "77002"}).json(t)["id"].(string)
	edit := map[string]any{"title": "Ofada Rice, Destoned · 5 lb bag", "attributes": map[string]string{"brand": "Ade's"},
		"images": []map[string]any{{"url": "https://img.example/ofada.webp", "contentType": "image/webp"}}, "variationTheme": "",
		"variants": []map[string]any{{"options": map[string]string{}, "sellerSku": "OFADA-5LB", "gtinExempt": true, "priceMinor": 2399,
			"compareAtMinor": 2999, "quantity": 30}},
		"listing": map[string]any{"description": "Stone-free.", "condition": "NEW", "keywords": []string{"ofada"}, "fulfilment": "SELLER",
			"handlingDays": 3, "shipFromId": shipFrom}, "version": 3}
	if r := b.do("PUT", "/v1/seller/products/prod-1", edit); r.status != 200 || r.json(t)["status"] != "IN_REVIEW" {
		t.Fatalf("update = %d %s", r.status, r.body)
	}
	if sf := bff.Upstreams.LastProductBody["listing"].(map[string]any)["shipFrom"].(map[string]any); sf["locationId"] != shipFrom || sf["city"] != "Houston" {
		t.Fatalf("ship-from snapshot = %v", sf)
	}
	edit["version"] = 2
	mustStatus(t, b.do("PUT", "/v1/seller/products/prod-1", edit), 409)
	mustStatus(t, b.do("DELETE", "/v1/seller/ship-from-addresses/"+shipFrom, nil), 200)
	edit["listing"].(map[string]any)["shipFromId"] = "someone-elses"
	mustStatus(t, b.do("PUT", "/v1/seller/products/prod-1", edit), 422)
	edit["listing"] = map[string]any{"fulfilment": "AFROTRANSACT"}
	if r := b.do("PUT", "/v1/seller/products/prod-1", edit); r.status != 422 || !strings.Contains(string(r.body), "listing.packaging") {
		t.Fatalf("FBA without package = %d %s", r.status, r.body)
	}
	// Drafts: save, autosave with versions, list, read, discard.
	d := b.do("POST", "/v1/seller/product-drafts", map[string]any{"title": "Ofada", "categoryPath": "Groceries › Pantry",
		"payload": map[string]any{"title": "Ofada", "variants": []any{}}})
	mustStatus(t, d, 201)
	id := d.json(t)["id"].(string)
	mustStatus(t, b.do("POST", "/v1/seller/product-drafts", map[string]any{"payload": "not an object"}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/product-drafts", map[string]any{"productId": "nope", "payload": map[string]any{}}), 404)
	if r := b.do("PUT", "/v1/seller/product-drafts/"+id, map[string]any{"version": 0, "title": "Ofada rice",
		"payload": map[string]any{"title": "Ofada rice"}}); r.status != 200 || r.json(t)["version"] != float64(1) {
		t.Fatalf("autosave = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("PUT", "/v1/seller/product-drafts/"+id, map[string]any{"version": 0, "payload": map[string]any{}}), 409)
	mustStatus(t, b.do("PUT", "/v1/seller/product-drafts/"+id, map[string]any{"payload": map[string]any{}}), 422)
	if r := b.do("GET", "/v1/seller/product-drafts", nil); r.status != 200 || len(r.json(t)["items"].([]any)) != 1 {
		t.Fatalf("drafts = %d %s", r.status, r.body)
	}
	if r := b.do("GET", "/v1/seller/product-drafts/"+id, nil); r.status != 200 || r.json(t)["payload"].(map[string]any)["title"] != "Ofada rice" {
		t.Fatalf("draft = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("DELETE", "/v1/seller/product-drafts/"+id, nil), 204)
	mustStatus(t, b.do("GET", "/v1/seller/product-drafts/"+id, nil), 404)
	mustStatus(t, b.do("DELETE", "/v1/seller/product-drafts/"+id, nil), 404)
	// Context and the AI listing writer (Seller Growth includes it; the owner may use it).
	if r := b.do("GET", "/v1/seller/catalog/listing-context", nil); r.status != 200 ||
		r.json(t)["aiListingWriter"].(map[string]any)["available"] != true || num(r.json(t)["importLimits"].(map[string]any), "maxRows") != 5000 {
		t.Fatalf("context = %d %s", r.status, r.body)
	}
	if r := b.do("POST", "/v1/seller/catalog/listing-writer", map[string]any{"productType": "grocery", "title": "Ofada rice",
		"attributes": map[string]string{"brand": "Ade's"}, "tone": "warm"}); r.status != 200 || len(r.json(t)["bullets"].([]any)) != 2 {
		t.Fatalf("writer = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/catalog/listing-writer", map[string]any{"productType": "", "title": "x"}), 422)
	bff.Upstreams.SetSellerPlan("seller_essentials")
	if r := b.do("POST", "/v1/seller/catalog/listing-writer", map[string]any{"productType": "grocery", "title": "Ofada rice"}); r.status != 403 ||
		r.json(t)["code"] != "PLAN_FEATURE_REQUIRED" {
		t.Fatalf("writer without the plan = %d %s", r.status, r.body)
	}
	if r := b.do("GET", "/v1/seller/catalog/listing-context", nil); r.json(t)["aiListingWriter"].(map[string]any)["reason"] != "PLAN" {
		t.Fatalf("context without the plan = %s", r.body)
	}
	bff.Upstreams.SetSellerPlan("seller_growth")

	// Bulk upload: templates, upload (file relayed as multipart), preview rows, report, start, history.
	tpl := b.do("GET", "/v1/seller/imports/template?productType=grocery&format=xlsx", nil)
	if tpl.status != 200 || !bytes.HasPrefix(tpl.body, []byte("PK")) || !strings.Contains(tpl.header.Get("Content-Disposition"), "afrotransact-products-grocery.xlsx") ||
		tpl.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("template = %d %v", tpl.status, tpl.header)
	}
	if csv := b.do("GET", "/v1/seller/imports/template?format=csv", nil); csv.status != 200 || !strings.HasPrefix(csv.header.Get("Content-Type"), "text/csv") {
		t.Fatalf("csv template = %d %v", csv.status, csv.header)
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports/template?format=pdf", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/imports/template?productType=nope", nil), 404)

	body, ct := multipartFile(t, "pantry.xlsx", "PK fake", map[string]string{"mode": "CREATE", "productType": "grocery"})
	b.headers["Content-Type"] = ct
	up := b.do("POST", "/v1/seller/imports", body)
	if up.status != 201 || up.json(t)["errorRows"] != float64(2) || up.json(t)["reportUrl"] != "/api/bff/v1/seller/imports/imp-errors/report?format=xlsx" {
		t.Fatalf("upload = %d %s", up.status, up.body)
	}
	if last := bff.Upstreams.LastUpload; last["name"] != "pantry.xlsx" || last["mode"] != "CREATE" || last["productType"] != "grocery" ||
		!strings.Contains(last["contentType"], "spreadsheetml") || last["uploadedByName"] == "" || last["body"] != "PK fake" {
		t.Fatalf("relayed upload = %v", last)
	}
	body, ct = multipartFile(t, "pantry.csv", "NO-HEADER", nil)
	b.headers["Content-Type"] = ct
	if r := b.do("POST", "/v1/seller/imports", body); r.status != 422 || !strings.Contains(string(r.body), "Missing required column") {
		t.Fatalf("rejected file = %d %s", r.status, r.body)
	}
	body, ct = multipartFile(t, "pantry.xls", "D0CF", nil)
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/imports", body), 422)
	body, ct = multipartFile(t, "big.csv", strings.Repeat("x", 10<<20+1), nil)
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/imports", body), 413)
	body, ct = multipartFile(t, "pantry.csv", "SKU\nA-1", map[string]string{"mode": "MERGE"})
	b.headers["Content-Type"] = ct
	mustStatus(t, b.do("POST", "/v1/seller/imports", body), 422)
	delete(b.headers, "Content-Type")

	if r := b.do("GET", "/v1/seller/imports/"+testkit.ImportWithErrors, nil); r.status != 200 || r.json(t)["status"] != "VALIDATED" {
		t.Fatalf("import = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports/nope", nil), 404)
	if r := b.do("GET", "/v1/seller/imports/"+testkit.ImportWithErrors+"/rows?status=ERROR", nil); r.status != 200 ||
		len(r.json(t)["items"].([]any)) != 1 {
		t.Fatalf("error rows = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports/"+testkit.ImportWithErrors+"/rows?status=BROKEN", nil), 422)
	rep := b.do("GET", "/v1/seller/imports/"+testkit.ImportWithErrors+"/report?format=csv", nil)
	if rep.status != 200 || !strings.Contains(string(rep.body), "'=1+1") || !strings.Contains(rep.header.Get("Content-Disposition"), "pantry-report.csv") {
		t.Fatalf("report = %d %s", rep.status, rep.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports/nope/report", nil), 404)
	if r := b.do("POST", "/v1/seller/imports/"+testkit.ImportWithErrors+"/start", nil); r.status != 409 || r.json(t)["code"] != "CONFLICT" {
		t.Fatalf("start with errors = %d %s", r.status, r.body)
	}
	if r := b.do("POST", "/v1/seller/imports/"+testkit.ImportWithErrors+"/start", map[string]any{"validOnly": true}); r.status != 202 ||
		r.json(t)["status"] != "QUEUED" {
		t.Fatalf("start valid rows = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/imports/nope/start", map[string]any{"validOnly": true}), 202)
	if r := b.do("GET", "/v1/seller/imports?page=0&size=10", nil); r.status != 200 || num(r.json(t), "total") != 2 {
		t.Fatalf("history = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/imports?size=500", nil), 422)
}
