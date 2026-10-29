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

const (
	testRunID      = "8c1f0e52-3a47-4d0e-9b6a-5d2e7c1a9f10"
	testRunPending = "9d2a1f63-4b58-4e1f-8c7b-6e3f8d2b0a21"
	testRunDone    = "ae3b2a74-5c69-4f2a-9d8c-7f4a9e3c1b32"
)

// runRoute is fulfilment's route of a run: seller pickups first, then the drops, plus a stop that was reassigned (left out).
func runRoute() map[string]any {
	return map[string]any{"id": "rte-1", "name": "LBK-7 route 1", "status": "ASSIGNED", "driverId": "drv-1", "vehicleId": nil, "blocked": false,
		"packages": 3, "bagsCollected": 1, "bagsDelivered": 0, "pickupStops": 2, "pickupsDone": 1, "dropStops": 1, "dropsDone": 0, "dropsFailed": 0,
		"stops": []any{
			map[string]any{"id": "st-1", "sequence": 1, "kind": "PICKUP", "name": "Ade's Kitchen", "status": "DONE", "handoverRequired": false},
			map[string]any{"id": "st-2", "sequence": 2, "kind": "PICKUP", "name": "Bonwire Weavers", "status": "PENDING", "handoverRequired": false, "note": "Back door"},
			map[string]any{"id": "st-3", "sequence": 3, "kind": "DROPOFF", "name": "Amara Nwosu", "status": "PENDING", "orderNumber": "LBK-1001",
				"addressLine": "2401 Broadway, Lubbock, TX 79401", "handoverRequired": true},
			map[string]any{"id": "st-4", "sequence": 4, "kind": "DROPOFF", "name": "Old stop", "status": "REASSIGNED", "orderNumber": "LBK-1002"}}}
}

func runView(id, status string, withRoutes bool) map[string]any {
	out := map[string]any{"runId": id, "town": "Lubbock", "state": "TX", "timeZone": "America/Chicago", "serviceDate": "2026-11-07",
		"windowStart": "2026-11-07T15:00:00Z", "windowEnd": "2026-11-07T19:00:00Z", "sellerPickupAt": "2026-11-06T16:00:00Z", "dropMode": "DOORSTEP",
		"status": status, "plannedAt": "2026-11-04T10:00:00Z", "pickupPoint": nil, "planError": nil,
		"progress": map[string]any{"orders": 3, "bags": 4, "pickupStops": 2, "pickupsDone": 1, "bagsCollected": 1, "dropStops": 1, "dropsDone": 0,
			"dropsFailed": 0, "bagsDelivered": 0, "unrouted": 0}}
	if id == testRunPending {
		out["status"], out["plannedAt"], out["planError"], out["dropMode"] = "PENDING", nil, "No depot is configured", "PICKUP_POINT"
		out["pickupPoint"] = map[string]any{"name": "Gateway Mall", "line1": "100 Main St", "city": "Lubbock", "state": "TX", "postalCode": "79401", "hours": "10-2"}
	}
	out["routes"] = []any{}
	if withRoutes {
		out["routes"] = []any{runRoute()}
	}
	return out
}

// runsOverride fakes fulfilment's /v1/dispatch/runs routes and delivery-runs' lifecycle routes.
func runsOverride(w http.ResponseWriter, r *http.Request, _ map[string]any) bool {
	p := r.URL.Path
	switch {
	case p == "/v1/me" && strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ops-no-dispatch"):
		writeJ(w, 200, map[string]any{"id": "stf-31", "firstName": "Ngozi", "lastName": "Eze", "email": "ngozi@afrotransact.com", "principalType": "STAFF",
			"roles": []string{"platform.operations_manager"}, "permissions": []string{"orders:read", "reports:read"}})
	case p == "/v1/dispatch/runs" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{runView(testRunID, "PLANNED", false), runView(testRunPending, "PENDING", false), runView(testRunDone, "PLANNED", false)})
	case strings.HasPrefix(p, "/v1/dispatch/runs/") && strings.HasSuffix(p, "/plan") && r.Method == http.MethodPost:
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/v1/dispatch/runs/"), "/plan")
		if id == testRunPending {
			problemDetail(w, 409, "fulfilment.run-cancelled", "This run was cancelled")
			return true
		}
		writeJ(w, 200, runView(id, "PLANNED", true))
	case strings.HasPrefix(p, "/v1/dispatch/runs/") && r.Method == http.MethodGet:
		id := strings.TrimPrefix(p, "/v1/dispatch/runs/")
		if id != testRunID && id != testRunPending && id != testRunDone {
			problemDetail(w, 404, "fulfilment.run-not-found", "No such run")
			return true
		}
		writeJ(w, 200, runView(id, "PLANNED", id == testRunID))
	case p == "/v1/runs" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{map[string]any{"id": testRunID, "status": "COLLECTING"}, map[string]any{"id": testRunDone, "status": "COMPLETED"}})
	case strings.HasPrefix(p, "/v1/runs/") && strings.HasSuffix(p, "/progress"):
		writeJ(w, 200, map[string]any{"orders": 3, "pending": 1, "collected": 1, "outForDelivery": 0, "failed": 0, "delivered": 1, "returned": 0,
			"lastDeliveredAt": "2026-11-07T17:20:00Z"})
	case strings.HasPrefix(p, "/v1/runs/") && r.Method == http.MethodPost:
		id := strings.Split(strings.TrimPrefix(p, "/v1/runs/"), "/")[0]
		if id == testRunDone {
			problemDetail(w, 409, "delivery-runs.invalid-transition", "Cannot move from COMPLETED to OUT_FOR_DELIVERY")
			return true
		}
		writeJ(w, 200, map[string]any{"id": id, "status": "OUT_FOR_DELIVERY"})
	case strings.HasPrefix(p, "/v1/runs/") && r.Method == http.MethodGet:
		id := strings.TrimPrefix(p, "/v1/runs/")
		status := "COLLECTING"
		if id == testRunDone {
			status = "COMPLETED"
		}
		writeJ(w, 200, map[string]any{"id": id, "status": status})
	default:
		return false
	}
	return true
}

// runDispatchBFF is the staff BFF with fulfilment and delivery-runs behind the fake platform's server.
func runDispatchBFF(t *testing.T, f *fakePlatform) func(token, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	f.override = runsOverride
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
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Fulfilment: up("fulfilment"), DeliveryRuns: up("delivery-runs")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Jobs:   exportfiles.NewMemoryJobs(), Blobs: files, Renderer: exportfiles.NewRenderer(), Log: log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Dispatch: app.NewStaffDispatchService(d)}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	return func(token, method, path string, body any) *httptest.ResponseRecorder {
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
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
}

// TestStaffRunDispatchConformance drives the four delivery run operations of the Dispatch module (S264) through the
// BFF against fake fulfilment and delivery-runs and validates every answer against gateway-bff-staff.yaml: routes with
// seller pickups before drops (a reassigned stop left out), the run's lifecycle and orders per stage from
// delivery-runs, who may plan and move a run (EDIT on dispatch and delivery:dispatch), and the problems on the way.
func TestStaffRunDispatchConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakePlatform{}
	raw := runDispatchBFF(t, f)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int) *httptest.ResponseRecorder {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", method, path, rec.Code, rec.Body.String())
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
	const runs = "/v1/staff/dispatch/runs"

	// List: every run with its lifecycle where delivery-runs knows it; only a manager can plan or move runs.
	list := jsonOf(t, do("admin", "GET", runs, nil, 200))
	items, _ := list["items"].([]any)
	if len(items) != 3 || list["canManage"] != true {
		t.Fatalf("runs = %v", list)
	}
	first := items[0].(map[string]any)
	if first["runStatus"] != "COLLECTING" || first["status"] != "PLANNED" || items[1].(map[string]any)["runStatus"] != nil ||
		items[2].(map[string]any)["runStatus"] != "COMPLETED" {
		t.Fatalf("lifecycle merge = %v / %v / %v", first, items[1], items[2])
	}
	if pending := items[1].(map[string]any); pending["planError"] != "No depot is configured" || pending["dropMode"] != "PICKUP_POINT" ||
		pending["pickupPoint"].(map[string]any)["name"] != "Gateway Mall" {
		t.Fatalf("pending run = %v", pending)
	}
	if c := jsonOf(t, do("support", "GET", runs+"?status=PLANNED", nil, 200)); c["canManage"] != false {
		t.Fatalf("support list = %v", c)
	}
	do("auditor", "GET", runs, nil, 200)
	do("admin", "GET", runs+"?status=SOON", nil, 422)
	do("", "GET", runs, nil, 401)
	do("tourist", "GET", runs, nil, 403)

	// One run: routes with pickups before drops, a reassigned stop left out, and the orders per stage.
	one := jsonOf(t, do("admin", "GET", runs+"/"+testRunID, nil, 200))
	routes, _ := one["routes"].([]any)
	if len(routes) != 1 || one["runStatus"] != "COLLECTING" {
		t.Fatalf("run = %v", one)
	}
	stops := routes[0].(map[string]any)["stops"].([]any)
	if len(stops) != 3 || stops[0].(map[string]any)["kind"] != "PICKUP" || stops[1].(map[string]any)["kind"] != "PICKUP" ||
		stops[2].(map[string]any)["kind"] != "DROPOFF" || stops[2].(map[string]any)["handoverRequired"] != true {
		t.Fatalf("stops = %v", stops)
	}
	if o, _ := one["orders"].(map[string]any); o["delivered"] != float64(1) || o["lastDeliveredAt"] != "2026-11-07T17:20:00Z" || o["collected"] != float64(1) {
		t.Fatalf("orders per stage = %v", one["orders"])
	}
	do("support", "GET", runs+"/"+testRunID, nil, 200)
	do("admin", "GET", runs+"/"+testRunPending, nil, 200)
	do("admin", "GET", runs+"/00000000-0000-4000-8000-000000000000", nil, 404)
	do("admin", "GET", runs+"/a%2Fb", nil, 404)

	// Plan: EDIT on dispatch and delivery:dispatch; support (view only) and ops without the permission are turned away.
	planned := jsonOf(t, do("admin", "POST", runs+"/"+testRunID+"/plan", nil, 200))
	if planned["status"] != "PLANNED" || planned["canManage"] != true || len(planned["routes"].([]any)) != 1 {
		t.Fatalf("planned = %v", planned)
	}
	do("support", "POST", runs+"/"+testRunID+"/plan", nil, 403)
	do("ops-no-dispatch", "POST", runs+"/"+testRunID+"/plan", nil, 403)
	do("admin", "POST", runs+"/"+testRunPending+"/plan", nil, 409)
	do("", "POST", runs+"/"+testRunID+"/plan", nil, 401)

	// Advance: the override walks the run forward; a step that does not follow is 409, an unknown step 422.
	moved := jsonOf(t, do("admin", "POST", runs+"/"+testRunID+"/advance", map[string]any{"to": "OUT_FOR_DELIVERY"}, 200))
	if moved["runId"] != testRunID || len(moved["routes"].([]any)) != 1 {
		t.Fatalf("moved = %v", moved)
	}
	f.mu.Lock()
	moves := append([]string(nil), f.calls...)
	f.mu.Unlock()
	if !contains(moves, "POST /v1/runs/"+testRunID+"/out-for-delivery") {
		t.Fatalf("delivery-runs was not asked to move the run: %v", moves)
	}
	do("admin", "POST", runs+"/"+testRunID+"/advance", map[string]any{"to": "COLLECTING"}, 200)
	do("admin", "POST", runs+"/"+testRunID+"/advance", map[string]any{"to": "COMPLETED"}, 200)
	if p := jsonOf(t, do("admin", "POST", runs+"/"+testRunDone+"/advance", map[string]any{"to": "OUT_FOR_DELIVERY"}, 409)); p["code"] != "CONFLICT" {
		t.Fatalf("conflict = %v", p)
	}
	do("admin", "POST", runs+"/"+testRunID+"/advance", map[string]any{"to": "DELIVERED"}, 422)
	do("admin", "POST", runs+"/"+testRunID+"/advance", map[string]any{}, 422)
	do("support", "POST", runs+"/"+testRunID+"/advance", map[string]any{"to": "COLLECTING"}, 403)
	do("ops-no-dispatch", "POST", runs+"/"+testRunID+"/advance", map[string]any{"to": "COLLECTING"}, 403)
	do("admin", "POST", runs+"/"+strings.Repeat("x", 80)+"/advance", map[string]any{"to": "COLLECTING"}, 404)

	for _, op := range []string{"listStaffRuns", "getStaffRun", "planStaffRun", "advanceStaffRun"} {
		if validated[op] == 0 {
			t.Errorf("%s was not exercised", op)
		}
	}
}

func contains(all []string, want string) bool {
	for _, s := range all {
		if s == want {
			return true
		}
	}
	return false
}
