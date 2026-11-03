package httpapi_test

import (
	"archive/zip"
	"bytes"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/xuri/excelize/v2"
)

// TestStaffTablesSearchEveryColumnSortAndExport covers the shared table model on the staff routes: one search over
// every column (orders' upstream only searches order numbers), sort by any column, the scan cap (partial), and exports
// that follow the table (search, sort, visible columns, selection) in CSV, Excel with native charts and PDF.
func TestStaffTablesSearchEveryColumnSortAndExport(t *testing.T) {
	f := &fakePlatform{payoutSt: "REQUESTED"}
	raw := staffBFF(t, f)
	get := func(path string, want int) map[string]any {
		t.Helper()
		rec := raw("admin", "GET", path, nil)
		if rec.Code != want {
			t.Fatalf("GET %s = %d, want %d: %s", path, rec.Code, want, rec.Body.String())
		}
		return jsonOf(t, rec)
	}
	post := func(path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw("admin", "POST", path, body)
		if rec.Code != want {
			t.Fatalf("POST %s = %d, want %d: %s", path, rec.Code, want, rec.Body.String())
		}
		return jsonOf(t, rec)
	}
	rows := func(p map[string]any) []map[string]any {
		out := []map[string]any{}
		for _, r := range p["rows"].([]any) {
			out = append(out, r.(map[string]any))
		}
		return out
	}

	// Customer, seller, amount as text, status label: none of these are searched by the orders upstream.
	for q, want := range map[string]int{"amara": 2, "kente": 2, "27.64": 2, "placed": 2, "AT-000002": 1, "amara+zzz": 0} {
		p := get("/v1/staff/resources/orders?q="+q, 200)
		if len(rows(p)) != want || p["total"].(float64) != float64(want) || p["nextCursor"] != nil {
			t.Errorf("q=%s rows = %d (%v)", q, len(rows(p)), p["total"])
		}
	}
	p := get("/v1/staff/resources/orders?sort=orderNumber,desc", 200)
	if r := rows(p); len(r) != 2 || r[0]["id"] != "AT-000002" || fmt.Sprint(p["sort"]) != "[orderNumber,desc]" {
		t.Fatalf("sorted = %v %v", r, p["sort"])
	}
	if cols := p["columns"].([]any); cols[0].(map[string]any)["sortable"] != true || p["searchable"] != true {
		t.Fatalf("columns = %v", cols[0])
	}
	get("/v1/staff/resources/orders?sort=nope,asc", 422)
	get("/v1/staff/resources/orders?sort=orderNumber,sideways", 422)

	// A big table: search and sort read the first 5,000 rows and say so. Rows read for searching are cached for a
	// few seconds per caller; any write through the BFF drops them.
	f.bigOrders = true
	if cached := get("/v1/staff/resources/orders?q=AT-0049&size=20", 200); cached["total"].(float64) != 0 {
		t.Fatalf("cached rows = %v", cached["total"])
	}
	post("/v1/staff/resources/orders/AT-000001/actions/shipment-status", map[string]any{"values": map[string]any{"step": "shp-1|CANCELLED", "reason": "Customer asked"}}, 200)
	big := get("/v1/staff/resources/orders?q=AT-0049&size=20", 200)
	if big["partial"] != true || big["total"].(float64) != 100 || len(rows(big)) != 20 {
		t.Fatalf("partial = %v total = %v", big["partial"], big["total"])
	}
	if next := get("/v1/staff/resources/orders?q=AT-0049&size=20&page=4", 200); len(rows(next)) != 20 || next["hasNext"] != false {
		t.Fatalf("last page = %d %v", len(rows(next)), next["hasNext"])
	}
	f.bigOrders = false

	// Exports follow the table: visible columns in order, search and sort; or only the selected rows.
	xlsx := post("/v1/staff/exports", map[string]any{"resource": "orders", "format": "xlsx", "q": "amara", "sort": []string{"orderNumber,desc"},
		"columns": []string{"orderNumber", "totalMinor", "placedAt", "status"}}, 201)
	if xlsx["rows"].(float64) != 2 || xlsx["title"] != "Orders" {
		t.Fatalf("xlsx job = %v", xlsx)
	}
	file := download(t, raw, xlsx["id"].(string))
	wb, err := excelize.OpenReader(bytes.NewReader(file))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = wb.Close() }()
	data, _ := wb.GetRows("Data")
	if strings.Join(data[0], "|") != "Order|Total|Placed|Status" || data[1][0] != "AT-000002" || data[1][1] != "$27.64" {
		t.Fatalf("data sheet = %v", data)
	}
	scope, _ := wb.GetCellValue("Summary", "A2")
	if !strings.Contains(scope, "Search “amara”") || !strings.Contains(scope, "sorted by Order (descending)") {
		t.Fatalf("scope = %q", scope)
	}
	if charts := chartParts(t, file); charts < 2 {
		t.Fatalf("charts = %d", charts)
	}
	sel := post("/v1/staff/exports", map[string]any{"resource": "orders", "format": "pdf", "ids": []string{"AT-000001"}}, 201)
	if sel["rows"].(float64) != 1 || !strings.HasSuffix(sel["fileName"].(string), ".pdf") {
		t.Fatalf("pdf job = %v", sel)
	}
	if pdf := download(t, raw, sel["id"].(string)); !bytes.HasPrefix(pdf, []byte("%PDF-")) {
		t.Fatal("not a pdf")
	}
	csv := post("/v1/staff/exports", map[string]any{"resource": "claims", "format": "csv", "q": "zzz"}, 201)
	if csv["rows"].(float64) != 0 {
		t.Fatalf("empty search export = %v", csv)
	}
	post("/v1/staff/exports", map[string]any{"resource": "orders", "format": "csv", "columns": []string{"nope"}}, 422)
	post("/v1/staff/exports", map[string]any{"resource": "orders", "format": "csv", "sort": []string{"nope,asc"}}, 422)

	// Client-side tables send their rows through the same pipeline and show up under Exports.
	ds := map[string]any{"format": "xlsx", "dataset": map[string]any{"table": "notification-templates", "title": "Templates", "scope": "Search “otp”",
		"columns": []map[string]any{{"key": "key", "label": "Template", "kind": "mono"}, {"key": "channel", "label": "Channel", "kind": "text"},
			{"key": "updatedAt", "label": "Updated", "kind": "datetime"}, {"key": "status", "label": "Status", "kind": "status"}},
		"rows": [][]any{{"delivery.otp", "sms", "2026-10-01T10:00:00Z", "ACTIVE"}, {"order.placed", "email", "2026-10-02T10:00:00Z", "DRAFT"}}}}
	job := post("/v1/staff/exports", ds, 201)
	if job["resource"] != "notification-templates" || job["title"] != "Templates" || job["rows"].(float64) != 2 {
		t.Fatalf("dataset job = %v", job)
	}
	if items := get("/v1/staff/exports", 200)["items"].([]any); len(items) != 4 {
		t.Fatalf("exports listed = %d", len(items))
	}
	bad := ds["dataset"].(map[string]any)
	bad["rows"] = [][]any{{"only one cell"}}
	post("/v1/staff/exports", ds, 422)
	bad["rows"] = [][]any{{map[string]any{"nested": true}, "x", nil, nil}}
	post("/v1/staff/exports", ds, 422)
}

func download(t *testing.T, raw func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder, id string) []byte {
	t.Helper()
	loc := raw("admin", "GET", "/v1/staff/exports/"+id+"/download", nil).Header().Get("Location")
	rec := raw("", "GET", strings.TrimPrefix(loc, "/api/bff"), nil)
	if rec.Code != 200 {
		t.Fatalf("file %s = %d", loc, rec.Code)
	}
	return rec.Body.Bytes()
}

func chartParts(t *testing.T, file []byte) int {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(file), int64(len(file)))
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, f := range zr.File {
		if strings.HasPrefix(f.Name, "xl/charts/chart") {
			n++
		}
	}
	return n
}
