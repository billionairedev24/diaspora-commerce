package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Staff members of the Pre-orders module (design roles: Marketing FULL, Operations EDIT, Support APPROVE, Finance VIEW).
var preorderStaff = map[string]map[string]any{
	"marketing": {"id": "stf-20", "firstName": "Bisi", "lastName": "Ade", "email": "bisi@afrotransact.com", "principalType": "STAFF",
		"roles": []string{"platform.marketing"}, "permissions": []string{"delivery-runs:read", "preorders:manage", "preorders:export", "preorders:late-add", "users:read"}},
	"ops": {"id": "stf-21", "firstName": "Tunde", "lastName": "Bello", "email": "tunde@afrotransact.com", "principalType": "STAFF",
		"roles": []string{"platform.operations_manager"}, "permissions": []string{"delivery-runs:read", "preorders:manage", "preorders:export", "preorders:late-add", "users:read"}},
	"cs": {"id": "stf-22", "firstName": "Grace", "lastName": "Adjei", "email": "grace@afrotransact.com", "principalType": "STAFF",
		"roles": []string{"platform.customer_support"}, "permissions": []string{"delivery-runs:read", "preorders:late-add", "users:read", "support:respond"}},
	"finance": {"id": "stf-23", "firstName": "Efe", "lastName": "Oko", "email": "efe@afrotransact.com", "principalType": "STAFF",
		"roles": []string{"platform.finance"}, "permissions": []string{"delivery-runs:read", "preorders:export"}},
}

// fakeDeliveryRuns serves delivery-runs' staff pre-order routes and the identity calls of the module; everything
// else goes to the shared fake platform.
type fakeDeliveryRuns struct {
	*fakePlatform
	mu       sync.Mutex
	ifMatch  map[string]string
	idemKeys map[string]string
	paths    []string
}

func (f *fakeDeliveryRuns) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if p == "/v1/me" {
		if me, ok := preorderStaff[token]; ok {
			writeJ(w, 200, me)
			return
		}
	}
	if strings.HasPrefix(p, "/v1/admin/users/") && strings.HasSuffix(p, "/addresses") {
		writeJ(w, 200, []any{map[string]any{"id": "adr-1", "label": "Home", "fullName": "Amara Nwosu", "phone": "+18065550101",
			"line1": "2401 Broadway", "line2": "Apt 4", "city": "Lubbock", "state": "TX", "postalCode": "79401", "country": "US",
			"instructions": "Gate 4", "isDefault": true}})
		return
	}
	switch {
	case p == "/v1/addresses/validate":
		status := "VALID"
		if b := f.record(r); b["postalCode"] == "00000" {
			status = "UNDELIVERABLE"
		}
		writeJ(w, 200, map[string]any{"status": status, "standardized": map[string]any{"line1": "2401 BROADWAY", "city": "LUBBOCK", "state": "TX",
			"postalCode": "79401"}})
		return
	case p == "/v1/uploads":
		f.record(r)
		writeJ(w, 201, map[string]any{"asset": map[string]any{"id": "ast-9"}, "upload": map[string]any{"url": "https://storage.example/put/ast-9",
			"method": "PUT", "headers": map[string]any{"Content-Type": "image/jpeg"}}})
		return
	case strings.HasPrefix(p, "/v1/assets/") && strings.HasSuffix(p, "/complete"):
		f.record(r)
		writeJ(w, 200, map[string]any{"id": "ast-9", "status": "READY"})
		return
	}
	if strings.HasPrefix(p, "/v1/delivery-fees") {
		f.deliveryFees(w, r)
		return
	}
	if !strings.HasPrefix(p, "/v1/preorder") && !strings.HasPrefix(p, "/v1/places/") {
		f.fakePlatform.ServeHTTP(w, r)
		return
	}
	body := f.record(r)
	f.mu.Lock()
	key := r.Method + " " + r.URL.EscapedPath()
	f.paths = append(f.paths, key)
	f.ifMatch[key] = r.Header.Get("If-Match")
	f.idemKeys[key] = r.Header.Get("Idempotency-Key")
	f.mu.Unlock()
	window := map[string]any{"id": "win-7", "code": "LBK-7", "name": "Lubbock Pre-order #7", "status": "OPEN", "targets": []string{"US/TX/Lubbock"},
		"doorFeeOverride": nil, "pickupFeeOverride": nil, "effectiveDoorFee": 799, "effectivePickupFee": 0, "currency": "USD", "version": 3,
		"creatives": map[string]any{"HERO": map[string]any{"assetId": "ast-1", "width": 1920, "height": 640}}}
	settings := map[string]any{"enabled": true, "fallback": "SHIP", "placements": map[string]any{"ribbon": true}, "version": 2,
		"payment": map[string]any{"pickupWindow": "10am–2pm",
			"allowedMethods": []string{"CARD", "WALLET"}, "lateAdd": map[string]any{"savedCard": true, "paymentLink": true, "linkExpiryHours": 24},
			"missedDelivery": map[string]any{"offerRefund": true, "offerNewDate": true}}}
	switch {
	case p == "/v1/preorders" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "win-7", "name": "Lubbock Pre-order #7", "status": "OPEN", "count": 142}},
			"total": 1, "counts": map[string]any{"OPEN": 1}})
	case p == "/v1/preorders" && r.Method == http.MethodPost:
		out := map[string]any{"id": "win-8", "status": "DRAFT", "version": 0}
		for k, v := range body {
			out[k] = v
		}
		writeJ(w, 201, out)
	case p == "/v1/preorders" && r.Method == http.MethodPatch:
		writeJ(w, 200, map[string]any{"items": []any{
			map[string]any{"id": "win-7", "outcome": "SWITCHED", "window": map[string]any{"id": "win-7", "active": false, "switchState": "PAUSED"}},
			map[string]any{"id": "win-8", "outcome": "SKIPPED", "window": nil, "code": "delivery-runs.invalid-transition", "reason": "A draft has no switch"}},
			"switched": 1, "unchanged": 0, "skipped": 1})
	case strings.HasPrefix(p, "/v1/preorder-areas/regions/"):
		writeJ(w, 200, map[string]any{"country": "US", "region": "TX", "countryName": "United States", "regionName": "Texas", "regionLabel": "State",
			"wholeRegion": nil, "cities": []any{map[string]any{"key": "US/TX/Lubbock", "name": "Lubbock", "on": true, "version": 2,
				"windows": []string{"Lubbock Pre-order #7"}}}, "version": 9007199254740991})
	case p == "/v1/preorders/win-7/publish":
		problemDetail(w, 422, "delivery-runs.publish-checks-failed", "2 checks to fix")
	case strings.HasSuffix(p, "/orders"):
		writeJ(w, 200, []any{map[string]any{"orderId": "ord-1", "number": "LBK-1001", "customerName": "Amara Nwosu", "status": "PAID", "channel": "INSTAGRAM",
			"total": 2598, "placedAt": "2026-10-01T15:00:00Z", "refundStatus": "REFUNDED"},
			map[string]any{"orderId": "ord-2", "number": "LBK-1002", "customerName": "Bola Ade", "status": "LATE_ADD", "channel": "WHATSAPP", "total": 1299,
				"placedAt": "2026-10-02T15:00:00Z"}})
	case strings.HasSuffix(p, "/products"):
		writeJ(w, 200, []any{map[string]any{"atin": "ATGARI0001", "name": "Gari 1kg", "price": 899, "sellerName": "AfroTransact Retail"}})
	case strings.HasSuffix(p, "/summary"):
		writeJ(w, 200, map[string]any{"kpis": map[string]any{"preorders": 142, "lateAdds": 2}, "byChannel": []any{}})
	case strings.Contains(p, "/exports/"):
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="LBK-7-orders.csv"`)
		_, _ = io.WriteString(w, "number,customer,status\nLBK-1001,Amara Nwosu,PAID\n")
	case strings.HasSuffix(p, "/late-orders"):
		writeJ(w, 201, map[string]any{"orderId": "ord-90", "number": "LBK-L90", "status": "LATE_ADD"})
	case strings.HasSuffix(p, "/decisions") && r.Method == http.MethodGet:
		writeJ(w, 200, []any{
			map[string]any{"id": "dec-1", "kind": "AWAITING", "reason": "Closed with 7 of 10 orders", "actor": "system", "orders": 7, "minimum": 10, "at": "2026-10-23T05:00:00Z"},
			map[string]any{"id": "dec-2", "kind": "CANCEL", "reason": "Not enough orders to make the trip", "actor": "stf-21", "orders": 7, "minimum": 10, "at": "2026-10-23T09:30:00Z"}})
	case strings.HasSuffix(p, "/refunds") && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"total": 7, "pending": 2, "refunded": 4, "voided": 0, "failed": 1, "done": false})
	case r.Method == http.MethodPost && (strings.HasSuffix(p, "/decision") || strings.HasSuffix(p, "/cancel") || strings.HasSuffix(p, "/close-early")):
		if strings.Contains(p, "win-gone") {
			problemDetail(w, 409, "delivery-runs.invalid-transition", "Cannot move from DELIVERED to CANCELLED")
			return
		}
		out := map[string]any{}
		for k, v := range window {
			out[k] = v
		}
		switch {
		case strings.HasSuffix(p, "/close-early"):
			out["status"] = "OPEN"
		case strings.HasSuffix(p, "/cancel") || body["decision"] == "CANCEL":
			out["status"], out["outcome"] = "CANCELLED", "CANCELLED"
		default:
			out["status"], out["outcome"] = "CLOSED", "PROCEEDED"
		}
		out["minOrders"], out["orderCount"] = 10, 7
		writeJ(w, 200, out)
	case strings.HasPrefix(p, "/v1/preorders/"):
		writeJ(w, 200, window)
	case p == "/v1/preorder-settings":
		writeJ(w, 200, settings)
	case p == "/v1/preorder-areas" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"key": "US/TX", "on": true, "onCount": 3, "windows": 1}}, "total": 1})
	case strings.HasPrefix(p, "/v1/preorder-areas") && r.Method == http.MethodDelete:
		writeJ(w, 200, map[string]any{"level": "COUNTRY", "rows": []any{}, "total": 0})
	case strings.HasPrefix(p, "/v1/preorder-areas"):
		writeJ(w, 200, map[string]any{"updated": 1})
	case p == "/v1/places/search":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"key": "US/TX/Lubbock", "label": "Lubbock, TX"}}, "regionLabel": "State"})
	default:
		http.NotFound(w, r)
	}
}

func preorderStaffBFF(t *testing.T) (*fakeDeliveryRuns, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeDeliveryRuns{fakePlatform: &fakePlatform{}, ifMatch: map[string]string{}, idemKeys: map[string]string{}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	files, err := exportfiles.NewLocal(exportfiles.Options{Dir: t.TempDir(), SigningKey: "test-key", BasePath: "/api/bff"})
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), DeliveryRuns: up("delivery-runs"), Media: up("media"), Geo: up("geo")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Jobs:   exportfiles.NewMemoryJobs(), Blobs: files, Renderer: exportfiles.NewRenderer(), Log: log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Preorders: app.NewStaffPreordersService(d)}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	return f, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder {
		var rd io.Reader
		if body != nil {
			raw, _ := json.Marshal(body)
			rd = bytes.NewReader(raw)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		for i := 0; i+1 < len(headers); i += 2 {
			req.Header.Set(headers[i], headers[i+1])
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
}

// TestStaffPreordersConformance mirrors delivery-runs' staff API through the BFF and checks the module's access model
// (Marketing FULL, Operations EDIT, Support APPROVE = view + late add, Finance VIEW + export) and every answer against
// gateway-bff-staff.yaml.
func TestStaffPreordersConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := preorderStaffBFF(t)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int, headers ...string) *httptest.ResponseRecorder {
		t.Helper()
		rec := raw(token, method, path, body, headers...)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
		}
		if strings.Contains(rec.Header().Get("Content-Type"), "json") {
			in := &openapi3filter.ResponseValidationInput{
				RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
					Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
				Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
				Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
			}
			if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
				t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
			}
		}
		validated[route.Operation.OperationID]++
		return rec
	}
	can := func(rec *httptest.ResponseRecorder) map[string]any {
		t.Helper()
		c, _ := jsonOf(t, rec)["can"].(map[string]any)
		if c == nil {
			t.Fatalf("no can in %s", rec.Body.String())
		}
		return c
	}

	// The module shows in the sidebar for the roles that have it.
	for token, want := range map[string]bool{"marketing": true, "cs": true, "finance": true} {
		sess := jsonOf(t, raw(token, "GET", "/v1/staff/session", nil))
		found := strings.Contains(mustJSON(t, sess["navGroups"]), `"key":"preorders"`)
		if found != want {
			t.Errorf("%s sees preorders = %v", token, found)
		}
	}

	// List and detail: everyone with VIEW; abilities per role.
	if c := can(do("marketing", "GET", "/v1/staff/preorders?status=OPEN&q=lub", nil, 200)); c["manage"] != true || c["settings"] != true || c["lateAdd"] != true {
		t.Fatalf("marketing can = %v", c)
	}
	if c := can(do("ops", "GET", "/v1/staff/preorders", nil, 200)); c["manage"] != true || c["settings"] != false {
		t.Fatalf("ops can = %v", c)
	}
	if c := can(do("cs", "GET", "/v1/staff/preorders/win-7", nil, 200)); c["manage"] != false || c["lateAdd"] != true || c["export"] != false {
		t.Fatalf("support can = %v", c)
	}
	if c := can(do("finance", "GET", "/v1/staff/preorders/win-7", nil, 200)); c["manage"] != false || c["lateAdd"] != false || c["export"] != true {
		t.Fatalf("finance can = %v", c)
	}
	if c := can(do("auditor", "GET", "/v1/staff/preorders", nil, 200)); c["manage"] != false || c["export"] != false { // VIEW by design
		t.Fatalf("auditor can = %v", c)
	}
	do("customer", "GET", "/v1/staff/preorders", nil, 403)
	do("", "GET", "/v1/staff/preorders", nil, 401)
	do("marketing", "GET", "/v1/staff/preorders/bad%20id", nil, 404)

	// Writes: managers only.
	draft := map[string]any{"name": "Midland Pre-order #1", "targets": []string{"US/TX/Midland"}, "doorFeeOverride": 699}
	created := jsonOf(t, do("ops", "POST", "/v1/staff/preorders", draft, 201, "Idempotency-Key", "idem-0123456789"))
	if created["status"] != "DRAFT" || created["doorFeeOverride"] != float64(699) {
		t.Fatalf("created = %v", created)
	}
	if f.idemKeys["POST /v1/preorders"] != "idem-0123456789" {
		t.Fatalf("idempotency key not relayed: %v", f.idemKeys)
	}
	do("cs", "POST", "/v1/staff/preorders", draft, 403)
	if etag := do("ops", "PUT", "/v1/staff/preorders/win-7", draft, 200, "If-Match", `"3"`).Header().Get("ETag"); etag != `"3"` {
		t.Fatalf("window ETag = %q", etag)
	}
	if f.ifMatch["PUT /v1/preorders/win-7"] != `"3"` {
		t.Fatalf("If-Match not relayed: %v", f.ifMatch)
	}
	if e := jsonOf(t, do("marketing", "POST", "/v1/staff/preorders/win-7/publish", nil, 422)); e["detail"] != "2 checks to fix" {
		t.Fatalf("publish checks = %v", e)
	}
	do("ops", "POST", "/v1/staff/preorders/win-7/pause", nil, 200)
	// Each window's own switch (never the master switch), one at a time or in bulk.
	if etag := do("ops", "PATCH", "/v1/staff/preorders/win-7", map[string]any{"active": false}, 200, "If-Match", `"3"`).Header().Get("ETag"); etag != `"3"` {
		t.Fatalf("switch ETag = %q", etag)
	}
	if b := f.bodies["PATCH /v1/preorders/win-7"]; b["active"] != false || f.ifMatch["PATCH /v1/preorders/win-7"] != `"3"` {
		t.Fatalf("switch body = %v, If-Match %q", b, f.ifMatch["PATCH /v1/preorders/win-7"])
	}
	do("ops", "PATCH", "/v1/staff/preorders/win-7", map[string]any{"active": "off"}, 422)
	do("finance", "PATCH", "/v1/staff/preorders/win-7", map[string]any{"active": false}, 403)
	if bulk := jsonOf(t, do("ops", "PATCH", "/v1/staff/preorders", map[string]any{"ids": []string{"win-7", "win-8"}, "active": false}, 200)); bulk["skipped"] != float64(1) {
		t.Fatalf("bulk = %v", bulk)
	}
	do("ops", "PATCH", "/v1/staff/preorders", map[string]any{"ids": []string{}, "active": false}, 422)
	do("ops", "PATCH", "/v1/staff/preorders", map[string]any{"ids": []string{"bad id"}, "active": false}, 422)
	do("cs", "PATCH", "/v1/staff/preorders", map[string]any{"ids": []string{"win-7"}, "active": false}, 403)
	if containsPath(f, "PUT /v1/preorder-settings") {
		t.Fatal("switching a window must never touch the master switch")
	}
	do("ops", "POST", "/v1/staff/preorders/win-7/resume", nil, 200)
	do("finance", "POST", "/v1/staff/preorders/win-7/pause", nil, 403)
	do("ops", "PUT", "/v1/staff/preorders/win-7/creatives/HERO", map[string]any{"assetId": "ast-9"}, 200)
	do("ops", "PUT", "/v1/staff/preorders/win-7/creatives/HERO", map[string]any{}, 422)
	do("ops", "DELETE", "/v1/staff/preorders/win-7/creatives/PDP", nil, 200)

	// Orders, summary, exports (streamed CSV, export permission).
	if items := jsonOf(t, do("cs", "GET", "/v1/staff/preorders/win-7/orders?status=PAID&q=amara", nil, 200))["items"].([]any); len(items) != 1 {
		t.Fatalf("orders = %v", items)
	}
	// One search over every column (channel, status label, total as text) and any sort; the BFF pages the result.
	for q, want := range map[string]string{"whatsapp": "LBK-1002", "late+add": "LBK-1002", "25.98": "LBK-1001", "oct+1,+2026": "LBK-1001"} {
		page := jsonOf(t, do("cs", "GET", "/v1/staff/preorders/win-7/orders?q="+q, nil, 200))
		if items := page["items"].([]any); len(items) != 1 || items[0].(map[string]any)["number"] != want || page["total"].(float64) != 1 {
			t.Fatalf("q=%s → %v", q, page)
		}
	}
	if page := jsonOf(t, do("cs", "GET", "/v1/staff/preorders/win-7/orders?sort=total,asc", nil, 200)); page["items"].([]any)[0].(map[string]any)["number"] != "LBK-1002" {
		t.Fatalf("sorted = %v", page)
	}
	do("cs", "GET", "/v1/staff/preorders/win-7/orders?sort=secret,asc", nil, 422)
	// The orders table exports through the shared pipeline (pre-order export permission).
	job := jsonOf(t, do("finance", "POST", "/v1/staff/exports", map[string]any{"table": "preorder-orders", "parent": "win-7", "format": "xlsx",
		"q": "lbk", "sort": []string{"total,desc"}, "columns": []string{"number", "customerName", "total", "status"}}, 201))
	if job["rows"].(float64) != 2 || job["resource"] != "preorder-orders" {
		t.Fatalf("preorder orders export = %v", job)
	}
	do("cs", "POST", "/v1/staff/exports", map[string]any{"table": "preorder-orders", "parent": "win-7", "format": "csv"}, 403)
	do("finance", "POST", "/v1/staff/exports", map[string]any{"table": "preorder-orders", "parent": "../x", "format": "csv"}, 422)
	do("finance", "GET", "/v1/staff/preorders/win-7/summary", nil, 200)
	csv := do("finance", "GET", "/v1/staff/preorders/win-7/exports/orders-filtered.csv?status=PAID", nil, 200)
	if !strings.HasPrefix(csv.Header().Get("Content-Type"), "text/csv") || !strings.Contains(csv.Body.String(), "LBK-1001") ||
		!strings.Contains(csv.Header().Get("Content-Disposition"), "LBK-7-orders.csv") {
		t.Fatalf("csv = %v %q", csv.Header(), csv.Body.String())
	}
	do("cs", "GET", "/v1/staff/preorders/win-7/exports/orders.csv", nil, 403)
	do("finance", "GET", "/v1/staff/preorders/win-7/exports/secrets.csv", nil, 404)

	// Late adds: support (APPROVE) and managers; finance (VIEW) may not.
	late := map[string]any{"customerId": "cus-1", "addressId": "adr-1", "phoneConfirmed": true, "items": []any{map[string]any{"atin": "ATGARI0001", "qty": 2}},
		"payment": "PAYMENT_LINK", "reason": "Customer called", "note": "Leave at the door"}
	// The Idempotency-Key is required: the console makes one per dialog and reuses it on every retry.
	const lateKey = "late-add-dialog-0001"
	if e := jsonOf(t, do("cs", "POST", "/v1/staff/preorders/win-7/late-orders", late, 422))["errors"].(map[string]any); e["Idempotency-Key"] == nil {
		t.Fatalf("keyless late order errors = %v", e)
	}
	do("cs", "POST", "/v1/staff/preorders/win-7/late-orders", late, 422, "Idempotency-Key", "short")
	if len(f.bodies["POST /v1/preorders/win-7/late-orders"]) != 0 {
		t.Fatal("a late order without a valid key reached delivery-runs")
	}
	if v := jsonOf(t, do("cs", "POST", "/v1/staff/preorders/win-7/late-orders", late, 201, "Idempotency-Key", lateKey)); v["number"] != "LBK-L90" {
		t.Fatalf("late order = %v", v)
	}
	if got := f.idemKeys["POST /v1/preorders/win-7/late-orders"]; got != lateKey {
		t.Fatalf("relayed Idempotency-Key = %q", got)
	}
	// A saved address id goes to delivery-runs with its snapshot, filled from identity's address book.
	sent := f.bodies["POST /v1/preorders/win-7/late-orders"]
	if a, _ := sent["address"].(map[string]any); a["line1"] != "2401 Broadway" || a["line2"] != "Apt 4" || a["city"] != "Lubbock" ||
		a["region"] != "TX" || a["zip"] != "79401" || a["country"] != "US" || a["fullName"] != "Amara Nwosu" || a["instructions"] != "Gate 4" {
		t.Fatalf("late order snapshot = %v", sent)
	}
	// Fields the console sent win; blanks are filled.
	withSnap := map[string]any{}
	for k, v := range late {
		withSnap[k] = v
	}
	withSnap["address"] = map[string]any{"line1": "2401 Broadway Unit B", "instructions": "Call on arrival"}
	do("cs", "POST", "/v1/staff/preorders/win-7/late-orders", withSnap, 201, "Idempotency-Key", lateKey+"-b")
	if a, _ := f.bodies["POST /v1/preorders/win-7/late-orders"]["address"].(map[string]any); a["line1"] != "2401 Broadway Unit B" ||
		a["instructions"] != "Call on arrival" || a["zip"] != "79401" || a["region"] != "TX" {
		t.Fatalf("merged snapshot = %v", a)
	}
	// A saved address that is not the customer's is refused before delivery-runs.
	unknown := map[string]any{}
	for k, v := range late {
		unknown[k] = v
	}
	unknown["addressId"] = "adr-404"
	if e := jsonOf(t, do("cs", "POST", "/v1/staff/preorders/win-7/late-orders", unknown, 422, "Idempotency-Key", lateKey+"-c"))["errors"].(map[string]any); e["addressId"] == nil {
		t.Fatalf("unknown address errors = %v", e)
	}
	do("finance", "POST", "/v1/staff/preorders/win-7/late-orders", late, 403, "Idempotency-Key", lateKey+"-d")
	if e := jsonOf(t, do("cs", "POST", "/v1/staff/preorders/win-7/late-orders", map[string]any{"customerId": "cus-1"}, 422))["errors"].(map[string]any); e["phoneConfirmed"] == nil || e["items"] == nil {
		t.Fatalf("late order errors = %v", e)
	}

	// Customer picker (identity + addresses), gated like late adds.
	cust := jsonOf(t, do("cs", "GET", "/v1/staff/customers/search?q=amara&windowId=win-7", nil, 200))
	first := cust["items"].([]any)[0].(map[string]any)
	if adr := first["addresses"].([]any); len(adr) != 1 || adr[0].(map[string]any)["zip"] != "79401" || first["city"] != "Lubbock" ||
		first["region"] != "TX" || first["addressesUnavailable"] != false {
		t.Fatalf("customers = %v", cust)
	}
	do("finance", "GET", "/v1/staff/customers/search?q=amara", nil, 403)
	if items := jsonOf(t, do("cs", "GET", "/v1/staff/customers/search?q=a&windowId=win-7", nil, 200))["items"].([]any); len(items) != 0 {
		t.Fatalf("short query = %v", items)
	}
	do("cs", "GET", "/v1/staff/customers/search?q=amara&windowId=bad%20id", nil, 404)

	// Late-add helpers: the window's products and address verification (geo).
	if items := jsonOf(t, do("cs", "GET", "/v1/staff/preorders/win-7/products", nil, 200))["items"].([]any); len(items) != 1 {
		t.Fatalf("products = %v", items)
	}
	v := jsonOf(t, do("cs", "POST", "/v1/staff/preorders/address-verify", map[string]any{"line1": "2401 Broadway", "zip": "79401", "city": "Lubbock",
		"region": "TX", "country": "US"}, 200))
	if v["verified"] != true || !strings.HasPrefix(v["placeId"].(string), "geo:") || v["formatted"] != "2401 BROADWAY, LUBBOCK, TX 79401" {
		t.Fatalf("verify = %v", v)
	}
	if v := jsonOf(t, do("cs", "POST", "/v1/staff/preorders/address-verify", map[string]any{"line1": "1 Nowhere", "zip": "00000"}, 200)); v["verified"] != false || v["placeId"] != nil {
		t.Fatalf("unverified = %v", v)
	}
	do("finance", "POST", "/v1/staff/preorders/address-verify", map[string]any{"line1": "x", "zip": "1"}, 403)

	// Creative upload: media ticket, then the slot (the BFF finalises the asset).
	ticket := jsonOf(t, do("ops", "POST", "/v1/staff/preorders/uploads", map[string]any{"fileName": "hero.jpg", "contentType": "image/jpeg",
		"size": 120000, "width": 1920, "height": 640}, 201))
	if ticket["assetId"] != "ast-9" || ticket["upload"].(map[string]any)["method"] != "PUT" {
		t.Fatalf("ticket = %v", ticket)
	}
	if b := f.bodies["POST /v1/uploads"]; b["purpose"] != "storefront" {
		t.Fatalf("media body = %v", b)
	}
	do("ops", "POST", "/v1/staff/preorders/uploads", map[string]any{"fileName": "hero.gif", "contentType": "image/gif", "size": 1}, 422)
	do("cs", "POST", "/v1/staff/preorders/uploads", map[string]any{"fileName": "hero.jpg", "contentType": "image/jpeg", "size": 1}, 403)

	// Settings (incl. payment): FULL only for changes.
	if s := jsonOf(t, do("ops", "GET", "/v1/staff/preorders/settings", nil, 200)); s["payment"].(map[string]any)["pickupWindow"] != "10am–2pm" {
		t.Fatalf("settings = %v", s)
	}
	update := map[string]any{"enabled": true, "fallback": "WAITLIST", "payment": map[string]any{"pickupWindow": "9am–1pm", "allowedMethods": []string{"CARD", "ACH"}}}
	do("ops", "PUT", "/v1/staff/preorders/settings", update, 403)
	if etag := do("marketing", "PUT", "/v1/staff/preorders/settings", update, 200, "If-Match", `"2"`).Header().Get("ETag"); etag != `"2"` {
		t.Fatalf("settings ETag = %q", etag)
	}
	if b := f.bodies["PUT /v1/preorder-settings"]; b["payment"].(map[string]any)["pickupWindow"] != "9am–1pm" {
		t.Fatalf("settings body = %v", b)
	}

	// Coverage areas and places.
	do("finance", "GET", "/v1/staff/preorders/areas?country=US", nil, 200)
	do("ops", "POST", "/v1/staff/preorders/areas", map[string]any{"key": "US/TX/Midland"}, 201)
	do("ops", "POST", "/v1/staff/preorders/areas", map[string]any{"key": "Texas"}, 422)
	do("ops", "PATCH", "/v1/staff/preorders/areas", map[string]any{"keys": []string{"US/TX", "US/OK"}, "on": false}, 200)
	do("cs", "PATCH", "/v1/staff/preorders/areas", map[string]any{"keys": []string{"US/TX"}, "on": false}, 403)
	do("ops", "DELETE", "/v1/staff/preorders/areas/US%2FTX%2FMidland", nil, 200)
	if !containsPath(f, "DELETE /v1/preorder-areas/US%2FTX%2FMidland") {
		t.Fatalf("area delete path = %v", f.paths)
	}
	// One state's cities edited in place (If-Match relayed, ETag = the region's version).
	if etag := do("finance", "GET", "/v1/staff/preorders/areas/regions/us/tx", nil, 200).Header().Get("ETag"); etag != `"9007199254740991"` {
		t.Fatalf("region ETag = %q", etag)
	}
	region := map[string]any{"everyCity": nil, "cities": []any{map[string]any{"city": " Lubbock ", "on": false}, map[string]any{"city": "Abilene", "on": true}}}
	do("ops", "PUT", "/v1/staff/preorders/areas/regions/US/TX", region, 200, "If-Match", `"9007199254740991"`)
	if f.ifMatch["PUT /v1/preorder-areas/regions/US/TX"] != `"9007199254740991"` {
		t.Fatalf("region If-Match = %v", f.ifMatch)
	}
	if b := f.bodies["PUT /v1/preorder-areas/regions/US/TX"]; b["cities"].([]any)[0].(map[string]any)["city"] != "Lubbock" || b["everyCity"] != nil {
		t.Fatalf("region body = %v", b)
	}
	do("cs", "PUT", "/v1/staff/preorders/areas/regions/US/TX", region, 403)
	do("ops", "PUT", "/v1/staff/preorders/areas/regions/US/TX", map[string]any{"cities": []any{map[string]any{"city": "A/B", "on": true}}}, 422)
	do("ops", "PUT", "/v1/staff/preorders/areas/regions/US/TX", map[string]any{"everyCity": "yes", "cities": []any{}}, 422)
	do("ops", "GET", "/v1/staff/preorders/areas/regions/USA/TX", nil, 404)

	if items := jsonOf(t, do("ops", "GET", "/v1/staff/preorders/places/search?country=US&q=lub", nil, 200))["items"].([]any); len(items) != 1 {
		t.Fatalf("places = %v", items)
	}

	for _, op := range []string{"listStaffPreorders", "createStaffPreorder", "getStaffPreorder", "updateStaffPreorder", "publishStaffPreorder",
		"pauseStaffPreorder", "resumeStaffPreorder", "putStaffPreorderCreative", "deleteStaffPreorderCreative", "listStaffPreorderOrders",
		"getStaffPreorderSummary", "exportStaffPreorder", "addStaffPreorderLateOrder", "getStaffPreorderSettings", "updateStaffPreorderSettings",
		"listStaffPreorderAreas", "addStaffPreorderArea", "toggleStaffPreorderAreas", "deleteStaffPreorderArea", "searchStaffPreorderPlaces",
		"switchStaffPreorder", "switchStaffPreorders", "getStaffPreorderRegion", "updateStaffPreorderRegion",
		"searchStaffCustomers", "listStaffPreorderProducts", "verifyStaffPreorderAddress", "requestStaffPreorderUpload"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
}

func containsPath(f *fakeDeliveryRuns, p string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, x := range f.paths {
		if x == p {
			return true
		}
	}
	return false
}

func mustJSON(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// TestPreorderModuleLevels checks the seeded levels (identity's matrix without a preorders column falls back to them).
func TestPreorderModuleLevels(t *testing.T) {
	matrixWithout := domain.ModuleMatrix{"dash": {"platform.marketing": "VIEW"}}
	for role, want := range map[string]string{"platform.super_admin": "FULL", "platform.marketing": "FULL", "platform.operations_manager": "EDIT",
		"platform.customer_support": "APPROVE", "platform.finance": "VIEW", "platform.auditor": "VIEW", "platform.product_owner": "VIEW",
		"platform.fulfilment_ops": "VIEW", "platform.retail_buyer": "VIEW", "platform.trust_safety": "NONE", "platform.driver": "NONE"} {
		if got := (domain.StaffAccess{Roles: []string{role}, Matrix: matrixWithout}).Level(domain.PreorderModule); got != want {
			t.Errorf("%s = %s, want %s", role, got, want)
		}
	}
	// identity's own column wins once it exists.
	own := domain.ModuleMatrix{"preorders": {"platform.finance": "EDIT"}}
	if got := (domain.StaffAccess{Roles: []string{"platform.finance"}, Matrix: own}).Level(domain.PreorderModule); got != "EDIT" {
		t.Fatalf("identity matrix = %s", got)
	}
}
