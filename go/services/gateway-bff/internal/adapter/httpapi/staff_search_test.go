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

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// searchStaff: Marketing (reports and the rule list only), Catalog Manager (also edits rules) and Support (no search access).
var searchStaff = map[string][]any{
	"viewer": {[]string{"platform.marketing"}, []string{"search:analytics.view"}},
	"editor": {[]string{"platform.catalog_manager"}, []string{"search:analytics.view", "search:synonyms.edit"}},
	"nobody": {[]string{"platform.customer_support"}, []string{"support:respond", "users:read"}},
}

// fakeSearch serves identity and the search service's analytics and synonym routes.
type fakeSearch struct {
	mu    sync.Mutex
	calls []string
	rules map[string]string
	puts  map[string]string
	zero  bool
}

func (f *fakeSearch) queryStats() []any {
	row := func(q string, searches, zero, clicks int) map[string]any {
		return map[string]any{"query": q, "searches": searches, "zeroResults": zero, "clicks": clicks, "ctr": float64(clicks) / float64(searches)}
	}
	return []any{row("shea butter", 1240, 0, 610), row("kente", 830, 12, 402), row("gari", 700, 0, 301), row("agbada kids", 96, 96, 0),
		row("suya spise", 41, 41, 0), row("egusi", 380, 190, 88)}
}

func (f *fakeSearch) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.calls = append(f.calls, r.Method+" "+p+"?"+r.URL.RawQuery)
	switch {
	case p == "/v1/me":
		who, ok := searchStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": who[0], "permissions": who[1]})
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case p == "/v1/search/analytics/top-queries" || p == "/v1/search/analytics/zero-results":
		if r.URL.Query().Get("market") != "us" {
			problemJ(w, 400, "search.unknown-market")
			return
		}
		items := f.queryStats()
		if f.zero {
			items = items[:0]
		}
		writeJ(w, 200, map[string]any{"market": "us", "from": firstOr(r.URL.Query().Get("from"), "2026-10-28"), "to": firstOr(r.URL.Query().Get("to"), "2026-11-03"), "items": items})
	case p == "/v1/search/analytics/ctr":
		days := []any{}
		for i := range 5 {
			s := 100 + i*10
			days = append(days, map[string]any{"day": time.Date(2026, 10, 28+i, 0, 0, 0, 0, time.UTC).Format(time.DateOnly), "searches": s, "clicks": s / 2, "zeroResults": i, "ctr": 0.5, "zeroResultRate": float64(i) / float64(s)})
		}
		writeJ(w, 200, map[string]any{"market": "us", "from": "2026-10-28", "to": "2026-11-01", "days": days,
			"total": map[string]any{"day": "total", "searches": 600, "clicks": 300, "zeroResults": 10, "ctr": 0.5, "zeroResultRate": 10.0 / 600}})
	case p == "/v1/search/synonyms" && r.Method == http.MethodGet:
		rules := []any{}
		for id, syn := range f.rules {
			rules = append(rules, map[string]any{"id": id, "synonyms": syn})
		}
		writeJ(w, 200, map[string]any{"rules": rules})
	case strings.HasPrefix(p, "/v1/search/synonyms/") && r.Method == http.MethodPut:
		id := strings.TrimPrefix(p, "/v1/search/synonyms/")
		var body struct {
			Synonyms string `json:"synonyms"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if strings.Contains(body.Synonyms, "badword") {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(422)
			_ = json.NewEncoder(w).Encode(map[string]any{"title": "Unprocessable", "status": 422, "code": "search.invalid-synonym", "detail": "invalid synonym rule\nterms are at most 60 characters"})
			return
		}
		f.rules[id], f.puts[id] = body.Synonyms, body.Synonyms
		writeJ(w, 200, map[string]any{"id": id, "synonyms": body.Synonyms})
	case strings.HasPrefix(p, "/v1/search/synonyms/") && r.Method == http.MethodDelete:
		id := strings.TrimPrefix(p, "/v1/search/synonyms/")
		if _, ok := f.rules[id]; !ok {
			problemJ(w, 404, "search.synonym-not-found")
			return
		}
		delete(f.rules, id)
		w.WriteHeader(204)
	default:
		problemJ(w, 404, "not-found")
	}
}

func firstOr(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

func (f *fakeSearch) called(sub string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if strings.Contains(c, sub) {
			return true
		}
	}
	return false
}

func searchBFF(t *testing.T, f *fakeSearch) func(token, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Search: up("search")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Search: app.NewStaffSearchService(d, []string{"us"})}
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

// TestStaffSearchAnalytics drives the reports and synonym rules through the BFF: filtering, sorting and paging of the
// whole-window reports, the permission split between viewing and editing, search's validation as a field error, and
// every answer checked against gateway-bff-staff.yaml.
func TestStaffSearchAnalytics(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeSearch{rules: map[string]string{"gari": "gari, garri, cassava flakes"}, puts: map[string]string{}}
	raw := searchBFF(t, f)
	do := func(token, method, path string, body any, want int) map[string]any {
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
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
		}
		if rec.Body.Len() == 0 {
			return nil
		}
		return jsonOf(t, rec)
	}
	queries := func(m map[string]any) []string {
		out := []string{}
		for _, it := range m["items"].([]any) {
			out = append(out, it.(map[string]any)["query"].(string))
		}
		return out
	}
	same := func(got, want []string) bool { return strings.Join(got, "|") == strings.Join(want, "|") }

	t.Run("markets name the market and say who may edit", func(t *testing.T) {
		m := do("viewer", "GET", "/v1/staff/search/markets", nil, 200)
		first := m["items"].([]any)[0].(map[string]any)
		if first["code"] != "us" || first["name"] != "United States" || m["canEditSynonyms"] != false {
			t.Fatalf("markets = %v", m)
		}
		if do("editor", "GET", "/v1/staff/search/markets", nil, 200)["canEditSynonyms"] != true {
			t.Error("the editor should be able to edit synonyms")
		}
	})

	t.Run("top queries are sorted, filtered and paged by the BFF", func(t *testing.T) {
		m := do("viewer", "GET", "/v1/staff/search/analytics/top-queries?market=us&from=2026-10-28&to=2026-11-03", nil, 200)
		if !same(queries(m), []string{"shea butter", "kente", "gari", "egusi", "agbada kids", "suya spise"}) {
			t.Errorf("default order = %v", queries(m))
		}
		if m["marketName"] != "United States" || m["total"] != float64(6) || m["truncated"] != false {
			t.Errorf("report = %v", m)
		}
		if tot := m["totals"].(map[string]any); tot["searches"] != float64(3287) || tot["zeroResults"] != float64(339) {
			t.Errorf("totals = %v", tot)
		}
		if !f.called("limit=500") || !f.called("from=2026-10-28") {
			t.Errorf("upstream calls = %v", f.calls)
		}
		rate := do("viewer", "GET", "/v1/staff/search/analytics/top-queries?sort=zeroResultRate,desc&size=2", nil, 200)
		if !same(queries(rate), []string{"agbada kids", "suya spise"}) {
			t.Errorf("by zero-result rate = %v", queries(rate))
		}
		if rate["items"].([]any)[0].(map[string]any)["zeroResultRate"] != float64(1) {
			t.Errorf("zero-result rate = %v", rate["items"])
		}
		p2 := do("viewer", "GET", "/v1/staff/search/analytics/top-queries?sort=query,asc&size=4&page=2", nil, 200)
		if !same(queries(p2), []string{"shea butter", "suya spise"}) || p2["page"] != float64(2) {
			t.Errorf("page 2 = %v", queries(p2))
		}
		q := do("viewer", "GET", "/v1/staff/search/analytics/top-queries?q=GA", nil, 200)
		if !same(queries(q), []string{"gari"}) || q["total"] != float64(1) {
			t.Errorf("filter = %v", queries(q))
		}
	})

	t.Run("no results lists only queries that found nothing, most first", func(t *testing.T) {
		m := do("viewer", "GET", "/v1/staff/search/analytics/zero-results", nil, 200)
		if !same(queries(m), []string{"egusi", "agbada kids", "suya spise", "kente"}) {
			t.Errorf("zero results = %v", queries(m))
		}
	})

	t.Run("click-through pages days and keeps the whole trend", func(t *testing.T) {
		m := do("viewer", "GET", "/v1/staff/search/analytics/ctr?size=2", nil, 200)
		days := m["items"].([]any)
		if len(days) != 2 || days[0].(map[string]any)["day"] != "2026-11-01" || m["total"] != float64(5) {
			t.Errorf("days = %v", m["items"])
		}
		trend := m["trend"].([]any)
		if len(trend) != 5 || trend[0].(map[string]any)["day"] != "2026-10-28" {
			t.Errorf("trend = %v", trend)
		}
		oct31 := do("viewer", "GET", "/v1/staff/search/analytics/ctr?q=10-31", nil, 200)
		if oct31["total"] != float64(1) || len(oct31["trend"].([]any)) != 5 {
			t.Errorf("day filter = %v", oct31)
		}
		asc := do("viewer", "GET", "/v1/staff/search/analytics/ctr?sort=searches,asc&size=1", nil, 200)
		if asc["items"].([]any)[0].(map[string]any)["searches"] != float64(100) {
			t.Errorf("ascending = %v", asc["items"])
		}
	})

	t.Run("bad market and dates are field errors before search is called", func(t *testing.T) {
		before := len(f.calls)
		for path, field := range map[string]string{
			"/v1/staff/search/analytics/top-queries?market=zz":                     "market",
			"/v1/staff/search/analytics/top-queries?from=yesterday":                "from",
			"/v1/staff/search/analytics/top-queries?from=2026-11-03&to=2026-10-01": "from",
			"/v1/staff/search/analytics/ctr?from=2024-01-01&to=2026-01-01":         "to",
		} {
			m := do("viewer", "GET", path, nil, 422)
			if _, ok := m["errors"].(map[string]any)[field]; !ok {
				t.Errorf("%s: errors = %v, want %s", path, m["errors"], field)
			}
		}
		for _, c := range f.calls[before:] {
			if strings.Contains(c, "/v1/search/analytics/") {
				t.Errorf("search was called with invalid input: %s", c)
			}
		}
	})

	t.Run("rules are listed with their words", func(t *testing.T) {
		m := do("viewer", "GET", "/v1/staff/search/synonyms", nil, 200)
		rule := m["items"].([]any)[0].(map[string]any)
		if m["canEdit"] != false || rule["id"] != "gari" || len(rule["terms"].([]any)) != 3 {
			t.Errorf("rules = %v", m)
		}
	})

	t.Run("adding and deleting rules needs search:synonyms.edit", func(t *testing.T) {
		do("viewer", "POST", "/v1/staff/search/synonyms", map[string]any{"terms": []string{"agbada kids", "grand boubou kids"}}, 403)
		do("viewer", "DELETE", "/v1/staff/search/synonyms/gari", nil, 403)
		if f.called("PUT /v1/search/synonyms") {
			t.Fatal("a viewer must not reach search's PUT")
		}
		rule := do("editor", "POST", "/v1/staff/search/synonyms", map[string]any{"terms": []string{" agbada  kids ", "grand boubou kids"}}, 201)
		if rule["id"] != "agbada-kids" || len(rule["terms"].([]any)) != 2 || f.puts["agbada-kids"] != "agbada kids, grand boubou kids" {
			t.Errorf("rule = %v puts = %v", rule, f.puts)
		}
		again := do("editor", "POST", "/v1/staff/search/synonyms", map[string]any{"terms": []string{"agbada kids", "dashiki kids"}}, 201)
		if again["id"] != "agbada-kids-2" {
			t.Errorf("second rule id = %v", again["id"])
		}
		do("editor", "DELETE", "/v1/staff/search/synonyms/agbada-kids-2", nil, 204)
		do("editor", "DELETE", "/v1/staff/search/synonyms/agbada-kids-2", nil, 404)
		do("editor", "DELETE", "/v1/staff/search/synonyms/Not%20A%20Rule", nil, 404)
	})

	t.Run("words are validated here and by search, as errors on terms", func(t *testing.T) {
		for _, terms := range [][]string{{"agbada"}, {"agbada", "AGBADA"}, {"a, b", "c"}, {strings.Repeat("x", 61), "y"}} {
			m := do("editor", "POST", "/v1/staff/search/synonyms", map[string]any{"terms": terms}, 422)
			if _, ok := m["errors"].(map[string]any)["terms"]; !ok {
				t.Errorf("%v: errors = %v", terms, m["errors"])
			}
		}
		m := do("editor", "POST", "/v1/staff/search/synonyms", map[string]any{"terms": []string{"agbada", "badword"}}, 422)
		if msg := m["errors"].(map[string]any)["terms"]; msg != "Terms are at most 60 characters" {
			t.Errorf("search's explanation = %v", msg)
		}
	})

	t.Run("access", func(t *testing.T) {
		do("nobody", "GET", "/v1/staff/search/analytics/top-queries", nil, 403)
		do("nobody", "GET", "/v1/staff/search/synonyms", nil, 403)
		do("nobody", "GET", "/v1/staff/search/markets", nil, 403)
		do("", "GET", "/v1/staff/search/markets", nil, 401)
		if rec := raw("cust", "GET", "/v1/staff/search/markets", nil); rec.Code != 403 {
			t.Errorf("customer = %d", rec.Code)
		}
	})

	t.Run("the sidebar shows Search analytics only with a search permission", func(t *testing.T) {
		has := func(token string) bool {
			rec := raw(token, "GET", "/v1/staff/session", nil)
			return rec.Code == 200 && strings.Contains(rec.Body.String(), `"key":"search"`)
		}
		if !has("viewer") || !has("editor") {
			t.Error("viewer and editor should see Search analytics")
		}
		if has("nobody") {
			t.Error("support should not see Search analytics")
		}
	})
}
