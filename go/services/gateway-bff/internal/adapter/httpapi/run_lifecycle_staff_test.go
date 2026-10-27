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
	draftRunID     = "9d4a1c7e-0000-4000-8000-0000000000d1"
	metRunID       = "9d4a1c7e-0000-4000-8000-0000000000a2"
	onTheRoadRunID = "9d4a1c7e-0000-4000-8000-0000000000c3"
	missingRunID   = "9d4a1c7e-0000-4000-8000-0000000000ff"
	inactiveLocID  = "4b2f9d3a-0000-4000-8000-0000000000ff"
	staffActorID   = "0b5a8f2e-1c3d-4e5f-8a9b-0c1d2e3f4a5b"
)

// fakeRun is a run as delivery-runs answers it.
func fakeRun(id, status string, reserved, minimum int) map[string]any {
	return map[string]any{"id": id, "locationId": runLocationID, "status": status, "windowStart": "2026-10-24T15:00:00Z", "windowEnd": "2026-10-24T19:00:00Z",
		"cutoffAt": "2026-10-23T01:00:00Z", "originalCutoffAt": "2026-10-23T01:00:00Z", "opensAt": nil, "sellerPickupAt": "2026-10-23T13:00:00Z", "feeMinor": 799,
		"currency": "USD", "minOrders": minimum, "capacityOrders": 40, "capacityPackages": nil, "dropMode": "DOORSTEP", "pickupPoint": nil, "cutoffPolicy": "EXTEND",
		"extendDays": 7, "notes": "Bring the cold bags", "extensions": 0, "reservedOrders": reserved, "reservedPackages": reserved, "ordersLeft": 40 - reserved,
		"minimumMet": reserved >= minimum, "cancelReason": nil, "estimate": map[string]any{"roadMiles": 32, "driveMinutes": 40, "method": "FAKE"}, "breakEvenOrders": 18,
		"milestones": map[string]any{"openedAt": "2026-10-20T09:00:00Z"}, "createdAt": "2026-10-19T09:00:00Z", "updatedAt": "2026-10-19T09:00:00Z", "version": 2}
}

// fakeLocation is a location as delivery-runs answers it.
func fakeLocation(id string, name, slug any) map[string]any {
	return map[string]any{"id": id, "slug": slug, "name": name, "market": "US", "state": "TX", "timeZone": "America/Chicago",
		"area": map[string]any{"kind": "RADIUS", "geoZoneId": nil, "centerLat": 30.1105, "centerLng": -97.3153, "radiusMiles": 12.0}, "currency": "USD",
		"defaultFeeMinor": 799, "defaultMinOrders": 10, "defaultCapacityOrders": 40, "defaultCutoffPolicy": "EXTEND", "defaultExtendDays": 7, "dropMode": "DOORSTEP",
		"pickupPoint": nil, "whatsappInviteUrl": nil, "active": true, "version": 1}
}

// runLifecycle fakes delivery-runs' run lifecycle routes; false when the route is not one of them.
func (f *fakeDeliveryRuns) runLifecycle(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
	p := r.URL.Path
	switch {
	case p == "/v1/locations" && r.Method == http.MethodPost:
		if body["slug"] == "taken-tx" {
			problemDetail(w, 409, "delivery-runs.slug-taken", "A location with this web address already exists")
			return true
		}
		loc := fakeLocation(runLocationID, body["name"], body["slug"])
		for k, v := range body {
			loc[k] = v
		}
		loc["active"] = body["active"] != false
		writeJ(w, 201, loc)
		return true
	case strings.HasPrefix(p, "/v1/locations/") && r.Method != http.MethodGet && !strings.HasSuffix(p, "/history") && !strings.HasSuffix(p, "/attribution"):
		id, action, _ := strings.Cut(strings.TrimPrefix(p, "/v1/locations/"), "/")
		if id != runLocationID {
			problemDetail(w, 404, "delivery-runs.location-not-found", "Location not found")
			return true
		}
		loc := fakeLocation(id, "Bastrop", "bastrop-tx")
		switch {
		case action == "" && r.Method == http.MethodPut:
			for k, v := range body {
				loc[k] = v
			}
			loc["active"] = true // an edit never switches a location
		case action == "deactivate":
			if body["reason"] == "live" {
				problemDetail(w, 409, "delivery-runs.location-has-live-runs", "A run of this location is open")
				return true
			}
			loc["active"] = false
		case action == "activate":
			loc["active"] = true
		default:
			return false
		}
		writeJ(w, 200, loc)
		return true
	case strings.HasPrefix(p, "/v1/locations/") && !strings.HasSuffix(p, "/history") && !strings.HasSuffix(p, "/attribution") && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"id": runLocationID, "slug": "bastrop-tx", "name": "Bastrop", "market": "US", "state": "TX", "timeZone": "America/Chicago",
			"area": map[string]any{"kind": "RADIUS", "geoZoneId": nil, "centerLat": 30.1105, "centerLng": -97.3153, "radiusMiles": 12.0}, "currency": "USD",
			"defaultFeeMinor": 799, "defaultMinOrders": 10, "defaultCapacityOrders": 40, "defaultCutoffPolicy": "EXTEND", "defaultExtendDays": 7, "dropMode": "DOORSTEP",
			"pickupPoint": nil, "whatsappInviteUrl": nil, "active": true, "version": 1})
		return true
	case p == "/v1/runs" && r.Method == http.MethodPost:
		if body["locationId"] == inactiveLocID {
			problemDetail(w, 422, "delivery-runs.location-inactive", "The location is not active")
			return true
		}
		run := fakeRun(draftRunID, "DRAFT", 0, 10)
		for _, k := range []string{"locationId", "windowStart", "windowEnd", "cutoffAt", "feeMinor", "minOrders", "capacityOrders", "notes", "cutoffPolicy", "dropMode", "opensAt"} {
			if v, ok := body[k]; ok {
				run[k] = v
			}
		}
		writeJ(w, 201, run)
		return true
	case !strings.HasPrefix(p, "/v1/runs/"):
		return false
	}
	rest := strings.TrimPrefix(p, "/v1/runs/")
	id, action, _ := strings.Cut(rest, "/")
	if action == "pnl" || action == "economics" || id == "economics-preview" {
		return false
	}
	runs := map[string]map[string]any{
		runID: fakeRun(runID, "OPEN", 7, 10), draftRunID: fakeRun(draftRunID, "DRAFT", 0, 10), metRunID: fakeRun(metRunID, "OPEN", 12, 10),
		onTheRoadRunID: fakeRun(onTheRoadRunID, "OUT_FOR_DELIVERY", 12, 10),
	}
	run, found := runs[id]
	if !found {
		problemDetail(w, 404, "delivery-runs.run-not-found", "Run not found")
		return true
	}
	badMove := func(to string) bool {
		if run["status"] == "OUT_FOR_DELIVERY" {
			problemDetail(w, 409, "delivery-runs.invalid-transition", "Cannot move from OUT_FOR_DELIVERY to "+to)
			return true
		}
		return false
	}
	switch {
	case action == "" && r.Method == http.MethodGet:
		writeJ(w, 200, run)
	case action == "" && r.Method == http.MethodPut:
		if run["status"] != "DRAFT" && body["feeMinor"] != nil && body["feeMinor"] != float64(799) {
			problemDetail(w, 422, "delivery-runs.run-not-editable", "Fee, currency, cutoff and drop mode are fixed once a run is open (extend to move the cutoff)")
			return true
		}
		for k, v := range body {
			run[k] = v
		}
		writeJ(w, 200, run)
	case action == "" && r.Method == http.MethodDelete:
		if run["status"] != "DRAFT" {
			problemDetail(w, 422, "delivery-runs.run-not-draft", "Only draft runs can be deleted; cancel instead")
			return true
		}
		w.WriteHeader(204)
	case action == "announce" || action == "open":
		if badMove(strings.ToUpper(action)) {
			return true
		}
		run["status"] = map[string]string{"announce": "ANNOUNCED", "open": "OPEN"}[action]
		writeJ(w, 200, run)
	case action == "extend":
		if badMove("EXTENDED") {
			return true
		}
		run["status"], run["extensions"], run["cutoffAt"] = "EXTENDED", 1, body["cutoffAt"]
		writeJ(w, 200, run)
	case action == "confirm":
		if badMove("CONFIRMED") {
			return true
		}
		if run["minimumMet"] == false && body["reason"] == nil {
			problemDetail(w, 422, "delivery-runs.reason-required", "Confirming a run below its minimum needs a reason")
			return true
		}
		run["status"] = "CONFIRMED"
		writeJ(w, 200, run)
	case action == "cancel":
		if badMove("CANCELLED") {
			return true
		}
		run["status"], run["cancelReason"] = "CANCELLED", body["reason"]
		writeJ(w, 200, run)
	case action == "log":
		writeJ(w, 200, []any{
			map[string]any{"id": "7a1b2c3d-0000-4000-8000-000000000001", "runId": id, "kind": "EXTEND", "reason": "MINIMUM_NOT_MET", "actor": "system", "fromStatus": "OPEN",
				"toStatus": "EXTENDED", "orders": 7, "minimum": 10, "fromCutoff": "2026-10-23T01:00:00Z", "toCutoff": "2026-10-30T01:00:00Z", "at": "2026-10-23T01:00:05Z"},
			map[string]any{"id": "7a1b2c3d-0000-4000-8000-000000000002", "runId": id, "kind": "CONFIRM_OVERRIDE", "reason": "Sellers already packed", "actor": staffActorID,
				"fromStatus": "EXTENDED", "toStatus": "CONFIRMED", "orders": 7, "minimum": 10, "fromCutoff": nil, "toCutoff": nil, "at": "2026-10-25T10:00:00Z"}})
	case action == "orders":
		if r.URL.Query().Get("status") == "CANCELLED" {
			writeJ(w, 200, []any{})
			return true
		}
		writeJ(w, 200, []any{map[string]any{"id": "5c1d2e3f-0000-4000-8000-000000000001", "runId": id, "orderId": "ord-1", "customerId": "cus-1", "packages": 1,
			"status": "RESERVED", "handoverCode": nil, "requestedRunId": nil, "reservedAt": "2026-10-20T10:00:00Z", "updatedAt": "2026-10-20T10:00:00Z", "stage": "PENDING",
			"stageAt": nil, "deliveredAt": nil}})
	default:
		return false
	}
	return true
}

// TestStaffRunLifecycleConformance drives every run lifecycle operation of S260 through the BFF against the fake
// delivery-runs and validates each answer against gateway-bff-staff.yaml: scheduling with the location's area and fee
// rule, changes before a run opens, announce and open, an extension (reason required), a confirmation (a reason when the
// minimum is not met), a cancellation (reason required, every order refunded), the log with names instead of ids and the
// orders on a run. Marketing and Operations change; Support and Finance only read.
func TestStaffRunLifecycleConformance(t *testing.T) {
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
	runs := "/v1/staff/delivery-runs/runs/"
	problems := func(rec *httptest.ResponseRecorder) map[string]any {
		t.Helper()
		e, _ := jsonOf(t, rec)["errors"].(map[string]any)
		if e == nil {
			t.Fatalf("no field errors: %s", rec.Body.String())
		}
		return e
	}

	// ---- reading: the list names the town, a run has its location (area), what its status permits and what I may do ----
	list := jsonOf(t, do("finance", "GET", "/v1/staff/delivery-runs/runs?status=open", nil, 200))["items"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["locationName"] != "Bastrop" {
		t.Fatalf("list = %v", list)
	}
	open := jsonOf(t, do("finance", "GET", runs+runID, nil, 200))
	allowed, can := open["allowed"].(map[string]any), open["can"].(map[string]any)
	loc := open["location"].(map[string]any)
	if open["locationName"] != "Bastrop" || loc["area"].(map[string]any)["kind"] != "RADIUS" || loc["area"].(map[string]any)["radiusMiles"] != float64(12) {
		t.Fatalf("location = %v", open)
	}
	if allowed["extend"] != true || allowed["confirm"] != true || allowed["cancel"] != true || allowed["announce"] != false || allowed["delete"] != false || can["manage"] != false {
		t.Fatalf("an open run, seen by Finance: allowed %v, can %v", allowed, can)
	}
	draft := jsonOf(t, do("ops", "GET", runs+draftRunID, nil, 200))
	if a := draft["allowed"].(map[string]any); a["announce"] != true || a["delete"] != true || a["extend"] != false || draft["can"].(map[string]any)["manage"] != true {
		t.Fatalf("a draft: %v", draft)
	}
	road := jsonOf(t, do("ops", "GET", runs+onTheRoadRunID, nil, 200))["allowed"].(map[string]any)
	for k, v := range road {
		if v != false {
			t.Errorf("a run on the road allows %s", k)
		}
	}
	do("ops", "GET", runs+missingRunID, nil, 404)
	do("ops", "GET", runs+"nope", nil, 404)
	do("", "GET", runs+runID, nil, 401)

	// ---- scheduling ----
	schedule := func() map[string]any {
		return map[string]any{"locationId": runLocationID, "windowStart": "2026-11-07T10:00:00-06:00", "windowEnd": "2026-11-07T14:00:00-06:00", "cutoffAt": "2026-11-05T20:00:00-06:00",
			"minOrders": 12, "capacityOrders": 30, "feeMinor": nil, "notes": "  Cold bags in the van  ", "cutoffPolicy": "extend", "dropMode": "doorstep"}
	}
	created := do("ops", "POST", "/v1/staff/delivery-runs/runs", schedule(), 201)
	out := jsonOf(t, created)
	if out["status"] != "DRAFT" || out["locationName"] != "Bastrop" || out["allowed"] == nil || out["breakEvenOrders"] != float64(18) {
		t.Fatalf("created = %v", out)
	}
	got := sent("POST /v1/runs")
	if got["windowStart"] != "2026-11-07T16:00:00Z" || got["cutoffAt"] != "2026-11-06T02:00:00Z" || got["notes"] != "Cold bags in the van" || got["cutoffPolicy"] != "EXTEND" ||
		got["dropMode"] != "DOORSTEP" || got["minOrders"] != float64(12) {
		t.Fatalf("create relayed %v", got)
	}
	if _, has := got["feeMinor"]; has {
		t.Error("a null fee (the delivery fee rule decides) was sent as a custom fee")
	}
	custom := schedule()
	custom["feeMinor"] = 999
	custom["opensAt"] = "2026-11-02T09:00:00Z"
	do("marketing", "POST", "/v1/staff/delivery-runs/runs", custom, 201)
	if got := sent("POST /v1/runs"); got["feeMinor"] != float64(999) || got["opensAt"] != "2026-11-02T09:00:00Z" {
		t.Fatalf("custom fee relayed %v", got)
	}
	before := tried("POST /v1/runs")
	for name, c := range map[string]struct {
		mutate func(map[string]any)
		field  string
	}{
		"no location":        {func(b map[string]any) { delete(b, "locationId") }, "locationId"},
		"bad location":       {func(b map[string]any) { b["locationId"] = "bastrop" }, "locationId"},
		"no window":          {func(b map[string]any) { delete(b, "windowStart") }, "windowStart"},
		"text window":        {func(b map[string]any) { b["windowEnd"] = "afternoon" }, "windowEnd"},
		"window backwards":   {func(b map[string]any) { b["windowEnd"] = "2026-11-07T09:00:00-06:00" }, "windowEnd"},
		"window too long":    {func(b map[string]any) { b["windowEnd"] = "2026-11-08T11:00:00-06:00" }, "windowEnd"},
		"no cutoff":          {func(b map[string]any) { delete(b, "cutoffAt") }, "cutoffAt"},
		"cutoff after start": {func(b map[string]any) { b["cutoffAt"] = "2026-11-07T10:00:00-06:00" }, "cutoffAt"},
		"opens after cutoff": {func(b map[string]any) { b["opensAt"] = "2026-11-06T10:00:00Z" }, "opensAt"},
		"pickup too early":   {func(b map[string]any) { b["sellerPickupAt"] = "2026-11-01T10:00:00Z" }, "sellerPickupAt"},
		"zero minimum":       {func(b map[string]any) { b["minOrders"] = 0 }, "minOrders"},
		"huge minimum":       {func(b map[string]any) { b["minOrders"] = 10_001 }, "minOrders"},
		"fractional minimum": {func(b map[string]any) { b["minOrders"] = 2.5 }, "minOrders"},
		"capacity under min": {func(b map[string]any) { b["capacityOrders"] = 5 }, "capacityOrders"},
		"dear fee":           {func(b map[string]any) { b["feeMinor"] = 20_000 }, "feeMinor"},
		"negative fee":       {func(b map[string]any) { b["feeMinor"] = -1 }, "feeMinor"},
		"bad drop mode":      {func(b map[string]any) { b["dropMode"] = "DRONE" }, "dropMode"},
		"bad policy":         {func(b map[string]any) { b["cutoffPolicy"] = "SHRUG" }, "cutoffPolicy"},
		"long note":          {func(b map[string]any) { b["notes"] = strings.Repeat("n", 501) }, "notes"},
		"note as number":     {func(b map[string]any) { b["notes"] = 7 }, "notes"},
		"extend days":        {func(b map[string]any) { b["extendDays"] = 31 }, "extendDays"},
	} {
		b := schedule()
		c.mutate(b)
		if e := problems(do("ops", "POST", "/v1/staff/delivery-runs/runs", b, 422)); e[c.field] == nil {
			t.Errorf("%s: errors = %v, want %s", name, e, c.field)
		}
	}
	if tried("POST /v1/runs") != before {
		t.Error("an invalid run reached delivery-runs")
	}
	inactive := schedule()
	inactive["locationId"] = inactiveLocID
	if r := jsonOf(t, do("ops", "POST", "/v1/staff/delivery-runs/runs", inactive, 422)); !strings.Contains(r["detail"].(string), "not active") {
		t.Errorf("an inactive location: %v", r)
	}
	for _, token := range []string{"cs", "finance"} {
		do(token, "POST", "/v1/staff/delivery-runs/runs", schedule(), 403)
	}
	do("", "POST", "/v1/staff/delivery-runs/runs", schedule(), 401)

	// ---- changing a run before it opens ----
	edit := schedule()
	edit["minOrders"] = 15
	edit["notes"] = ""
	edited := jsonOf(t, do("ops", "PUT", runs+draftRunID, edit, 200))
	if edited["minOrders"] != float64(15) || edited["allowed"] == nil {
		t.Fatalf("edited = %v", edited)
	}
	if got := sent("PUT /v1/runs/" + draftRunID); got["notes"] != "" || got["minOrders"] != float64(15) {
		t.Fatalf("update relayed %v", got)
	}
	locked := schedule()
	locked["feeMinor"] = 1299
	do("ops", "PUT", runs+runID, locked, 422)
	bad := schedule()
	bad["cutoffAt"] = "2026-11-09T10:00:00Z"
	if e := problems(do("ops", "PUT", runs+draftRunID, bad, 422)); e["cutoffAt"] == nil {
		t.Errorf("errors = %v", e)
	}
	do("ops", "PUT", runs+"nope", schedule(), 404)
	do("ops", "PUT", runs+missingRunID, schedule(), 404)
	do("cs", "PUT", runs+draftRunID, schedule(), 403)
	do("", "PUT", runs+draftRunID, schedule(), 401)

	// ---- announce, open, delete ----
	if s := jsonOf(t, do("ops", "POST", runs+draftRunID+"/announce", nil, 200))["status"]; s != "ANNOUNCED" {
		t.Errorf("announce = %v", s)
	}
	if s := jsonOf(t, do("marketing", "POST", runs+draftRunID+"/open", nil, 200))["status"]; s != "OPEN" {
		t.Errorf("open = %v", s)
	}
	do("ops", "POST", runs+onTheRoadRunID+"/announce", nil, 409)
	do("ops", "POST", runs+onTheRoadRunID+"/open", nil, 409)
	for _, token := range []string{"cs", "finance"} {
		do(token, "POST", runs+draftRunID+"/announce", nil, 403)
		do(token, "POST", runs+draftRunID+"/open", nil, 403)
	}
	do("ops", "POST", runs+"nope/open", nil, 404)
	do("", "POST", runs+draftRunID+"/open", nil, 401)
	do("ops", "DELETE", runs+draftRunID, nil, 204)
	do("ops", "DELETE", runs+runID, nil, 422)
	do("cs", "DELETE", runs+draftRunID, nil, 403)
	do("ops", "DELETE", runs+"nope", nil, 404)
	do("", "DELETE", runs+draftRunID, nil, 401)

	// ---- extending: a reason is required and goes to the log ----
	extend := map[string]any{"cutoffAt": "2026-10-30T01:00:00+02:00", "reason": "  Two sellers need another day  "}
	extended := jsonOf(t, do("ops", "POST", runs+runID+"/extend", extend, 200))
	if extended["status"] != "EXTENDED" || extended["extensions"] != float64(1) {
		t.Fatalf("extended = %v", extended)
	}
	if got := sent("POST /v1/runs/" + runID + "/extend"); got["reason"] != "Two sellers need another day" || got["cutoffAt"] != "2026-10-29T23:00:00Z" {
		t.Fatalf("extend relayed %v", got)
	}
	before = tried("POST /v1/runs/" + runID + "/extend")
	for name, c := range map[string]struct {
		mutate func(map[string]any)
		field  string
	}{
		"no reason":     {func(b map[string]any) { delete(b, "reason") }, "reason"},
		"blank reason":  {func(b map[string]any) { b["reason"] = "  " }, "reason"},
		"long reason":   {func(b map[string]any) { b["reason"] = strings.Repeat("r", 501) }, "reason"},
		"no cutoff":     {func(b map[string]any) { delete(b, "cutoffAt") }, "cutoffAt"},
		"text cutoff":   {func(b map[string]any) { b["cutoffAt"] = "later" }, "cutoffAt"},
		"half a window": {func(b map[string]any) { b["windowStart"] = "2026-11-07T10:00:00Z" }, "windowEnd"},
		"window too big": {func(b map[string]any) {
			b["windowStart"], b["windowEnd"] = "2026-11-07T10:00:00Z", "2026-11-09T10:00:00Z"
		}, "windowEnd"},
		"cutoff in it": {func(b map[string]any) {
			b["windowStart"], b["windowEnd"] = "2026-10-29T10:00:00Z", "2026-10-29T14:00:00Z"
		}, "cutoffAt"},
	} {
		b := map[string]any{"cutoffAt": "2026-10-30T01:00:00Z", "reason": "Why"}
		c.mutate(b)
		if e := problems(do("ops", "POST", runs+runID+"/extend", b, 422)); e[c.field] == nil {
			t.Errorf("extend %s: errors = %v, want %s", name, e, c.field)
		}
	}
	if tried("POST /v1/runs/"+runID+"/extend") != before {
		t.Error("an invalid extension reached delivery-runs")
	}
	do("ops", "POST", runs+onTheRoadRunID+"/extend", extend, 409)
	do("finance", "POST", runs+runID+"/extend", extend, 403)
	do("ops", "POST", runs+"nope/extend", extend, 404)
	do("", "POST", runs+runID+"/extend", extend, 401)

	// ---- confirming: below the minimum only with a reason ----
	confirmCalls := tried("POST /v1/runs/" + runID + "/confirm")
	for _, body := range []map[string]any{nil, {}, {"reason": "   "}} {
		if e := problems(do("ops", "POST", runs+runID+"/confirm", body, 422)); e["reason"] == nil {
			t.Errorf("confirm %v: errors = %v", body, e)
		}
	}
	do("ops", "POST", runs+runID+"/confirm", map[string]any{"reason": strings.Repeat("r", 501)}, 422)
	if tried("POST /v1/runs/"+runID+"/confirm") != confirmCalls {
		t.Error("a confirmation below the minimum without a reason reached delivery-runs")
	}
	confirmed := jsonOf(t, do("ops", "POST", runs+runID+"/confirm", map[string]any{"reason": "  Sellers already packed  "}, 200))
	if confirmed["status"] != "CONFIRMED" || confirmed["allowed"].(map[string]any)["cancel"] != true || confirmed["allowed"].(map[string]any)["extend"] != false {
		t.Fatalf("confirmed = %v", confirmed)
	}
	if got := sent("POST /v1/runs/" + runID + "/confirm"); got["reason"] != "Sellers already packed" {
		t.Fatalf("confirm relayed %v", got)
	}
	do("marketing", "POST", runs+metRunID+"/confirm", nil, 200)
	if got := sent("POST /v1/runs/" + metRunID + "/confirm"); len(got) != 0 {
		t.Errorf("a run at its minimum needs no reason: relayed %v", got)
	}
	do("ops", "POST", runs+onTheRoadRunID+"/confirm", map[string]any{"reason": "x"}, 409)
	do("cs", "POST", runs+metRunID+"/confirm", nil, 403)
	do("ops", "POST", runs+"nope/confirm", nil, 404)
	do("ops", "POST", runs+missingRunID+"/confirm", map[string]any{"reason": "x"}, 404)
	do("", "POST", runs+metRunID+"/confirm", nil, 401)

	// ---- cancelling: a reason is required, every order is refunded ----
	cancelCalls := tried("POST /v1/runs/" + runID + "/cancel")
	for _, body := range []map[string]any{nil, {}, {"reason": " "}, {"reason": strings.Repeat("r", 501)}} {
		if e := problems(do("ops", "POST", runs+runID+"/cancel", body, 422)); e["reason"] == nil {
			t.Errorf("cancel %v: errors = %v", body, e)
		}
	}
	if tried("POST /v1/runs/"+runID+"/cancel") != cancelCalls {
		t.Error("a cancellation without a reason reached delivery-runs")
	}
	cancelled := jsonOf(t, do("marketing", "POST", runs+runID+"/cancel", map[string]any{"reason": "  Tropical storm warning  "}, 200))
	if cancelled["status"] != "CANCELLED" || cancelled["cancelReason"] != "Tropical storm warning" {
		t.Fatalf("cancelled = %v", cancelled)
	}
	if got := sent("POST /v1/runs/" + runID + "/cancel"); got["reason"] != "Tropical storm warning" {
		t.Fatalf("cancel relayed %v", got)
	}
	do("ops", "POST", runs+onTheRoadRunID+"/cancel", map[string]any{"reason": "x"}, 409)
	for _, token := range []string{"cs", "finance"} {
		do(token, "POST", runs+runID+"/cancel", map[string]any{"reason": "x"}, 403)
	}
	do("ops", "POST", runs+"nope/cancel", map[string]any{"reason": "x"}, 404)
	do("", "POST", runs+runID+"/cancel", map[string]any{"reason": "x"}, 401)

	// ---- the log names people and the job, never ids ----
	logged := jsonOf(t, do("finance", "GET", runs+runID+"/log", nil, 200))["items"].([]any)
	if len(logged) != 2 {
		t.Fatalf("log = %v", logged)
	}
	job, person := logged[0].(map[string]any), logged[1].(map[string]any)
	if job["actor"] != "System" || job["bySystem"] != true || job["reason"] != "MINIMUM_NOT_MET" || job["kind"] != "EXTEND" {
		t.Errorf("the cutoff job's entry: %v", job)
	}
	if person["actor"] != "Amara Nwosu" || person["bySystem"] != false || person["kind"] != "CONFIRM_OVERRIDE" || person["reason"] != "Sellers already packed" || person["actor"] == staffActorID {
		t.Errorf("the staff entry: %v", person)
	}
	do("", "GET", runs+runID+"/log", nil, 401)
	do("ops", "GET", runs+"nope/log", nil, 404)
	do("ops", "GET", runs+missingRunID+"/log", nil, 404)

	// ---- the orders on a run ----
	rows := jsonOf(t, do("finance", "GET", runs+runID+"/orders", nil, 200))["items"].([]any)
	if len(rows) != 1 || rows[0].(map[string]any)["customerName"] != "Amara Nwosu" || rows[0].(map[string]any)["orderNumber"] == nil {
		t.Fatalf("orders = %v", rows)
	}
	if _, leaked := rows[0].(map[string]any)["handoverCode"]; leaked {
		t.Error("a handover code was relayed to the console")
	}
	if n := len(jsonOf(t, do("ops", "GET", runs+runID+"/orders?status=cancelled", nil, 200))["items"].([]any)); n != 0 {
		t.Errorf("cancelled orders = %d", n)
	}
	do("ops", "GET", runs+runID+"/orders?status=maybe", nil, 422)
	do("", "GET", runs+runID+"/orders", nil, 401)
	do("ops", "GET", runs+"nope/orders", nil, 404)

	for _, op := range []string{"createStaffDeliveryRun", "getStaffDeliveryRun", "updateStaffDeliveryRun", "deleteStaffDeliveryRun", "announceStaffDeliveryRun",
		"openStaffDeliveryRun", "extendStaffDeliveryRun", "confirmStaffDeliveryRun", "cancelStaffDeliveryRun", "listStaffDeliveryRunLog", "listStaffDeliveryRunOrders",
		"listStaffPreorderRuns"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
