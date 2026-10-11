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
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Staff members of the Inventory hub (default matrix: inventory manager FULL / retail VIEW, retail buyer EDIT / FULL,
// finance VIEW / none, auditor VIEW / VIEW).
var hubStaff = map[string][]string{
	"inv":     {"platform.inventory_manager"},
	"buyer":   {"platform.retail_buyer"},
	"finance": {"platform.finance"},
	"auditor": {"platform.auditor"},
}

// fakeHub serves identity, inventory, retail, offers and search for the hub.
type fakeHub struct {
	mu      sync.Mutex
	calls   []string
	bodies  map[string]map[string]any
	headers map[string]http.Header
	// listedSku is the inventory SKU of the retail offer (the trace's broken link when it differs).
	listedSku string
}

func (f *fakeHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	f.mu.Lock()
	call := r.Method + " " + p
	if r.URL.RawQuery != "" {
		call += "?" + r.URL.RawQuery
	}
	f.calls = append(f.calls, call)
	f.bodies[r.Method+" "+p] = body
	f.headers[r.Method+" "+p] = r.Header.Clone()
	f.mu.Unlock()
	shipment := map[string]any{"id": "7d4a3c2e-0000-4000-8000-000000000001", "number": "FBA-IN-006601", "source": "FBA", "nodeId": "HOU-FC",
		"status": "SHIPPED", "expectedUnits": 12, "receivedUnits": 0, "version": 3, "boxes": []any{},
		"lines": []any{map[string]any{"lineNo": 1, "sku": "AT-JOL-200061", "expectedQty": 12, "receivedGood": 0, "receivedDamaged": 0,
			"shortQty": 12, "overQty": 0, "casePack": 6, "cases": 2, "lotNo": "B-77", "mfgDate": "2026-09-01", "expDate": "2027-09-01"}}}
	lot := map[string]any{"id": "5b1d0c9e-0000-4000-8000-000000000002", "sku": "AT-JOL-200061", "lotNo": "B-77", "nodeId": "HOU-FC",
		"qtyOnHand": 12, "qtyReserved": 0, "qtyAvailable": 12, "expDate": "2027-09-01", "status": "SELLABLE", "markdownFlag": "NONE", "version": 0}
	count := map[string]any{"id": "c0c0c0c0-0000-4000-8000-000000000003", "number": "CC-000001", "nodeId": "HOU-FC", "status": "OPEN", "blind": false,
		"netVariance": 0, "varianceLines": 0, "version": 0, "lines": []any{map[string]any{"lineNo": 1, "lotId": lot["id"], "sku": "AT-JOL-200061",
			"lotNo": "B-77", "expectedQty": 12, "countedQty": nil, "variance": 0}}}
	page := func(items ...any) map[string]any {
		return map[string]any{"items": items, "total": len(items), "page": 0, "size": 25}
	}
	switch {
	case p == "/v1/me":
		roles, ok := hubStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": roles, "permissions": []string{"inventory:read", "inventory:adjust", "inventory:receive"}})
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case p == "/v1/inbound/shipments" && r.Method == http.MethodGet:
		writeJ(w, 200, page(shipment))
	case p == "/v1/inbound/shipments" && r.Method == http.MethodPost:
		writeJ(w, 201, shipment)
	case strings.HasSuffix(p, "/labels"):
		w.Header().Set("Content-Type", "application/pdf")
		_, _ = w.Write([]byte("%PDF-1.7 labels"))
	case strings.HasSuffix(p, "/receipts") && strings.HasPrefix(p, "/v1/inbound/"):
		writeJ(w, 200, map[string]any{"shipment": shipment, "receiptRef": "FBA-IN-006601-ab12", "lots": []any{lot}, "replayed": false})
	case strings.HasPrefix(p, "/v1/inbound/shipments/"):
		writeJ(w, 200, shipment)
	case p == "/v1/lots":
		writeJ(w, 200, page(lot))
	case strings.HasPrefix(p, "/v1/lots/"):
		writeJ(w, 200, lot)
	case p == "/v1/cycle-counts" && r.Method == http.MethodGet:
		writeJ(w, 200, page(count))
	case strings.HasPrefix(p, "/v1/cycle-counts"):
		writeJ(w, 200, count)
	case p == "/v1/availability":
		writeJ(w, 200, []any{map[string]any{"sku": "AT-JOL-200061", "available": 12}})
	case p == "/v1/nodes":
		writeJ(w, 200, []any{map[string]any{"id": "HOU-FC", "name": "Houston FC"}})
	case p == "/v1/items":
		writeJ(w, 200, page(map[string]any{"id": "1f000000-0000-4000-8000-000000000004", "sku": "AT-JOL-200061", "gtin": "086001200061",
			"atin": "AT00000042", "name": "Jollof Seasoning", "listingStatus": "LIVE", "version": 1}))
	case strings.HasPrefix(p, "/v1/purchase-orders") && strings.HasSuffix(p, "/pdf"):
		w.Header().Set("Content-Type", "application/pdf")
		_, _ = w.Write([]byte("%PDF-1.7 po"))
	case strings.HasPrefix(p, "/v1/purchase-orders/"):
		writeJ(w, 200, map[string]any{"id": "2f000000-0000-4000-8000-000000000005", "number": "PO-1P-000322", "status": "APPROVED", "version": 2})
	case p == "/v1/purchase-orders", p == "/v1/receipts", p == "/v1/invoices":
		writeJ(w, 200, map[string]any{"items": []any{}, "total": 2, "page": 0, "size": 1})
	case p == "/v1/offers":
		writeJ(w, 200, page(map[string]any{"id": "of-1", "atin": "AT00000042", "productAtin": "AT00000041", "sellerSku": "AT-JOL",
			"inventorySku": f.listedSku, "fulfilment": "AFROTRANSACT", "status": "ACTIVE", "priceMinor": 1199, "currency": "USD"}))
	case strings.HasPrefix(p, "/v1/public/atins/"):
		writeJ(w, 200, map[string]any{"atin": "AT00000042", "buyBox": map[string]any{"soldBy": "AfroTransact Retail", "fulfilment": "AFROTRANSACT",
			"availableQty": 12, "deliveryDays": 2, "badges": []string{"FULFILLED_BY_AFROTRANSACT"}}})
	case p == "/v1/search":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"atin": "AT00000041", "availability": "IN_STOCK", "availableQty": 12}}})
	default:
		problemJ(w, 404, "test.not-found")
	}
}

func (f *fakeHub) seen(prefix string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if strings.HasPrefix(c, prefix) {
			return true
		}
	}
	return false
}

func hubBFF(t *testing.T) (*fakeHub, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeHub{bodies: map[string]map[string]any{}, headers: map[string]http.Header{}, listedSku: "AT-JOL-200061"}
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
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Inventory: up("inventory"), Retail: up("retail"), Offers: up("offers"), Search: up("search")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Jobs:   exportfiles.NewMemoryJobs(), Blobs: files, Renderer: exportfiles.NewRenderer(), Log: log, Background: func(fn func()) { fn() }, RetailOrgID: "org-retail"}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Inventory: app.NewStaffInventoryService(d)}
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
		req.Header.Set("Authorization", "Bearer "+token)
		for i := 0; i+1 < len(headers); i += 2 {
			req.Header.Set(headers[i], headers[i+1])
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
}

// TestStaffInventoryHub drives the Inventory hub and Retail (1P) routes (S281): relayed 1:1 with the module level
// each needs, query allow-lists, If-Match and Idempotency-Key relayed, documents streamed, the flow and the trace, and
// every answer against gateway-bff-staff.yaml.
func TestStaffInventoryHub(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := hubBFF(t)
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
			if want == 404 {
				return rec
			}
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
	const ship = "/v1/staff/inventory/inbound/7d4a3c2e-0000-4000-8000-000000000001"

	// Inbound: list (query allow-list), create (201), ship with If-Match, receive with Idempotency-Key, labels.
	do("inv", "GET", "/v1/staff/inventory/inbound?status=SHIPPED&evil=1&size=10", nil, 200)
	if !f.seen("GET /v1/inbound/shipments?size=10&status=SHIPPED") {
		t.Fatalf("query not filtered to the allow-list: %v", f.calls)
	}
	do("inv", "POST", "/v1/staff/inventory/inbound", map[string]any{"nodeId": "HOU-FC", "lines": []any{map[string]any{"atin": "AT00000042", "qty": 12}}}, 201)
	do("inv", "POST", ship+"/ship", nil, 200, "If-Match", `"3"`)
	if got := f.headers["POST /v1/inbound/shipments/7d4a3c2e-0000-4000-8000-000000000001/ship"].Get("If-Match"); got != `"3"` {
		t.Fatalf("If-Match not relayed: %q", got)
	}
	rec := do("inv", "POST", ship+"/receipts", map[string]any{"lines": []any{map[string]any{"sku": "AT-JOL-200061", "qtyGood": 11, "qtyDamaged": 1}}}, 200,
		"Idempotency-Key", "rcv-0001-abcd")
	if got := f.headers["POST /v1/inbound/shipments/7d4a3c2e-0000-4000-8000-000000000001/receipts"].Get("Idempotency-Key"); got != "rcv-0001-abcd" {
		t.Fatalf("Idempotency-Key not relayed: %q", got)
	}
	if !strings.Contains(rec.Body.String(), "B-77") {
		t.Fatalf("receipt lots missing: %s", rec.Body.String())
	}
	labels := do("inv", "GET", ship+"/labels?kind=BOX&format=PDF", nil, 200)
	if labels.Header().Get("Content-Type") != "application/pdf" || !strings.HasPrefix(labels.Body.String(), "%PDF") {
		t.Fatalf("labels not streamed: %q %q", labels.Header().Get("Content-Type"), labels.Body.String())
	}

	// Lots, cycle counts, nodes ({items} wrapper for upstream arrays).
	do("inv", "GET", "/v1/staff/inventory/lots?sku=AT-JOL-200061", nil, 200)
	do("inv", "GET", "/v1/staff/inventory/lots/5b1d0c9e-0000-4000-8000-000000000002", nil, 200)
	do("inv", "POST", "/v1/staff/inventory/lots/5b1d0c9e-0000-4000-8000-000000000002/quarantine", map[string]any{"reason": "Seal broken"}, 200)
	do("inv", "POST", "/v1/staff/inventory/cycle-counts", map[string]any{"nodeId": "HOU-FC", "sku": "AT-JOL-200061"}, 201)
	do("inv", "PUT", "/v1/staff/inventory/cycle-counts/c0c0c0c0-0000-4000-8000-000000000003/counts", map[string]any{"lines": []any{map[string]any{"lineNo": 1, "countedQty": 11}}}, 200,
		"If-Match", `"0"`)
	do("inv", "POST", "/v1/staff/inventory/cycle-counts/c0c0c0c0-0000-4000-8000-000000000003/post", nil, 200)
	do("inv", "GET", "/v1/staff/inventory/cycle-counts?status=OPEN", nil, 200)
	nodes := do("inv", "GET", "/v1/staff/inventory/nodes", nil, 200)
	if !strings.Contains(nodes.Body.String(), `"items"`) {
		t.Fatalf("arrays are wrapped as {items}: %s", nodes.Body.String())
	}

	// Levels: finance views inventory but cannot change it nor open retail; write-off needs Approve (inventory
	// manager is FULL); the retail buyer approves POs and opens the PO PDF; the auditor only reads.
	do("finance", "GET", "/v1/staff/inventory/lots", nil, 200)
	do("finance", "POST", "/v1/staff/inventory/cycle-counts", map[string]any{"nodeId": "HOU-FC"}, 403)
	do("finance", "GET", "/v1/staff/retail/items", nil, 403)
	do("inv", "POST", "/v1/staff/inventory/lots/5b1d0c9e-0000-4000-8000-000000000002/write-off", map[string]any{"reason": "Crushed"}, 200, "Idempotency-Key", "wo-1-abcdefgh")
	do("buyer", "POST", "/v1/staff/retail/purchase-orders/2f000000-0000-4000-8000-000000000005/approve", map[string]any{"note": "ok"}, 200, "If-Match", `"2"`)
	pdf := do("buyer", "GET", "/v1/staff/retail/purchase-orders/2f000000-0000-4000-8000-000000000005/pdf", nil, 200)
	if !strings.HasPrefix(pdf.Body.String(), "%PDF") {
		t.Fatalf("PO PDF not streamed: %q", pdf.Body.String())
	}
	do("auditor", "GET", "/v1/staff/retail/items?q=AT-JOL", nil, 200)
	do("auditor", "POST", "/v1/staff/retail/purchase-orders/2f000000-0000-4000-8000-000000000005/approve", map[string]any{}, 403)
	do("inv", "POST", "/v1/staff/retail/purchase-orders/2f000000-0000-4000-8000-000000000005/approve", map[string]any{}, 403)

	// Anything not in the route table is 404, never relayed.
	do("inv", "GET", "/v1/staff/inventory/reservations", nil, 404)
	do("inv", "POST", "/v1/staff/inventory/lots/x/y/release", map[string]any{}, 404)
	do("inv", "GET", "/v1/staff/retail/gl/journal/x/y", nil, 404)

	// Flow: counts per step.
	flow := jsonOf(t, do("inv", "GET", "/v1/staff/inventory/flow", nil, 200))
	stages := flow["stages"].([]any)
	if len(stages) != 9 {
		t.Fatalf("want 9 stages, got %d", len(stages))
	}
	if c := stages[1].(map[string]any)["count"]; c != float64(2) {
		t.Fatalf("draft POs = %v", c)
	}
	if c := stages[2].(map[string]any)["count"]; c != float64(8) {
		t.Fatalf("approve & send sums four statuses: %v", c)
	}
	finance := jsonOf(t, do("finance", "GET", "/v1/staff/inventory/flow", nil, 200))
	if s := finance["stages"].([]any)[1].(map[string]any); s["count"] != nil || s["unavailable"] == "" {
		t.Fatalf("finance cannot read retail counts: %v", s)
	}

	// Trace: the whole chain is done, then the offer selling another SKU is the broken link.
	trace := jsonOf(t, do("inv", "GET", "/v1/staff/inventory/trace?atin=AT00000042", nil, 200))
	statuses := map[string]string{}
	for _, s := range trace["steps"].([]any) {
		m := s.(map[string]any)
		statuses[m["key"].(string)] = m["status"].(string)
	}
	if statuses["inbound"] != "WAITING" {
		t.Fatalf("a shipped, unreceived ASN is waiting: %v", statuses)
	}
	for _, k := range []string{"item", "lots", "positions", "offer", "pdp", "search"} {
		if statuses[k] != "DONE" {
			t.Fatalf("step %s = %s: %v", k, statuses[k], trace)
		}
	}
	if trace["sku"] != "AT-JOL-200061" || trace["availableUnits"] != float64(12) {
		t.Fatalf("trace: %v", trace)
	}
	f.listedSku = "AT00000042"
	broken := jsonOf(t, do("inv", "GET", "/v1/staff/inventory/trace?atin=AT00000042", nil, 200))
	for _, s := range broken["steps"].([]any) {
		if m := s.(map[string]any); m["key"] == "offer" && (m["status"] != "BROKEN" || !strings.Contains(m["detail"].(string), "AT00000042")) {
			t.Fatalf("offer selling another SKU is the broken link: %v", m)
		}
	}
	do("inv", "GET", "/v1/staff/inventory/trace", nil, 422)
	do("inv", "GET", "/v1/staff/inventory/trace?atin=nope", nil, 422)

	for _, op := range []string{"getStaffInventoryFlow", "getStaffInventoryTrace", "getStaffInbound", "postStaffInbound", "postStaffInboundShip",
		"postStaffInboundReceipts", "getStaffInboundLabels", "getStaffLots", "postStaffCycleCounts", "putStaffCycleCountsCounts", "postStaffPurchaseOrdersApprove"} {
		if validated[op] == 0 {
			t.Errorf("%s was not exercised", op)
		}
	}
}
