package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
)

const (
	runLocationID = "4b2f9d3a-0000-4000-8000-00000000000a"
	runID         = "9d4a1c7e-0000-4000-8000-000000000009"
	advertorialID = "5e3a0c2b-1d4f-4a6e-8b7c-9d0e1f2a3b4c"
)

func econNumbers(perHour, perMile int) map[string]any {
	return map[string]any{"driverCostPerHourMinor": perHour, "driverCostPerMileMinor": perMile, "collectionMinutes": 45, "perStopMinutes": 6,
		"pickupPointStopMinutes": 30, "handoverMinutesPerOrder": 1, "roundTrip": true}
}

func runEconomicsFake(minOrders int) map[string]any {
	snap := func(n int, fees, cost int) map[string]any {
		return map[string]any{"orders": n, "feeRevenueMinor": fees, "commissionShareMinor": nil, "revenueMinor": fees, "estimatedCostMinor": cost,
			"contributionMarginMinor": fees - cost}
	}
	return map[string]any{"route": map[string]any{"roadMiles": 32, "driveMinutes": 40, "method": "FAKE"},
		"cost":    map[string]any{"driveMiles": 64, "fixedMinutes": 125, "perOrderMinutes": 6, "fixedCostMinor": 9688, "perOrderCostMinor": 250},
		"feeMinor": 799, "currency": "USD", "breakEvenOrders": 18, "atMinimum": snap(minOrders, minOrders*799, 9688+minOrders*250),
		"atCapacity": nil, "atOrders": snap(3, 3*799, 9688+3*250)}
}

func advertorialFake(id, status string) map[string]any {
	return map[string]any{"id": id, "source": "WINDOW", "sourceId": "win-7", "sourceName": "Lubbock Pre-order #7", "sourceCode": "LBK-7",
		"sourceStatus": "OPEN", "sourceCloses": "2026-10-23T04:59:00Z", "locationId": runLocationID, "locationName": "Bastrop", "variant": "A",
		"weight": 1, "priority": 0, "generated": true, "headline": "Lubbock pre-order is open", "body": "Order by Thu", "ctaLabel": "Pre-order now",
		"startsAt": nil, "endsAt": nil, "status": status, "ended": nil, "serving": status == "LIVE",
		"results": map[string]any{"views": 40, "clicks": 4, "ctrPct": 10.0, "orders": 2}, "createdBy": "system",
		"createdAt": "2026-10-20T09:00:00Z", "updatedAt": "2026-10-20T09:00:00Z", "version": 3}
}

// runEconomics fakes delivery-runs' economics, runs, locations and advertorials routes.
func (f *fakeDeliveryRuns) runEconomics(w http.ResponseWriter, r *http.Request) {
	body := f.record(r)
	f.mu.Lock()
	key := r.Method + " " + r.URL.EscapedPath()
	f.paths = append(f.paths, key)
	f.ifMatch[key] = r.Header.Get("If-Match")
	f.queries[key] = r.URL.RawQuery
	f.mu.Unlock()
	p := r.URL.Path
	switch {
	case p == "/v1/economics-settings" && r.Method == http.MethodGet:
		out := econNumbers(2500, 70)
		out["version"], out["source"], out["updatedAt"], out["updatedBy"], out["reason"] = 0, "CONFIG", nil, nil, nil
		w.Header().Set("ETag", `"0"`)
		writeJ(w, 200, out)
	case p == "/v1/economics-settings" && r.Method == http.MethodPut:
		if r.Header.Get("If-Match") == `"0"` && body["driverCostPerHourMinor"] == float64(1) {
			problemDetail(w, 412, "delivery-runs.version-mismatch", "The economics settings changed since version 0 (now 2); reload and retry")
			return
		}
		out := map[string]any{}
		for k, v := range body {
			out[k] = v
		}
		out["version"], out["source"], out["updatedAt"], out["updatedBy"] = 3, "STAFF", "2026-10-25T12:00:00Z", "stf-21"
		w.Header().Set("ETag", `"3"`)
		writeJ(w, 200, out)
	case p == "/v1/economics-settings/changes":
		writeJ(w, 200, map[string]any{"items": []any{
			map[string]any{"id": "c-3", "version": 3, "before": econNumbers(2500, 70), "after": econNumbers(3000, 80), "actor": "stf-21",
				"reason": "Austin drivers moved to overtime", "at": "2026-10-25T12:00:00Z"}}})
	case p == "/v1/preorders/economics-preview":
		min := 0
		if n, ok := body["minOrders"].(float64); ok {
			min = int(n)
		}
		if t, _ := body["targets"].([]any); len(t) > 0 && t[0] == "US/TX/Nowhere" {
			writeJ(w, 200, map[string]any{"locationId": nil, "locationName": nil, "currency": "USD", "feeMinor": 0, "dropMode": "DOORSTEP",
				"minOrders": min, "economics": nil, "minimumCoversBreakEven": nil})
			return
		}
		writeJ(w, 200, map[string]any{"locationId": runLocationID, "locationName": "Bastrop", "currency": "USD", "feeMinor": 799, "dropMode": "DOORSTEP",
			"minOrders": min, "economics": runEconomicsFake(min), "minimumCoversBreakEven": min >= 18})
	case strings.HasPrefix(p, "/v1/preorders/") && strings.HasSuffix(p, "/economics"):
		if strings.Contains(p, "win-cancelled") {
			writeJ(w, 200, map[string]any{"windowId": "win-cancelled", "code": "LBK-7", "name": "Lubbock Pre-order #7", "status": "CANCELLED", "cancelled": true,
				"locationId": runLocationID, "locationName": "Bastrop", "currency": "USD", "minOrders": 10, "orders": 0, "revenueMinor": 0, "deliveryFeesMinor": 0,
				"ordersRefunded": 7, "refundsSettled": 3, "refundedMinor": 19593, "economics": nil, "minimumCoversBreakEven": nil})
			return
		}
		writeJ(w, 200, map[string]any{"windowId": "win-7", "code": "LBK-7", "name": "Lubbock Pre-order #7", "status": "OPEN", "cancelled": false,
			"locationId": runLocationID, "locationName": "Bastrop", "currency": "USD", "minOrders": 10, "orders": 3, "revenueMinor": 8397, "deliveryFeesMinor": 2397,
			"ordersRefunded": 0, "refundsSettled": 0, "refundedMinor": 0, "economics": runEconomicsFake(10), "minimumCoversBreakEven": false})
	case p == "/v1/locations":
		writeJ(w, 200, []any{map[string]any{"id": runLocationID, "slug": "bastrop-tx", "name": "Bastrop", "market": "US", "state": "TX", "timeZone": "America/Chicago",
			"currency": "USD", "defaultFeeMinor": 799, "defaultMinOrders": 10, "dropMode": "DOORSTEP", "whatsappInviteUrl": "https://chat.whatsapp.com/BastropRuns",
			"active": true, "version": 1}})
	case strings.HasPrefix(p, "/v1/locations/") && strings.HasSuffix(p, "/history"):
		writeJ(w, 200, map[string]any{"location": map[string]any{"id": runLocationID, "slug": "bastrop-tx", "name": "Bastrop"},
			"runs": []any{
				map[string]any{"id": runID, "status": "COMPLETED", "windowStart": "2026-10-24T15:00:00Z", "cutoffAt": "2026-10-23T01:00:00Z", "orders": 22,
					"packages": 25, "feeMinor": 799, "feeRevenueMinor": 17578, "ordersRefunded": 0, "minOrders": 10, "minimumMet": true},
				map[string]any{"id": "7e1f0a2b-0000-4000-8000-000000000001", "status": "CANCELLED", "windowStart": "2026-10-10T15:00:00Z", "cutoffAt": "2026-10-09T01:00:00Z",
					"orders": 6, "packages": 6, "feeMinor": 799, "feeRevenueMinor": 0, "ordersRefunded": 6, "minOrders": 10, "minimumMet": false}},
			"subscribers":      map[string]any{"locationId": runLocationID, "total": 120, "active": 96, "pending": 4, "byChannel": map[string]any{"EMAIL": 90, "SMS": 40, "PUSH": 22}},
			"subscriberGrowth": []any{map[string]any{"month": "2026-09", "count": 31}, map[string]any{"month": "2026-10", "count": 52}}})
	case p == "/v1/runs" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{map[string]any{"id": runID, "locationId": runLocationID, "status": "OPEN", "windowStart": "2026-10-24T15:00:00Z",
			"windowEnd": "2026-10-24T19:00:00Z", "cutoffAt": "2026-10-23T01:00:00Z", "feeMinor": 799, "currency": "USD", "minOrders": 10, "capacityOrders": 40,
			"dropMode": "DOORSTEP", "reservedOrders": 7, "minimumMet": false, "cancelReason": nil}})
	case strings.HasSuffix(p, "/pnl"):
		if strings.Contains(p, "7e1f0a2b") {
			e := runEconomicsFake(10)
			e["atOrders"] = map[string]any{"orders": 0, "feeRevenueMinor": 0, "commissionShareMinor": nil, "revenueMinor": 0, "estimatedCostMinor": 0, "contributionMarginMinor": 0}
			writeJ(w, 200, map[string]any{"economics": e, "status": "CANCELLED", "phase": "CANCELLED", "cancelReason": "Storm warning", "ordersRefunded": 6,
				"refundedFeesMinor": 4794})
			return
		}
		writeJ(w, 200, map[string]any{"economics": runEconomicsFake(10), "status": "OPEN", "phase": "BEFORE", "cancelReason": nil, "ordersRefunded": 0, "refundedFeesMinor": 0})
	case p == "/v1/runs/economics-preview":
		writeJ(w, 200, runEconomicsFake(10))
	case p == "/v1/advertorials" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"items": []any{advertorialFake(advertorialID, "LIVE")}})
	case p == "/v1/advertorials" && r.Method == http.MethodPost:
		out := advertorialFake("6f4b1d3c-2e5a-4b7f-9c8d-0e1f2a3b4c5d", "DRAFT")
		out["variant"], out["generated"], out["createdBy"], out["headline"], out["version"] = "B", false, "stf-21", body["headline"], 0
		w.Header().Set("ETag", `"0"`)
		writeJ(w, 201, out)
	case p == "/v1/advertorials/generate":
		if body["sourceId"] == "win-gone" {
			problemDetail(w, 409, "delivery-runs.invalid-transition", "Cannot move from DELIVERED to ADVERTORIAL")
			return
		}
		writeJ(w, 200, advertorialFake(advertorialID, "LIVE"))
	case strings.HasPrefix(p, "/v1/advertorials/") && r.Method == http.MethodGet:
		writeJ(w, 200, advertorialFake(advertorialID, "LIVE"))
	case strings.HasPrefix(p, "/v1/advertorials/") && r.Method == http.MethodPut:
		if r.Header.Get("If-Match") == `"2"` {
			problemDetail(w, 412, "delivery-runs.version-mismatch", "The advertorial changed since version 2 (now 3); reload and retry")
			return
		}
		out := advertorialFake(advertorialID, "LIVE")
		out["generated"], out["headline"], out["version"] = false, body["headline"], 4
		writeJ(w, 200, out)
	case strings.HasPrefix(p, "/v1/advertorials/") && r.Method == http.MethodPost:
		status := map[string]string{"publish": "LIVE", "pause": "PAUSED", "end": "ENDED"}[p[strings.LastIndex(p, "/")+1:]]
		out := advertorialFake(advertorialID, status)
		if status == "ENDED" {
			out["ended"] = "STAFF"
		}
		writeJ(w, 200, out)
	default:
		http.NotFound(w, r)
	}
}

// TestStaffRunEconomicsConformance drives every run economics and advertorial operation through the BFF against the
// fake delivery-runs and validates each answer against gateway-bff-staff.yaml: the cost model (reason required, field
// problems before anything is sent, If-Match and ETag relayed, 412), the break-even beside a window's minimum, a
// window's and a run's P&L (a cancelled one reports orders refunded), a location's history, and the advertorials
// (generate, write, edit with a version, publish, pause, end). Marketing and Operations change; Finance and the auditor read.
func TestStaffRunEconomicsConformance(t *testing.T) {
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
	sent := func(key string) map[string]any { f.mu.Lock(); defer f.mu.Unlock(); return f.bodies[key] }
	tried := func(key string) int { return calls(f, key) }
	query := func(key string) string { f.mu.Lock(); defer f.mu.Unlock(); return f.queries[key] }

	// ---- the driver cost model ----
	cur := jsonOf(t, do("finance", "GET", "/v1/staff/economics-settings", nil, 200))
	if cur["version"] != float64(0) || cur["source"] != "CONFIG" || cur["driverCostPerHourMinor"] != float64(2500) || cur["can"].(map[string]any)["manage"] != false {
		t.Fatalf("settings = %v", cur)
	}
	if got := do("ops", "GET", "/v1/staff/economics-settings", nil, 200); got.Header().Get("ETag") != `"0"` {
		t.Fatalf("ETag = %q", got.Header().Get("ETag"))
	}
	do("auditor", "GET", "/v1/staff/economics-settings", nil, 200)
	do("", "GET", "/v1/staff/economics-settings", nil, 401)

	numbers := econNumbers(3000, 80)
	numbers["reason"] = "  Austin drivers moved to overtime  "
	saved := do("marketing", "PUT", "/v1/staff/economics-settings", numbers, 200, "If-Match", `"0"`)
	if saved.Header().Get("ETag") != `"3"` {
		t.Fatalf("ETag = %q", saved.Header().Get("ETag"))
	}
	out := jsonOf(t, saved)
	if out["version"] != float64(3) || out["source"] != "STAFF" || out["reason"] != "Austin drivers moved to overtime" || out["can"] == nil {
		t.Fatalf("saved = %v", out)
	}
	if got := sent("PUT /v1/economics-settings"); got["reason"] != "Austin drivers moved to overtime" || got["driverCostPerHourMinor"] != float64(3000) || got["roundTrip"] != true {
		t.Fatalf("relayed %v", got)
	}
	if f.ifMatch["PUT /v1/economics-settings"] != `"0"` {
		t.Fatalf("If-Match relayed = %q", f.ifMatch["PUT /v1/economics-settings"])
	}
	before := tried("PUT /v1/economics-settings")
	for name, mutate := range map[string]func(map[string]any){
		"no reason":      func(b map[string]any) { delete(b, "reason") },
		"blank reason":   func(b map[string]any) { b["reason"] = "   " },
		"long reason":    func(b map[string]any) { b["reason"] = strings.Repeat("x", 501) },
		"dear driver":    func(b map[string]any) { b["driverCostPerHourMinor"] = 100_001 },
		"negative mile":  func(b map[string]any) { b["driverCostPerMileMinor"] = -1 },
		"fractional fee": func(b map[string]any) { b["driverCostPerHourMinor"] = 10.5 },
		"day-long stop":  func(b map[string]any) { b["perStopMinutes"] = 1441 },
		"text minutes":   func(b map[string]any) { b["collectionMinutes"] = "forty" },
		"no round trip":  func(b map[string]any) { delete(b, "roundTrip") },
	} {
		b := econNumbers(3000, 80)
		b["reason"] = "why"
		mutate(b)
		if e := jsonOf(t, do("ops", "PUT", "/v1/staff/economics-settings", b, 422)); e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}
	// A missing reason is reported together with the numbers' problems.
	if e := jsonOf(t, do("ops", "PUT", "/v1/staff/economics-settings", map[string]any{"driverCostPerHourMinor": -5}, 422))["errors"].(map[string]any); e["driverCostPerHourMinor"] == nil || e["reason"] == nil || e["roundTrip"] == nil {
		t.Fatalf("errors = %v", e)
	}
	if tried("PUT /v1/economics-settings") != before {
		t.Error("an invalid change reached delivery-runs")
	}
	stale := econNumbers(1, 80)
	stale["reason"] = "Stale"
	do("ops", "PUT", "/v1/staff/economics-settings", stale, 412, "If-Match", `"0"`)
	for _, token := range []string{"cs", "finance", "auditor"} {
		do(token, "PUT", "/v1/staff/economics-settings", numbers, 403)
	}
	do("", "PUT", "/v1/staff/economics-settings", numbers, 401)
	changes := jsonOf(t, do("finance", "GET", "/v1/staff/economics-settings/changes?limit=20", nil, 200))["items"].([]any)
	if len(changes) != 1 || changes[0].(map[string]any)["actor"] != "stf-21" || query("GET /v1/economics-settings/changes") != "limit=20" {
		t.Fatalf("changes = %v (query %q)", changes, query("GET /v1/economics-settings/changes"))
	}
	for _, q := range []string{"limit=0", "limit=500", "limit=abc"} {
		do("ops", "GET", "/v1/staff/economics-settings/changes?"+q, nil, 422)
	}
	do("", "GET", "/v1/staff/economics-settings/changes", nil, 401)

	// ---- break-even beside a window's minimum ----
	plan := jsonOf(t, do("finance", "POST", "/v1/staff/preorders/economics-preview",
		map[string]any{"targets": []any{"US/TX/Bastrop"}, "door": true, "minOrders": 5, "doorFeeMinor": 799}, 200))
	if plan["locationName"] != "Bastrop" || plan["minimumCoversBreakEven"] != false || plan["economics"].(map[string]any)["breakEvenOrders"] != float64(18) {
		t.Fatalf("plan = %v", plan)
	}
	if got := sent("POST /v1/preorders/economics-preview"); got["minOrders"] != float64(5) || got["targets"].([]any)[0] != "US/TX/Bastrop" || got["doorFeeMinor"] != float64(799) {
		t.Fatalf("plan relayed %v", got)
	}
	if p := jsonOf(t, do("ops", "POST", "/v1/staff/preorders/economics-preview", map[string]any{"targets": []any{"US/TX/Nowhere"}, "minOrders": 5}, 200)); p["economics"] != nil {
		t.Fatalf("no location = %v", p)
	}
	do("ops", "POST", "/v1/staff/preorders/economics-preview", map[string]any{"locationId": runLocationID, "minOrders": 25, "expectedOrders": 30}, 200)
	for name, body := range map[string]map[string]any{
		"nothing to plan":  {},
		"bad place":        {"targets": []any{"Bastrop"}},
		"bad location":     {"locationId": "not-a-uuid"},
		"negative minimum": {"targets": []any{"US/TX/Bastrop"}, "minOrders": -1},
		"huge minimum":     {"targets": []any{"US/TX/Bastrop"}, "minOrders": 1_000_000},
		"fractional":       {"targets": []any{"US/TX/Bastrop"}, "minOrders": 2.5},
		"door text":        {"targets": []any{"US/TX/Bastrop"}, "door": "yes"},
		"fee too high":     {"targets": []any{"US/TX/Bastrop"}, "doorFeeMinor": 20000},
	} {
		if e := jsonOf(t, do("ops", "POST", "/v1/staff/preorders/economics-preview", body, 422)); e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}
	do("", "POST", "/v1/staff/preorders/economics-preview", map[string]any{"targets": []any{"US/TX/Bastrop"}}, 401)

	// ---- a window's economics, a cancelled one reports orders refunded ----
	open := jsonOf(t, do("finance", "GET", "/v1/staff/preorders/win-7/economics", nil, 200))
	if open["orders"] != float64(3) || open["deliveryFeesMinor"] != float64(2397) || open["economics"] == nil || open["can"] == nil {
		t.Fatalf("window economics = %v", open)
	}
	cancelled := jsonOf(t, do("ops", "GET", "/v1/staff/preorders/win-cancelled/economics", nil, 200))
	if cancelled["cancelled"] != true || cancelled["ordersRefunded"] != float64(7) || cancelled["revenueMinor"] != float64(0) || cancelled["economics"] != nil {
		t.Fatalf("cancelled window economics = %v", cancelled)
	}
	do("ops", "GET", "/v1/staff/preorders/bad%20id/economics", nil, 404)
	do("", "GET", "/v1/staff/preorders/win-7/economics", nil, 401)

	// ---- locations, history, runs and P&L ----
	locations := jsonOf(t, do("finance", "GET", "/v1/staff/delivery-runs/locations", nil, 200))
	if l := locations["items"].([]any); len(l) != 1 || l[0].(map[string]any)["slug"] != "bastrop-tx" || locations["can"] == nil {
		t.Fatalf("locations = %v", locations)
	}
	history := jsonOf(t, do("ops", "GET", "/v1/staff/delivery-runs/locations/"+runLocationID+"/history", nil, 200))
	runs := history["runs"].([]any)
	if len(runs) != 2 || runs[1].(map[string]any)["ordersRefunded"] != float64(6) || runs[1].(map[string]any)["feeRevenueMinor"] != float64(0) ||
		len(history["subscriberGrowth"].([]any)) != 2 {
		t.Fatalf("history = %v", history)
	}
	do("ops", "GET", "/v1/staff/delivery-runs/locations/nope/history", nil, 404)
	list := jsonOf(t, do("finance", "GET", "/v1/staff/delivery-runs/runs?locationId="+runLocationID+"&status=open,confirmed&limit=50", nil, 200))
	if len(list["items"].([]any)) != 1 || query("GET /v1/runs") != "limit=50&locationId="+runLocationID+"&status=OPEN%2CCONFIRMED" {
		t.Fatalf("runs = %v (query %q)", list, query("GET /v1/runs"))
	}
	do("ops", "GET", "/v1/staff/delivery-runs/runs", nil, 200)
	for _, q := range []string{"locationId=nope", "status=maybe", "limit=0", "limit=501"} {
		do("ops", "GET", "/v1/staff/delivery-runs/runs?"+q, nil, 422)
	}
	pnl := jsonOf(t, do("finance", "GET", "/v1/staff/delivery-runs/runs/"+runID+"/pnl?commissionShareMinor=1500", nil, 200))
	if pnl["phase"] != "BEFORE" || pnl["economics"].(map[string]any)["breakEvenOrders"] != float64(18) || query("GET /v1/runs/"+runID+"/pnl") != "commissionShareMinor=1500" {
		t.Fatalf("pnl = %v", pnl)
	}
	cp := jsonOf(t, do("ops", "GET", "/v1/staff/delivery-runs/runs/7e1f0a2b-0000-4000-8000-000000000001/pnl", nil, 200))
	if cp["phase"] != "CANCELLED" || cp["ordersRefunded"] != float64(6) || cp["economics"].(map[string]any)["atOrders"].(map[string]any)["revenueMinor"] != float64(0) {
		t.Fatalf("cancelled pnl = %v", cp)
	}
	do("ops", "GET", "/v1/staff/delivery-runs/runs/"+runID+"/pnl?commissionShareMinor=-4", nil, 422)
	do("ops", "GET", "/v1/staff/delivery-runs/runs/nope/pnl", nil, 404)
	runPlan := jsonOf(t, do("finance", "POST", "/v1/staff/delivery-runs/economics-preview",
		map[string]any{"locationId": runLocationID, "feeMinor": 899, "minOrders": 12, "dropMode": "pickup_point", "expectedOrders": 20}, 200))
	if runPlan["breakEvenOrders"] != float64(18) {
		t.Fatalf("run plan = %v", runPlan)
	}
	if got := sent("POST /v1/runs/economics-preview"); got["dropMode"] != "PICKUP_POINT" || got["feeMinor"] != float64(899) || got["locationId"] != runLocationID {
		t.Fatalf("run plan relayed %v", got)
	}
	for name, body := range map[string]map[string]any{
		"no location":  {"feeMinor": 799},
		"bad location": {"locationId": "x"},
		"bad drop":     {"locationId": runLocationID, "dropMode": "DRONE"},
		"zero minimum": {"locationId": runLocationID, "minOrders": 0},
		"negative fee": {"locationId": runLocationID, "feeMinor": -1},
	} {
		if e := jsonOf(t, do("ops", "POST", "/v1/staff/delivery-runs/economics-preview", body, 422)); e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}
	do("", "POST", "/v1/staff/delivery-runs/economics-preview", map[string]any{"locationId": runLocationID}, 401)

	// ---- advertorials ----
	ads := jsonOf(t, do("finance", "GET", "/v1/staff/advertorials?source=window&sourceId=win-7&status=live&limit=20", nil, 200))
	if l := ads["items"].([]any); len(l) != 1 || l[0].(map[string]any)["results"].(map[string]any)["orders"] != float64(2) || ads["can"] == nil ||
		query("GET /v1/advertorials") != "limit=20&source=WINDOW&sourceId=win-7&status=LIVE" {
		t.Fatalf("advertorials = %v (query %q)", ads, query("GET /v1/advertorials"))
	}
	for _, q := range []string{"source=boat", "sourceId=bad%20id", "status=maybe", "limit=0", "limit=201"} {
		do("ops", "GET", "/v1/staff/advertorials?"+q, nil, 422)
	}
	do("", "GET", "/v1/staff/advertorials", nil, 401)
	one := do("auditor", "GET", "/v1/staff/advertorials/"+advertorialID, nil, 200)
	if jsonOf(t, one)["variant"] != "A" {
		t.Fatalf("advertorial = %s", one.Body.String())
	}
	do("ops", "GET", "/v1/staff/advertorials/nope", nil, 404)

	gen := do("marketing", "POST", "/v1/staff/advertorials/generate", map[string]any{"source": "window", "sourceId": "win-7"}, 200)
	if got := sent("POST /v1/advertorials/generate"); got["source"] != "WINDOW" || got["sourceId"] != "win-7" || jsonOf(t, gen)["generated"] != true {
		t.Fatalf("generate relayed %v", got)
	}
	do("ops", "POST", "/v1/staff/advertorials/generate", map[string]any{"source": "WINDOW", "sourceId": "win-gone"}, 409)
	for name, body := range map[string]map[string]any{"no source": {"sourceId": "win-7"}, "bad source": {"source": "BOAT", "sourceId": "x"}, "bad id": {"source": "RUN", "sourceId": "bad id!"}} {
		if e := jsonOf(t, do("ops", "POST", "/v1/staff/advertorials/generate", body, 422)); e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}

	words := map[string]any{"source": "WINDOW", "sourceId": "win-7", "variant": "B", "weight": 2, "priority": 5, "headline": "  Lubbock, your egusi is here  ",
		"body": "Order before Thursday", "ctaLabel": "Order now", "startsAt": "2026-10-20T10:00:00+02:00", "endsAt": "2026-10-22T10:00:00Z"}
	created := do("marketing", "POST", "/v1/staff/advertorials", words, 201)
	if created.Header().Get("ETag") != `"0"` || jsonOf(t, created)["variant"] != "B" {
		t.Fatalf("created = %s (ETag %q)", created.Body.String(), created.Header().Get("ETag"))
	}
	if got := sent("POST /v1/advertorials"); got["headline"] != "Lubbock, your egusi is here" || got["startsAt"] != "2026-10-20T08:00:00Z" || got["weight"] != float64(2) {
		t.Fatalf("create relayed %v", got)
	}
	before = tried("POST /v1/advertorials")
	for name, mutate := range map[string]func(map[string]any){
		"no headline":     func(b map[string]any) { delete(b, "headline") },
		"long headline":   func(b map[string]any) { b["headline"] = strings.Repeat("h", 141) },
		"long body":       func(b map[string]any) { b["body"] = strings.Repeat("b", 401) },
		"no button":       func(b map[string]any) { b["ctaLabel"] = " " },
		"long button":     func(b map[string]any) { b["ctaLabel"] = strings.Repeat("c", 41) },
		"bad variant":     func(b map[string]any) { b["variant"] = "not a label" },
		"zero weight":     func(b map[string]any) { b["weight"] = 0 },
		"huge priority":   func(b map[string]any) { b["priority"] = 1001 },
		"bad start":       func(b map[string]any) { b["startsAt"] = "tomorrow" },
		"end before":      func(b map[string]any) { b["endsAt"] = "2026-10-19T10:00:00Z" },
		"no source":       func(b map[string]any) { delete(b, "source") },
		"bad source id":   func(b map[string]any) { b["sourceId"] = "bad id!" },
		"fractional prio": func(b map[string]any) { b["priority"] = 1.5 },
	} {
		b := map[string]any{}
		for k, v := range words {
			b[k] = v
		}
		mutate(b)
		if e := jsonOf(t, do("ops", "POST", "/v1/staff/advertorials", b, 422)); e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}
	if tried("POST /v1/advertorials") != before {
		t.Error("an invalid advertorial reached delivery-runs")
	}
	for _, token := range []string{"cs", "finance", "auditor"} {
		do(token, "POST", "/v1/staff/advertorials", words, 403)
		do(token, "POST", "/v1/staff/advertorials/generate", map[string]any{"source": "RUN", "sourceId": runID}, 403)
		do(token, "POST", "/v1/staff/advertorials/"+advertorialID+"/pause", nil, 403)
	}
	do("", "POST", "/v1/staff/advertorials", words, 401)

	edit := map[string]any{"headline": "Lubbock, egusi is back", "body": "", "ctaLabel": "Pre-order now"}
	edited := do("marketing", "PUT", "/v1/staff/advertorials/"+advertorialID, edit, 200, "If-Match", `"3"`)
	if f.ifMatch["PUT /v1/advertorials/"+advertorialID] != `"3"` || edited.Header().Get("ETag") != `"4"` || jsonOf(t, edited)["generated"] != false {
		t.Fatalf("edited = %s (If-Match %q, ETag %q)", edited.Body.String(), f.ifMatch["PUT /v1/advertorials/"+advertorialID], edited.Header().Get("ETag"))
	}
	do("ops", "PUT", "/v1/staff/advertorials/"+advertorialID, edit, 412, "If-Match", `"2"`)
	do("ops", "PUT", "/v1/staff/advertorials/"+advertorialID, map[string]any{"headline": "x"}, 422)
	do("ops", "PUT", "/v1/staff/advertorials/nope", edit, 404)

	for action, status := range map[string]string{"publish": "LIVE", "pause": "PAUSED", "end": "ENDED"} {
		r := jsonOf(t, do("ops", "POST", "/v1/staff/advertorials/"+advertorialID+"/"+action, nil, 200, "If-Match", `"4"`))
		if r["status"] != status {
			t.Errorf("%s = %v", action, r["status"])
		}
		if f.ifMatch["POST /v1/advertorials/"+advertorialID+"/"+action] != `"4"` {
			t.Errorf("%s: If-Match relayed = %q", action, f.ifMatch["POST /v1/advertorials/"+advertorialID+"/"+action])
		}
	}
	do("ops", "POST", "/v1/staff/advertorials/"+advertorialID+"/explode", nil, 404)
	do("ops", "POST", "/v1/staff/advertorials/nope/pause", nil, 404)

	for _, op := range []string{"getStaffEconomicsSettings", "updateStaffEconomicsSettings", "listStaffEconomicsChanges", "previewStaffWindowEconomics",
		"getStaffPreorderEconomics", "listStaffRunLocations", "getStaffRunLocationHistory", "listStaffRuns", "getStaffRunPnl", "previewStaffRunEconomics",
		"listStaffAdvertorials", "createStaffAdvertorial", "generateStaffAdvertorial", "getStaffAdvertorial", "updateStaffAdvertorial", "actStaffAdvertorial"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
