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

// feeRule is the fake delivery-runs' answer for one rule (version 3).
func feeRule(method, currency string) map[string]any {
	r := map[string]any{"id": "7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a11", "method": method, "currency": currency, "version": 3, "bands": []any{},
		"feeMinor": 0, "handlingMinor": 0, "fallbackMinor": 0, "freeAboveMinor": nil, "coverageMiles": 0,
		"updatedAt": "2026-10-24T12:00:00Z", "updatedBy": "stf-21", "reason": "Texas launch rates"}
	switch method {
	case "AFROTRANSACT_DELIVERY":
		r["bands"] = []any{map[string]any{"fromMiles": 0, "toMiles": 10, "feeMinor": 599}, map[string]any{"fromMiles": 10, "toMiles": 40, "feeMinor": 799}}
		r["coverageMiles"] = 40
	case "CARRIER":
		r["handlingMinor"], r["fallbackMinor"], r["freeAboveMinor"] = 0, 599, 3500
	case "PICKUP":
		r["feeMinor"] = 100
	}
	return r
}

// deliveryFees fakes delivery-runs' staff delivery fee routes (tag Delivery fees).
func (f *fakeDeliveryRuns) deliveryFees(w http.ResponseWriter, r *http.Request) {
	body := f.record(r)
	f.mu.Lock()
	key := r.Method + " " + r.URL.EscapedPath()
	f.paths = append(f.paths, key)
	f.ifMatch[key] = r.Header.Get("If-Match")
	f.mu.Unlock()
	p := r.URL.Path
	switch {
	case p == "/v1/delivery-fees" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"items": []any{feeRule("AFROTRANSACT_DELIVERY", "USD"), feeRule("CARRIER", "USD"), feeRule("PICKUP", "USD")}})
	case p == "/v1/delivery-fees/changes":
		before := feeRule("PICKUP", "USD")
		before["feeMinor"], before["version"] = 0, 2
		writeJ(w, 200, []any{
			map[string]any{"id": "chg-2", "method": "PICKUP", "currency": "USD", "version": 3, "before": before, "after": feeRule("PICKUP", "USD"),
				"actor": "stf-21", "reason": "Small pickup fee", "at": "2026-10-24T12:00:00Z"},
			map[string]any{"id": "chg-1", "method": "PICKUP", "currency": "USD", "version": 2, "before": nil, "after": before,
				"actor": "migration", "reason": "Carried over from the pre-order payment settings", "at": "2026-10-20T09:00:00Z"}})
	case p == "/v1/delivery-fees/preview":
		if body["method"] == "AFROTRANSACT_DELIVERY" && body["distanceMiles"] == float64(90) {
			writeJ(w, 200, map[string]any{"applies": false, "fee": nil})
			return
		}
		writeJ(w, 200, map[string]any{"applies": true, "fee": map[string]any{"method": body["method"], "ruleId": "7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a11",
			"ruleVersion": 3, "currency": body["currency"], "amountMinor": 799, "source": "RULE", "band": "10–40 mi", "distanceMiles": 32.0}})
	case strings.HasPrefix(p, "/v1/delivery-fees/") && r.Method == http.MethodPut:
		if r.Header.Get("If-Match") == `"2"` {
			problemDetail(w, 412, "delivery-runs.version-mismatch", "The AFROTRANSACT_DELIVERY fee in USD changed since version 2 (now 3); reload and retry")
			return
		}
		parts := strings.Split(strings.TrimPrefix(p, "/v1/delivery-fees/"), "/")
		out := feeRule(parts[0], parts[1])
		out["version"], out["reason"], out["updatedBy"] = 4, body["reason"], "stf-21"
		for _, k := range []string{"bands", "feeMinor", "handlingMinor", "fallbackMinor", "freeAboveMinor"} {
			if v, ok := body[k]; ok {
				out[k] = v
			}
		}
		writeJ(w, 200, out)
	default:
		http.NotFound(w, r)
	}
}

// TestStaffDeliveryFeesConformance drives every delivery fee operation through the BFF against the fake delivery-runs
// and validates each answer against gateway-bff-staff.yaml: the three rules, a change with its required reason (field
// problems come back before anything is sent), the version relay (If-Match, ETag, 412), the audit trail and the
// preview checkout's resolution. Only managers (Marketing, Operations) change a rule; Finance and the auditor read.
func TestStaffDeliveryFeesConformance(t *testing.T) {
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

	// List: every role that can open the module reads; only managers can change.
	rules := jsonOf(t, do("ops", "GET", "/v1/staff/delivery-fees", nil, 200))
	items, _ := rules["items"].([]any)
	if len(items) != 3 || rules["can"].(map[string]any)["manage"] != true {
		t.Fatalf("rules = %v", rules)
	}
	if c := jsonOf(t, do("finance", "GET", "/v1/staff/delivery-fees", nil, 200))["can"].(map[string]any); c["manage"] != false {
		t.Fatalf("finance can = %v", c)
	}
	do("auditor", "GET", "/v1/staff/delivery-fees", nil, 200)
	do("", "GET", "/v1/staff/delivery-fees", nil, 401)

	// Change a distance-band rule: reason required, numbers checked first, If-Match and ETag relayed.
	bands := []any{map[string]any{"fromMiles": 0, "toMiles": 20, "feeMinor": 649}, map[string]any{"fromMiles": 20, "toMiles": 60, "feeMinor": 899}}
	put := "/v1/staff/delivery-fees/AFROTRANSACT_DELIVERY/USD"
	saved := do("marketing", "PUT", put, map[string]any{"bands": bands, "reason": "  Fuel went up  ", "feeMinor": 5}, 200, "If-Match", `"3"`)
	if saved.Header().Get("ETag") != `"4"` {
		t.Fatalf("ETag = %q", saved.Header().Get("ETag"))
	}
	out := jsonOf(t, saved)
	if out["version"] != float64(4) || out["reason"] != "Fuel went up" || out["can"] == nil {
		t.Fatalf("saved = %v", out)
	}
	relayed := sent("PUT /v1/delivery-fees/AFROTRANSACT_DELIVERY/USD")
	if relayed["reason"] != "Fuel went up" || len(relayed["bands"].([]any)) != 2 || relayed["feeMinor"] != nil {
		t.Fatalf("relayed %v", relayed)
	}
	if f.ifMatch["PUT /v1/delivery-fees/AFROTRANSACT_DELIVERY/USD"] != `"3"` {
		t.Fatalf("If-Match relayed = %q", f.ifMatch["PUT /v1/delivery-fees/AFROTRANSACT_DELIVERY/USD"])
	}
	// The other methods and a lower-case currency.
	if o := jsonOf(t, do("ops", "PUT", "/v1/staff/delivery-fees/carrier/usd", map[string]any{"handlingMinor": 200, "fallbackMinor": 799, "freeAboveMinor": nil,
		"reason": "Carrier handling"}, 200)); o["method"] != "CARRIER" || o["fallbackMinor"] != float64(799) {
		t.Fatalf("carrier = %v", o)
	}
	if r := sent("PUT /v1/delivery-fees/CARRIER/USD"); r["freeAboveMinor"] != nil || r["handlingMinor"] != float64(200) {
		t.Fatalf("carrier relayed %v", r)
	}
	do("ops", "PUT", "/v1/staff/delivery-fees/PICKUP/USD", map[string]any{"feeMinor": 0, "reason": "Pickup is free"}, 200)

	// Nothing reaches delivery-runs when the request is wrong: a reason, the numbers and the path are checked first.
	before := tried("PUT /v1/delivery-fees/AFROTRANSACT_DELIVERY/USD")
	for name, body := range map[string]map[string]any{
		"no reason":    {"bands": bands},
		"blank reason": {"bands": bands, "reason": "   "},
		"long reason":  {"bands": bands, "reason": strings.Repeat("x", 501)},
		"no bands":     {"reason": "why"},
		"gap":          {"bands": []any{map[string]any{"fromMiles": 0, "toMiles": 10, "feeMinor": 599}, map[string]any{"fromMiles": 15, "toMiles": 30, "feeMinor": 699}}, "reason": "why"},
		"not from 0":   {"bands": []any{map[string]any{"fromMiles": 5, "toMiles": 30, "feeMinor": 699}}, "reason": "why"},
		"fee too high": {"bands": []any{map[string]any{"fromMiles": 0, "toMiles": 30, "feeMinor": 20000}}, "reason": "why"},
	} {
		e := jsonOf(t, do("ops", "PUT", put, body, 422))
		if e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}
	// A missing reason is reported together with the numbers' problems.
	if e := jsonOf(t, do("ops", "PUT", put, map[string]any{"bands": []any{}}, 422))["errors"].(map[string]any); e["bands"] == nil || e["reason"] == nil {
		t.Fatalf("errors = %v", e)
	}
	do("ops", "PUT", "/v1/staff/delivery-fees/PICKUP/USD", map[string]any{"reason": "why"}, 422)
	do("ops", "PUT", "/v1/staff/delivery-fees/CARRIER/USD", map[string]any{"handlingMinor": -1, "fallbackMinor": 10, "reason": "why"}, 422)
	do("ops", "PUT", "/v1/staff/delivery-fees/DRONE/USD", map[string]any{"feeMinor": 1, "reason": "why"}, 404)
	do("ops", "PUT", "/v1/staff/delivery-fees/PICKUP/DOLLARS", map[string]any{"feeMinor": 1, "reason": "why"}, 404)
	if tried("PUT /v1/delivery-fees/AFROTRANSACT_DELIVERY/USD") != before {
		t.Error("an invalid change reached delivery-runs")
	}
	// A stale version (someone saved meanwhile) is relayed as 412; viewers and the signed-out cannot change anything.
	do("ops", "PUT", put, map[string]any{"bands": bands, "reason": "Stale"}, 412, "If-Match", `"2"`)
	for _, token := range []string{"cs", "finance", "auditor"} {
		do(token, "PUT", put, map[string]any{"bands": bands, "reason": "x"}, 403)
	}
	do("", "PUT", put, map[string]any{"bands": bands, "reason": "x"}, 401)

	// Audit trail: filters are checked, answers are relayed as {items}.
	changes := jsonOf(t, do("finance", "GET", "/v1/staff/delivery-fees/changes?method=pickup&currency=usd&limit=20", nil, 200))
	list, _ := changes["items"].([]any)
	if len(list) != 2 || list[0].(map[string]any)["actor"] != "stf-21" || list[1].(map[string]any)["before"] != nil {
		t.Fatalf("changes = %v", changes)
	}
	do("ops", "GET", "/v1/staff/delivery-fees/changes", nil, 200)
	for _, q := range []string{"method=drone", "currency=dollars", "limit=0", "limit=500", "limit=abc"} {
		do("ops", "GET", "/v1/staff/delivery-fees/changes?"+q, nil, 422)
	}
	do("", "GET", "/v1/staff/delivery-fees/changes", nil, 401)

	// Preview: the resolution checkout runs; read access is enough.
	p := jsonOf(t, do("finance", "POST", "/v1/staff/delivery-fees/preview",
		map[string]any{"method": "afrotransact_delivery", "currency": "usd", "distanceMiles": 32}, 200))
	if p["applies"] != true || p["fee"].(map[string]any)["band"] != "10–40 mi" {
		t.Fatalf("preview = %v", p)
	}
	if got := sent("POST /v1/delivery-fees/preview"); got["method"] != "AFROTRANSACT_DELIVERY" || got["currency"] != "USD" || got["distanceMiles"] != float64(32) {
		t.Fatalf("preview relayed %v", got)
	}
	if p := jsonOf(t, do("ops", "POST", "/v1/staff/delivery-fees/preview", map[string]any{"method": "AFROTRANSACT_DELIVERY", "currency": "USD", "distanceMiles": 90}, 200)); p["applies"] != false {
		t.Fatalf("beyond coverage = %v", p)
	}
	do("ops", "POST", "/v1/staff/delivery-fees/preview", map[string]any{"method": "CARRIER", "currency": "USD", "carrierQuoteMinor": 1150, "itemsMinor": 2000}, 200)
	do("ops", "POST", "/v1/staff/delivery-fees/preview", map[string]any{"method": "PICKUP", "currency": "USD", "windowId": "win-7"}, 200)
	for name, body := range map[string]map[string]any{
		"no method":       {"currency": "USD"},
		"unknown method":  {"method": "DRONE", "currency": "USD"},
		"bad currency":    {"method": "PICKUP", "currency": "US"},
		"negative miles":  {"method": "AFROTRANSACT_DELIVERY", "currency": "USD", "distanceMiles": -1},
		"far miles":       {"method": "AFROTRANSACT_DELIVERY", "currency": "USD", "distanceMiles": 900},
		"bad window":      {"method": "PICKUP", "currency": "USD", "windowId": "bad id!"},
		"fractional cent": {"method": "CARRIER", "currency": "USD", "carrierQuoteMinor": 10.5},
		"negative items":  {"method": "CARRIER", "currency": "USD", "itemsMinor": -5},
	} {
		if e := jsonOf(t, do("ops", "POST", "/v1/staff/delivery-fees/preview", body, 422)); e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
	}
	do("", "POST", "/v1/staff/delivery-fees/preview", map[string]any{"method": "PICKUP", "currency": "USD"}, 401)

	for _, op := range []string{"listStaffDeliveryFees", "updateStaffDeliveryFee", "listStaffDeliveryFeeChanges", "previewStaffDeliveryFee"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
