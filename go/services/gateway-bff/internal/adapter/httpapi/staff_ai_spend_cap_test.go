package httpapi_test

import (
	"bytes"
	"context"
	"io"
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

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

const capChangerID = "6b1f2a40-3c1e-4d7e-9a55-0d2f6c1b7e11"

// TestStaffAISpendCapConformance: Settings > AI and MCP spend cap is editable, and the BFF decides nothing about the value:
// it checks the permission it already knows (ai:cap.manage to change, ai:manage or ai:cap.manage to look), validates the
// shape the form can get wrong, and relays reads and the change with the staff member's own token, If-Match and reason.
// People in the history are named. Answers match the contract and are never cached.
func TestStaffAISpendCapConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var reads, writes []map[string]any
	var gwStatus int
	gwDoc := func(canChange bool) map[string]any {
		return map[string]any{"capMicros": 5000000, "source": "saved", "defaultMicros": 2000000, "ceilingMicros": 20000000, "version": 3,
			"changedBy": capChangerID, "changedAt": "2026-11-16T09:00:00Z", "reason": "Launch week", "day": "2026-11-16", "spentMicros": 1400000,
			"remainingMicros": 3600000, "resetsAt": "2026-11-17T00:00:00Z", "canChange": canChange, "history": []any{
				map[string]any{"version": 3, "previousMicros": 3000000, "previousWasDefault": false, "newMicros": 5000000, "changedBy": capChangerID,
					"changedAt": "2026-11-16T09:00:00Z", "reason": "Launch week"},
				map[string]any{"version": 1, "previousMicros": nil, "previousWasDefault": true, "newMicros": 3000000, "changedBy": capChangerID,
					"changedAt": "2026-11-10T09:00:00Z", "reason": "First change"}}}
	}
	f := &fakePlatform{payoutSt: "REQUESTED"}
	f.override = func(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if r.URL.Path == "/v1/me" && token == "owner" {
			writeJ(w, 200, map[string]any{"id": "stf-8", "firstName": "Chidi", "lastName": "Eze", "principalType": "STAFF",
				"roles": []string{"platform.product_owner"}, "permissions": []string{"ai:manage", "access:read"}})
			return true
		}
		if r.URL.Path != "/v1/ai/spend-cap" {
			return false
		}
		mu.Lock()
		defer mu.Unlock()
		if r.Method == http.MethodGet {
			reads = append(reads, map[string]any{"auth": r.Header.Get("Authorization")})
			writeJ(w, 200, gwDoc(token == "admin"))
			return true
		}
		writes = append(writes, map[string]any{"auth": r.Header.Get("Authorization"), "ifMatch": r.Header.Get("If-Match"), "body": body})
		if gwStatus != 0 {
			problemJ(w, gwStatus, "ai.spend-cap.version-mismatch")
			return true
		}
		saved := gwDoc(true)
		saved["capMicros"], saved["version"], saved["reason"] = int(body["capMicros"].(float64)), 4, body["reason"]
		writeJ(w, 200, saved)
		return true
	}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) {
		ai := httptest.NewServer(f)
		t.Cleanup(ai.Close)
		d.Up.AI = upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: "ai-gateway", BaseURL: ai.URL, Timeout: 3 * time.Second,
			Metrics: httpclient.NewMetrics(prometheus.NewRegistry()), Transport: http.DefaultTransport}))
		staff.AISpend = app.NewStaffAISpendService(d)
	})
	conforms := func(rec *httptest.ResponseRecorder, method string) {
		t.Helper()
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal/v1/staff/ai/spend-cap", nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatal(err)
		}
		in := &openapi3filter.ResponseValidationInput{RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
			Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}}, Status: rec.Code, Header: rec.Header(),
			Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())), Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true}}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s does not match the contract: %v\n%s", method, err, rec.Body.String())
		}
	}
	put := func(token, ifMatch string, body any, want int) *httptest.ResponseRecorder {
		t.Helper()
		var rec *httptest.ResponseRecorder
		if ifMatch == "" {
			rec = raw(token, "PUT", "/v1/staff/ai/spend-cap", body)
		} else {
			rec = raw(token, "PUT", "/v1/staff/ai/spend-cap", body, "If-Match", ifMatch)
		}
		if rec.Code != want {
			t.Fatalf("PUT as %q = %d, want %d: %s", token, rec.Code, want, rec.Body.String())
		}
		return rec
	}

	// Reading: the cap, source, ceiling, spend and remaining, people named, version as the ETag.
	rec := raw("admin", "GET", "/v1/staff/ai/spend-cap", nil)
	if rec.Code != 200 {
		t.Fatalf("GET = %d: %s", rec.Code, rec.Body.String())
	}
	conforms(rec, "GET")
	if !strings.Contains(rec.Header().Get("Cache-Control"), "no-store") || rec.Header().Get("ETag") != `"3"` {
		t.Errorf("Cache-Control %q ETag %q", rec.Header().Get("Cache-Control"), rec.Header().Get("ETag"))
	}
	got := jsonOf(t, rec)
	hist := got["history"].([]any)
	if got["capMicros"].(float64) != 5000000 || got["source"] != "saved" || got["ceilingMicros"].(float64) != 20000000 || got["canChange"] != true ||
		got["remainingMicros"].(float64) != 3600000 || got["changedByName"] != "Amara Nwosu" || len(hist) != 2 ||
		hist[0].(map[string]any)["changedByName"] != "Amara Nwosu" || hist[1].(map[string]any)["previousMicros"] != nil {
		t.Fatalf("cap = %v", got)
	}
	if strings.Contains(rec.Body.String(), capChangerID) {
		t.Errorf("a person's id reached the console: %s", rec.Body.String())
	}
	if len(reads) != 1 || reads[0]["auth"] != "Bearer admin" {
		t.Fatalf("ai-gateway should see the staff member's own token: %v", reads)
	}

	// A staff member with ai:manage alone may look, and ai-gateway (not the BFF) says they cannot change.
	rec = raw("owner", "GET", "/v1/staff/ai/spend-cap", nil)
	if rec.Code != 200 || jsonOf(t, rec)["canChange"] != false {
		t.Fatalf("view-only GET = %d %s", rec.Code, rec.Body.String())
	}
	conforms(rec, "GET")

	// Changing: ai:cap.manage, the form's own mistakes caught first, then relayed with If-Match, reason and token.
	rec = put("admin", `"3"`, map[string]any{"capMicros": 7500000, "reason": "  Diaspora week promotion "}, 200)
	conforms(rec, "PUT")
	if rec.Header().Get("ETag") != `"4"` || !strings.Contains(rec.Header().Get("Cache-Control"), "no-store") {
		t.Errorf("PUT ETag %q Cache-Control %q", rec.Header().Get("ETag"), rec.Header().Get("Cache-Control"))
	}
	if saved := jsonOf(t, rec); saved["capMicros"].(float64) != 7500000 || saved["reason"] != "Diaspora week promotion" || saved["version"].(float64) != 4 {
		t.Fatalf("saved = %v", saved)
	}
	if len(writes) != 1 || writes[0]["auth"] != "Bearer admin" || writes[0]["ifMatch"] != `"3"` {
		t.Fatalf("relay = %v", writes)
	}
	sent := writes[0]["body"].(map[string]any)
	if sent["capMicros"].(float64) != 7500000 || sent["reason"] != "Diaspora week promotion" || len(sent) != 2 {
		t.Fatalf("body sent to ai-gateway = %v", sent)
	}

	// Zero (AI off) is a valid value.
	put("admin", `"4"`, map[string]any{"capMicros": 0, "reason": "Pause AI"}, 200)

	before := len(writes)
	bad := jsonOf(t, put("admin", `"4"`, map[string]any{"capMicros": 1234567, "reason": "no"}, 422))
	fields, _ := bad["errors"].(map[string]any)
	if fields["capMicros"] == nil || fields["reason"] == nil {
		t.Fatalf("validation = %v", bad)
	}
	put("admin", `"4"`, map[string]any{"capMicros": -10000, "reason": "Negative"}, 422)
	put("admin", `"4"`, map[string]any{"capMicros": "5", "reason": "A string"}, 422)
	put("admin", `"4"`, map[string]any{"capMicros": 1000000, "reason": strings.Repeat("x", 301)}, 422)
	if p := jsonOf(t, put("admin", "", map[string]any{"capMicros": 1000000, "reason": "No version"}, 428)); p["code"] != "VERSION_REQUIRED" {
		t.Fatalf("428 = %v", p)
	}
	if len(writes) != before {
		t.Fatalf("a request the BFF refused reached ai-gateway: %v", writes[before:])
	}

	// Who may change it: ai:cap.manage only; look-only staff, support and the auditor are refused before ai-gateway is asked.
	put("owner", `"3"`, map[string]any{"capMicros": 1000000, "reason": "Not allowed"}, 403)
	put("support", `"3"`, map[string]any{"capMicros": 1000000, "reason": "Not allowed"}, 403)
	put("auditor", `"3"`, map[string]any{"capMicros": 1000000, "reason": "Not allowed"}, 403)
	put("", `"3"`, map[string]any{"capMicros": 1000000, "reason": "Signed out"}, 401)
	if rec := raw("support", "GET", "/v1/staff/ai/spend-cap", nil); rec.Code != 403 {
		t.Fatalf("support GET = %d", rec.Code)
	}
	if len(writes) != before || len(reads) != 2 {
		t.Fatalf("a refused request reached ai-gateway: writes %d reads %d", len(writes), len(reads))
	}

	// Someone else saved first: ai-gateway's 412 is the console's version conflict.
	gwStatus = http.StatusPreconditionFailed
	if p := jsonOf(t, put("admin", `"2"`, map[string]any{"capMicros": 1000000, "reason": "Stale"}, 412)); p["code"] != "VERSION_CONFLICT" {
		t.Fatalf("412 = %v", p)
	}
}
