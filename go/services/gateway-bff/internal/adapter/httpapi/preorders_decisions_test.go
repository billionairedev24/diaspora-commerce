package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
)

// TestStaffPreorderDecisionsConformance drives every operation added for ADR 0005 §4 through the BFF against the fake
// delivery-runs and validates each answer against gateway-bff-staff.yaml: the minimum order count on windows, the
// decision for a window that closed below it (cancel and refund everyone, or proceed; a reason is required), cancelling
// a window, closing early, the decision log and the refund progress. Only managers (Marketing, Operations) may decide.
func TestStaffPreorderDecisionsConformance(t *testing.T) {
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

	// Decide: CANCEL refunds everyone, PROCEED goes ahead; the reason is required and relayed, If-Match too.
	cancelled := jsonOf(t, do("ops", "POST", "/v1/staff/preorders/win-7/decision",
		map[string]any{"decision": "cancel", "reason": "  Not enough orders to make the trip  "}, 200, "If-Match", `"3"`))
	if cancelled["status"] != "CANCELLED" || cancelled["outcome"] != "CANCELLED" || cancelled["can"] == nil {
		t.Fatalf("cancelled = %v", cancelled)
	}
	sent := f.bodies["POST /v1/preorders/win-7/decision"]
	if sent["decision"] != "CANCEL" || sent["reason"] != "Not enough orders to make the trip" || f.ifMatch["POST /v1/preorders/win-7/decision"] != `"3"` {
		t.Fatalf("relayed %v, If-Match %q", sent, f.ifMatch["POST /v1/preorders/win-7/decision"])
	}
	if proceed := jsonOf(t, do("marketing", "POST", "/v1/staff/preorders/win-7/decision",
		map[string]any{"decision": "PROCEED", "reason": "Two orders still cover the drive"}, 200)); proceed["outcome"] != "PROCEEDED" {
		t.Fatalf("proceed = %v", proceed)
	}
	for name, body := range map[string]map[string]any{
		"no reason":      {"decision": "CANCEL"},
		"blank reason":   {"decision": "CANCEL", "reason": "   "},
		"long reason":    {"decision": "CANCEL", "reason": strings.Repeat("x", 501)},
		"no decision":    {"reason": "why"},
		"unknown choice": {"decision": "REFUND", "reason": "why"},
	} {
		before := calls(f, "POST /v1/preorders/win-7/decision")
		e := jsonOf(t, do("ops", "POST", "/v1/staff/preorders/win-7/decision", body, 422))
		if e["errors"] == nil {
			t.Errorf("%s: errors = %v", name, e)
		}
		if calls(f, "POST /v1/preorders/win-7/decision") != before {
			t.Errorf("%s reached delivery-runs", name)
		}
	}
	// A window that cannot be decided any more (delivery-runs' 409) is relayed.
	do("ops", "POST", "/v1/staff/preorders/win-gone/decision", map[string]any{"decision": "CANCEL", "reason": "late"}, 409)
	for _, token := range []string{"cs", "finance"} {
		do(token, "POST", "/v1/staff/preorders/win-7/decision", map[string]any{"decision": "CANCEL", "reason": "x"}, 403)
		do(token, "POST", "/v1/staff/preorders/win-7/cancel", map[string]any{"reason": "x"}, 403)
		do(token, "POST", "/v1/staff/preorders/win-7/close-early", map[string]any{"reason": "x"}, 403)
	}
	do("", "POST", "/v1/staff/preorders/win-7/decision", map[string]any{"decision": "CANCEL", "reason": "x"}, 401)
	do("ops", "POST", "/v1/staff/preorders/bad%20id/decision", map[string]any{"decision": "CANCEL", "reason": "x"}, 404)

	// Cancel any window; close an open one early.
	if c := jsonOf(t, do("ops", "POST", "/v1/staff/preorders/win-7/cancel", map[string]any{"reason": "Seller stock fell through"}, 200, "If-Match", `"4"`)); c["status"] != "CANCELLED" {
		t.Fatalf("cancel = %v", c)
	}
	if got := f.bodies["POST /v1/preorders/win-7/cancel"]; got["reason"] != "Seller stock fell through" || f.ifMatch["POST /v1/preorders/win-7/cancel"] != `"4"` {
		t.Fatalf("cancel relayed %v", got)
	}
	do("ops", "POST", "/v1/staff/preorders/win-7/cancel", map[string]any{}, 422)
	do("ops", "POST", "/v1/staff/preorders/win-gone/cancel", map[string]any{"reason": "late"}, 409)
	if c := jsonOf(t, do("marketing", "POST", "/v1/staff/preorders/win-7/close-early", map[string]any{"reason": "Both seller lots are gone"}, 200)); c["status"] != "OPEN" {
		t.Fatalf("close-early = %v", c)
	}
	if got := f.bodies["POST /v1/preorders/win-7/close-early"]; got["reason"] != "Both seller lots are gone" {
		t.Fatalf("close-early relayed %v", got)
	}
	do("marketing", "POST", "/v1/staff/preorders/win-7/close-early", map[string]any{"reason": ""}, 422)
	do("ops", "POST", "/v1/staff/preorders/win-gone/close-early", map[string]any{"reason": "late"}, 409)

	// The decision log and the refund progress: anyone with VIEW.
	log := jsonOf(t, do("finance", "GET", "/v1/staff/preorders/win-7/decisions", nil, 200))
	entries, _ := log["items"].([]any)
	if len(entries) != 2 || entries[1].(map[string]any)["actor"] != "stf-21" || entries[1].(map[string]any)["reason"] == "" {
		t.Fatalf("decision log = %v", log)
	}
	do("cs", "GET", "/v1/staff/preorders/win-7/decisions", nil, 200)
	do("", "GET", "/v1/staff/preorders/win-7/decisions", nil, 401)
	do("ops", "GET", "/v1/staff/preorders/bad%20id/decisions", nil, 404)
	refunds := jsonOf(t, do("cs", "GET", "/v1/staff/preorders/win-7/refunds", nil, 200))
	if refunds["pending"] != float64(2) || refunds["refunded"] != float64(4) || refunds["failed"] != float64(1) || refunds["done"] != false {
		t.Fatalf("refunds = %v", refunds)
	}
	do("", "GET", "/v1/staff/preorders/win-7/refunds", nil, 401)

	// Each order of a cancelled window carries its refund status (a column of the orders table, searchable and sortable).
	page := jsonOf(t, do("cs", "GET", "/v1/staff/preorders/win-7/orders?q=refunded", nil, 200))
	if items := page["items"].([]any); len(items) != 1 || items[0].(map[string]any)["number"] != "LBK-1001" || items[0].(map[string]any)["refundStatus"] != "REFUNDED" {
		t.Fatalf("orders by refund status = %v", page)
	}

	// The minimum is part of the window the console edits (relayed untouched), and the list says what a new one defaults to.
	do("ops", "PUT", "/v1/staff/preorders/win-7", map[string]any{"name": "Midland Pre-order #1", "targets": []string{"US/TX/Midland"}, "minOrders": 25}, 200, "If-Match", `"3"`)
	if got := f.bodies["PUT /v1/preorders/win-7"]; got["minOrders"] != float64(25) {
		t.Fatalf("minOrders not relayed: %v", got)
	}

	for _, op := range []string{"decideStaffPreorder", "cancelStaffPreorder", "closeEarlyStaffPreorder", "listStaffPreorderDecisions", "getStaffPreorderRefunds"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
}

// calls counts the requests that reached the fake delivery-runs with this method and path.
func calls(f *fakeDeliveryRuns, key string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, p := range f.paths {
		if p == key {
			n++
		}
	}
	return n
}
