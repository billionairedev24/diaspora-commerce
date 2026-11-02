package httpapi_test

import (
	"net/http"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// postFile posts a multipart body the way a browser sends an upload.
func postFile(b *browser, path, body, contentType string) resp {
	b.headers["Content-Type"] = contentType
	defer delete(b.headers, "Content-Type")
	return b.do("POST", path, body)
}

func chartInput(name string, size string) map[string]any {
	return map[string]any{"name": name, "unitSystem": "CM", "productTypeCode": "dress",
		"columns": []any{map[string]any{"label": "Bust"}, map[string]any{"label": "Waist"}},
		"rows": []any{map[string]any{"size": size, "values": map[string]any{
			"bust": map[string]any{"min": 84, "max": 88}, "waist": map[string]any{"min": 66}}}},
		"fitNote": "TRUE_TO_SIZE", "howToMeasure": "Measure over light clothing."}
}

// exerciseSellerSizeCharts drives the seller's size chart API (and the sizing fields of the product form) through the
// real handlers; the caller validates every answer against gateway-bff-seller.yaml.
func exerciseSellerSizeCharts(t *testing.T, bff *testkit.BFF, b *browser) {
	t.Helper()
	// What a sized category suggests: sizes and measurement columns (and nothing for a category without sizes).
	sug := b.do("GET", "/v1/seller/size-charts/suggestions?productType=dress", nil)
	if sug.status != 200 || sug.json(t)["sized"] != true || len(sug.json(t)["sizes"].([]any)) != 5 ||
		sug.json(t)["measurements"].([]any)[0].(map[string]any)["key"] != "bust" || sug.json(t)["sizesAllowCustomValues"] != true {
		t.Fatalf("suggestions = %d %s", sug.status, sug.body)
	}
	if r := b.do("GET", "/v1/seller/size-charts/suggestions?productType=grocery", nil); r.status != 200 || r.json(t)["sized"] != false {
		t.Fatalf("unsized suggestions = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/suggestions", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/suggestions?productTypeId=nope", nil), 422)

	// Write, read, edit, duplicate, retire.
	created := b.do("POST", "/v1/seller/size-charts", chartInput("Adire dresses", "S"))
	if created.status != 201 || created.json(t)["ownerOrgId"] != testkit.SellerOrgID || created.json(t)["alternateUnit"] != "IN" {
		t.Fatalf("create = %d %s", created.status, created.body)
	}
	id := created.json(t)["id"].(string)
	if row := created.json(t)["rows"].([]any)[0].(map[string]any); row["alternateValues"].(map[string]any)["bust"] == nil {
		t.Fatalf("converted values = %v", row)
	}
	dup := b.do("POST", "/v1/seller/size-charts", chartInput("adire DRESSES", "S"))
	if dup.status != 422 || dup.json(t)["errors"].(map[string]any)["name"] == nil {
		t.Fatalf("duplicate name = %d %s", dup.status, dup.body)
	}
	mustStatus(t, b.do("POST", "/v1/seller/size-charts", "[1,2]"), 422)
	mustStatus(t, b.do("POST", "/v1/seller/size-charts", "not json"), 400)
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/"+id, nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/not-a-chart", nil), 404)
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/5c1e0000-0000-4000-8000-0000000000ff", nil), 404)
	list := b.do("GET", "/v1/seller/size-charts?productType=dress&q=adire&size=10", nil)
	if list.status != 200 || list.json(t)["total"] != float64(1) {
		t.Fatalf("list = %d %s", list.status, list.body)
	}
	if q := bff.Upstreams.LastSizeChartQuery; q.Get("productType") != "dress" || q.Get("q") != "adire" || q.Get("size") != "10" || q.Get("page") != "0" {
		t.Fatalf("query relayed to catalog = %v", q)
	}
	mustStatus(t, b.do("GET", "/v1/seller/size-charts?size=1000", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/size-charts?productTypeId=nope", nil), 422)
	edit := chartInput("Adire dresses 2026", "S")
	edit["version"] = 0
	if r := b.do("PUT", "/v1/seller/size-charts/"+id, edit); r.status != 200 || r.json(t)["version"] != float64(1) || r.json(t)["name"] != "Adire dresses 2026" {
		t.Fatalf("update = %d %s", r.status, r.body)
	}
	if bff.Upstreams.LastSizeChartBody["version"] != float64(0) {
		t.Fatalf("version relayed = %v", bff.Upstreams.LastSizeChartBody)
	}
	mustStatus(t, b.do("PUT", "/v1/seller/size-charts/"+id, edit), 409)
	mustStatus(t, b.do("PUT", "/v1/seller/size-charts/5c1e0000-0000-4000-8000-0000000000ff", edit), 404)
	copied := b.do("POST", "/v1/seller/size-charts/"+id+"/duplicate", map[string]any{})
	if copied.status != 201 || copied.json(t)["name"] != "Adire dresses 2026 (copy)" {
		t.Fatalf("duplicate = %d %s", copied.status, copied.body)
	}
	if r := b.do("POST", "/v1/seller/size-charts/"+id+"/duplicate", map[string]any{"name": "Kente dresses"}); r.status != 201 || r.json(t)["name"] != "Kente dresses" {
		t.Fatalf("named duplicate = %d %s", r.status, r.body)
	}
	if r := b.do("DELETE", "/v1/seller/size-charts/"+id, nil); r.status != 200 || r.json(t)["retired"] != true {
		t.Fatalf("retire = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("DELETE", "/v1/seller/size-charts/not-a-chart", nil), 404)

	// Bulk upload: the template per product type, a dry run, the real upload, and a bad row.
	tpl := b.do("GET", "/v1/seller/size-charts/import-template?productType=dress&format=csv", nil)
	if tpl.status != 200 || !strings.Contains(string(tpl.body), "Size chart name *") {
		t.Fatalf("template = %d %s", tpl.status, tpl.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/import-template?format=pdf", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/size-charts/import-template?productType=Bad-Type", nil), 422)
	csv := "Size chart name *,Unit,Size *,Bust *,Waist *\r\nAdire dresses,cm,S,84-88,66-70\r\n"
	body, ct := multipartFile(t, "charts.csv", csv, map[string]string{"productType": "dress", "dryRun": "true"})
	dry := postFile(b, "/v1/seller/size-charts/import", body, ct)
	if dry.status != 200 || dry.json(t)["applied"] != false || dry.json(t)["charts"].([]any)[0].(map[string]any)["action"] != "CREATE" {
		t.Fatalf("dry run = %d %s", dry.status, dry.body)
	}
	if q := bff.Upstreams.LastSizeChartQuery; q.Get("dryRun") != "true" || q.Get("productType") != "dress" {
		t.Fatalf("upload query = %v", q)
	}
	body, ct = multipartFile(t, "charts.csv", csv, map[string]string{"productType": "dress"})
	if r := postFile(b, "/v1/seller/size-charts/import", body, ct); r.status != 200 || r.json(t)["applied"] != true || r.json(t)["created"] != float64(1) {
		t.Fatalf("upload = %d %s", r.status, r.body)
	}
	body, ct = multipartFile(t, "charts.csv", strings.Replace(csv, "84-88", "abc", 1), nil)
	bad := postFile(b, "/v1/seller/size-charts/import", body, ct)
	if bad.status != 200 || bad.json(t)["failed"] != float64(1) {
		t.Fatalf("bad row = %d %s", bad.status, bad.body)
	}
	if m := bad.json(t)["rows"].([]any)[0].(map[string]any)["messages"].([]any)[0].(map[string]any); m["column"] != "m:bust" || m["columnIndex"] != float64(3) {
		t.Fatalf("row message = %v", m)
	}
	body, ct = multipartFile(t, "charts.pdf", "x", nil)
	mustStatus(t, postFile(b, "/v1/seller/size-charts/import", body, ct), 422)
	body, ct = multipartFile(t, "charts.csv", "", nil)
	mustStatus(t, postFile(b, "/v1/seller/size-charts/import", body, ct), 422)
	body, ct = multipartFile(t, "charts.csv", csv, map[string]string{"dryRun": "maybe"})
	mustStatus(t, postFile(b, "/v1/seller/size-charts/import", body, ct), 422)

	// The product form: the size chart and fit note travel in listing and come back on the edit form; the product
	// type schema carries the sizing trait and the dimensions that allow custom values.
	if r := b.do("GET", "/v1/seller/catalog/product-types/dress", nil); r.status != 200 {
		t.Fatalf("dress type = %d %s", r.status, r.body)
	} else if sz := r.json(t)["traits"].(map[string]any)["sizing"].(map[string]any); sz["sizeChartRequired"] != true || sz["sizeDimension"] != "Size" ||
		len(sz["suggestedMeasurements"].([]any)) != 2 {
		t.Fatalf("sizing trait = %v", sz)
	} else if dim := r.json(t)["themes"].([]any)[0].(map[string]any)["dimensions"].([]any)[0].(map[string]any); dim["allowCustomValues"] != true {
		t.Fatalf("size dimension = %v", dim)
	}
	if r := b.do("GET", "/v1/seller/catalog/product-types/grocery", nil); r.status != 200 || r.json(t)["traits"].(map[string]any)["sizing"] != nil {
		t.Fatalf("grocery type = %d %s", r.status, r.body)
	}
	form := map[string]any{"title": "Ofada Rice, Destoned · 5 lb bag", "attributes": map[string]string{"brand": "Ade's"},
		"images": []map[string]any{{"url": "https://img.example/ofada.webp", "contentType": "image/webp"}}, "variationTheme": "",
		"variants": []map[string]any{{"options": map[string]string{}, "sellerSku": "OFADA-5LB", "gtinExempt": true, "priceMinor": 2399, "quantity": 30}},
		"listing":  map[string]any{"description": "Stone-free.", "sizeChartId": id, "fitNote": "RUNS_LARGE"}, "version": 3}
	mustStatus(t, b.do("PUT", "/v1/seller/products/prod-1", form), 200)
	if l := bff.Upstreams.LastProductBody["listing"].(map[string]any); l["sizeChartId"] != id || l["fitNote"] != "RUNS_LARGE" {
		t.Fatalf("size chart in the listing sent to catalog = %v", l)
	}
	if r := b.do("GET", "/v1/seller/listings/prod-1", nil); r.status != 200 {
		t.Fatalf("listing = %d %s", r.status, r.body)
	} else if l := r.json(t)["listing"].(map[string]any); l["sizeChartId"] != "5c1e0000-0000-4000-8000-000000000009" || l["fitNote"] != "TRUE_TO_SIZE" {
		t.Fatalf("listing size chart = %v", l)
	}
}

func TestSellerSizeChartsNeedTheListingsModule(t *testing.T) {
	_, guest := setup(t, testkit.Options{SellerPending: true})
	for _, path := range []string{"/v1/seller/size-charts", "/v1/seller/size-charts/suggestions?productType=dress"} {
		mustStatus(t, guest.do("GET", path, nil), http.StatusUnauthorized)
	}
	mustStatus(t, guest.do("POST", "/v1/seller/size-charts", chartInput("Adire dresses", "S")), http.StatusUnauthorized)
}

func TestSizedProductPageShowsTheSellersSizeChart(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	pd := b.do("GET", "/v1/products/"+testkit.SizedProductATIN, nil)
	if pd.status != 200 {
		t.Fatalf("product = %d %s", pd.status, pd.body)
	}
	chart := pd.json(t)["sizeChart"].(map[string]any)
	if chart["name"] != "Adire dresses" || chart["fitNote"] != "RUNS_SMALL" || chart["alternateUnit"] != "IN" ||
		chart["howToMeasure"] != "Measure over light clothing." || len(chart["rows"].([]any)) != 3 || len(chart["columns"].([]any)) != 2 {
		t.Fatalf("size chart = %v", chart)
	}
	if first := chart["rows"].([]any)[0].(map[string]any); first["size"] != "S" || first["values"].(map[string]any)["bust"].(map[string]any)["max"] != float64(88) ||
		first["alternateValues"].(map[string]any)["bust"] == nil {
		t.Fatalf("first row = %v", first)
	}
	variants := pd.json(t)["product"].(map[string]any)["variants"].([]any)
	if len(variants) != 3 || variants[1].(map[string]any)["options"].(map[string]any)["Size"] != "UK 12" ||
		variants[2].(map[string]any)["options"].(map[string]any)["Color"] != "Ankara blue print" {
		t.Fatalf("variants carry the seller's own sizes and colours: %v", variants)
	}
	if other := b.do("GET", "/v1/products/ATSHEA0001", nil).json(t); other["sizeChart"] != nil {
		t.Fatalf("a product without a chart has none: %v", other["sizeChart"])
	}
}
