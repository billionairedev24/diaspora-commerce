package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
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

// TestStaffAISpendConformance: Settings > AI and MCP spend cap's live figure comes from ai-gateway's ledger and nowhere else. A day
// with usage is relayed by feature with people's names for the features; a day without any is zero with hasData false; the
// permission is ai:manage (and only that); the answer matches the contract and is never cached.
func TestStaffAISpendConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	var reply map[string]any
	var asked []string
	f := &fakePlatform{payoutSt: "REQUESTED"}
	f.override = func(w http.ResponseWriter, r *http.Request, _ map[string]any) bool {
		if r.URL.Path != "/v1/ai/usage/today" {
			return false
		}
		asked = append(asked, r.Header.Get("Authorization"))
		writeJ(w, 200, reply)
		return true
	}
	raw := staffBFFWith(t, f, func(d app.StaffDeps, staff *httpapi.StaffServices) {
		ai := httptest.NewServer(f)
		t.Cleanup(ai.Close)
		d.Up.AI = upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: "ai-gateway", BaseURL: ai.URL, Timeout: 3 * time.Second,
			Metrics: httpclient.NewMetrics(prometheus.NewRegistry()), Transport: http.DefaultTransport}))
		staff.AISpend = app.NewStaffAISpendService(d)
	})
	do := func(token string, want int) map[string]any {
		t.Helper()
		rec := raw(token, "GET", "/v1/staff/ai/spend-today", nil)
		if rec.Code != want {
			t.Fatalf("as %q = %d, want %d: %s", token, rec.Code, want, rec.Body.String())
		}
		if want != 200 {
			return nil
		}
		if cc := rec.Header().Get("Cache-Control"); !strings.Contains(cc, "no-store") {
			t.Errorf("Cache-Control = %q", cc)
		}
		req := httptest.NewRequestWithContext(context.Background(), "GET", "http://bff.internal/v1/staff/ai/spend-today", nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatal(err)
		}
		in := &openapi3filter.ResponseValidationInput{RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
			Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}}, Status: rec.Code, Header: rec.Header(),
			Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())), Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true}}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("does not match the contract: %v\n%s", err, rec.Body.String())
		}
		return jsonOf(t, rec)
	}

	reply = map[string]any{"day": "2026-11-15", "capMicros": 2000000, "callerCapMicros": 500000, "spentMicros": 1400000, "calls": 15, "tokens": 11000, "hasData": true,
		"resetsAt": "2026-11-16T00:00:00Z", "byFeature": []any{
			map[string]any{"feature": "lola", "calls": 12, "tokens": 9000, "costMicros": 1250000},
			map[string]any{"feature": "listing-writer", "calls": 3, "tokens": 2000, "costMicros": 150000}}}
	got := do("admin", 200)
	feats := got["byFeature"].([]any)
	if got["spentMicros"].(float64) != 1400000 || got["capMicros"].(float64) != 2000000 || got["hasData"] != true || got["capSetBy"] != nil ||
		len(feats) != 2 || feats[1].(map[string]any)["label"] != "Listing writer" {
		t.Fatalf("spend = %v", got)
	}
	if len(asked) != 1 || asked[0] != "Bearer admin" {
		t.Fatalf("ai-gateway should see the staff member's own token: %v", asked)
	}

	// No usage today: zero with no data, and the BFF adds no number of its own.
	reply = map[string]any{"day": "2026-11-15", "capMicros": 2000000, "callerCapMicros": 500000, "spentMicros": 0, "calls": 0, "tokens": 0, "hasData": false,
		"resetsAt": "2026-11-16T00:00:00Z", "byFeature": []any{}}
	none := do("admin", 200)
	if none["spentMicros"].(float64) != 0 || none["hasData"] != false || len(none["byFeature"].([]any)) != 0 {
		t.Fatalf("empty day = %v", none)
	}

	// Who may: ai:manage only (customer support and the auditor do not hold it), and a guest is signed out.
	do("support", 403)
	do("auditor", 403)
	do("", 401)
	if len(asked) != 2 {
		t.Fatalf("a refused request reached ai-gateway: %v", asked)
	}
}
