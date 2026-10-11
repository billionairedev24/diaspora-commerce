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

// marketsStaff: Super Admin (markets FULL + content:markets.edit) and Operations (markets VIEW).
var marketsStaff = map[string][]any{
	"admin": {[]string{"platform.super_admin"}, []string{"content:markets.view", "content:markets.edit", "delivery-runs:read"}},
	"ops":   {[]string{"platform.operations_manager"}, []string{"content:markets.view", "delivery-runs:read"}},
}

// fakeMarkets serves content's draft markets, delivery-runs' coverage (what depends on a place) and geo's gazetteer.
type fakeMarkets struct {
	mu      sync.Mutex
	version int
	cities  []map[string]any
	calls   []string
	ifMatch map[string]string
	bodies  map[string]map[string]any
}

func (f *fakeMarkets) country() map[string]any {
	cities := make([]any, 0, len(f.cities))
	for _, c := range f.cities {
		cities = append(cities, c)
	}
	return map[string]any{"code": "US", "name": "United States", "status": "LIVE", "currency": "USD", "version": 2, "liveRegions": 1,
		"plannedRegions": 1, "regions": []any{
			map[string]any{"code": "TX", "name": "Texas", "kind": "STATE", "status": "LIVE", "everyCity": true, "version": f.version,
				"cityCount": len(cities), "liveCities": 1, "capabilities": map[string]any{"marketplace": true}, "cities": cities},
			map[string]any{"code": "OH", "name": "Ohio", "kind": "STATE", "status": "PLANNED", "everyCity": false, "version": 0,
				"cityCount": 0, "liveCities": 0, "capabilities": map[string]any{"marketplace": false}, "cities": []any{}},
		}}
}

func (f *fakeMarkets) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.calls = append(f.calls, r.Method+" "+p+"?"+r.URL.RawQuery)
	f.ifMatch[r.Method+" "+p] = r.Header.Get("If-Match")
	body := map[string]any{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	f.bodies[r.Method+" "+p] = body
	ifMatchOK := func() bool {
		im := strings.Trim(r.Header.Get("If-Match"), `"`)
		return im == "" || im == jsonNumber(f.version)
	}
	switch {
	case p == "/v1/me":
		who, ok := marketsStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": who[0], "permissions": who[1]})
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case p == "/v1/markets/draft/countries":
		writeJ(w, 200, []any{map[string]any{"code": "US", "name": "United States", "status": "LIVE", "regionCount": 51, "liveRegions": 14,
			"plannedRegions": 1, "cityCount": len(f.cities), "liveCities": 1, "version": 2}})
	case p == "/v1/markets/draft/changes":
		writeJ(w, 200, map[string]any{"pending": true, "changedCountries": []string{"US"}, "newlyLive": []string{}, "disabledRegions": []string{},
			"enabledRegions": []string{}})
	case p == "/v1/markets/draft/countries/US" && r.Method == http.MethodGet:
		writeJ(w, 200, f.country())
	case p == "/v1/markets/draft/countries/US" && r.Method == http.MethodPatch:
		if strings.Trim(r.Header.Get("If-Match"), `"`) != "2" {
			problemJ(w, 412, "content.version-mismatch")
			return
		}
		writeJ(w, 200, f.country())
	case p == "/v1/markets/draft/countries/US/regions":
		writeJ(w, 201, f.country())
	case p == "/v1/markets/draft/countries/US/regions/TX":
		if !ifMatchOK() {
			problemJ(w, 412, "content.version-mismatch")
			return
		}
		f.version++
		writeJ(w, 200, f.country())
	case p == "/v1/markets/draft/countries/US/regions/TX/cities":
		if !ifMatchOK() {
			problemJ(w, 412, "content.version-mismatch")
			return
		}
		switch r.Method {
		case http.MethodPost:
			raw, _ := body["cities"].([]any)
			for _, c := range raw {
				m, _ := c.(map[string]any)
				f.cities = append(f.cities, map[string]any{"name": m["name"], "status": m["status"]})
			}
		case http.MethodDelete:
			name := r.URL.Query().Get("name")
			kept := f.cities[:0]
			for _, c := range f.cities {
				if c["name"] != name {
					kept = append(kept, c)
				}
			}
			f.cities = kept
		}
		f.version++
		writeJ(w, 200, f.country())
	case p == "/v1/markets/publications":
		writeJ(w, 201, map[string]any{"version": 9, "changes": map[string]any{"pending": true, "changedCountries": []string{"US"}}})
	// delivery-runs: open pre-orders per state, then per city of a state that has some
	case p == "/v1/preorder-areas":
		writeJ(w, 200, map[string]any{"level": "REGION", "rows": []any{map[string]any{"key": "US/TX", "name": "Texas", "windows": 2}}})
	case p == "/v1/preorder-areas/regions/US/TX":
		writeJ(w, 200, map[string]any{"cities": []any{map[string]any{"name": "Lubbock", "on": true, "windows": []string{"LBK-7"}}}})
	// geo
	case p == "/v1/places/countries":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"code": "US", "name": "United States", "regionLabel": "State"}}})
	case p == "/v1/places/countries/US/regions":
		writeJ(w, 200, map[string]any{"country": "US", "countryName": "United States", "regionLabel": "State",
			"items": []any{map[string]any{"code": "OH", "name": "Ohio", "cityCount": 1200}}})
	case p == "/v1/places/countries/US/regions/TX/cities":
		writeJ(w, 200, map[string]any{"country": "US", "region": "TX", "regionName": "Texas", "items": []string{"Katy", "Kyle"}, "total": 2})
	default:
		problemJ(w, 404, "not-found")
	}
}

func jsonNumber(n int) string {
	b, _ := json.Marshal(n)
	return string(b)
}

func marketsBFF(t *testing.T) (*fakeMarkets, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeMarkets{version: 3, ifMatch: map[string]string{}, bodies: map[string]map[string]any{},
		cities: []map[string]any{{"name": "Houston", "status": "LIVE"}, {"name": "Lubbock", "status": "PLANNED"}}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Content: up("content"), DeliveryRuns: up("delivery-runs"), Geo: up("geo")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Markets: app.NewStaffMarketsService(d)}
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

// TestStaffMarketsTree drives the location tree through the BFF: reads with what depends on each place, inline edits
// with If-Match (412 relayed as VERSION_CONFLICT), the removal guard for cities an open pre-order sells to, the
// shared place pickers and the module's access — every answer checked against gateway-bff-staff.yaml.
func TestStaffMarketsTree(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := marketsBFF(t)
	do := func(token, method, path string, body any, want int, headers ...string) map[string]any {
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
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
		}
		return jsonOf(t, rec)
	}
	region := func(country map[string]any, code string) map[string]any {
		for _, r := range country["regions"].([]any) {
			if m := r.(map[string]any); m["code"] == code {
				return m
			}
		}
		t.Fatalf("no region %s", code)
		return nil
	}

	list := do("admin", "GET", "/v1/staff/markets/countries", nil, 200)
	if list["canEdit"] != true || len(list["items"].([]any)) != 1 || list["changes"].(map[string]any)["pending"] != true {
		t.Fatalf("countries = %v", list)
	}
	us := do("admin", "GET", "/v1/staff/markets/countries/US", nil, 200)
	tx := region(us, "TX")
	if tx["preorders"] != float64(2) {
		t.Errorf("Texas preorders = %v", tx["preorders"])
	}
	for _, c := range tx["cities"].([]any) {
		if m := c.(map[string]any); m["name"] == "Lubbock" && len(m["preorders"].([]any)) != 1 {
			t.Errorf("Lubbock preorders = %v", m["preorders"])
		}
	}

	// Inline edits relay If-Match; a stale one is 412 VERSION_CONFLICT.
	added := do("admin", "POST", "/v1/staff/markets/countries/US/regions/TX/cities",
		map[string]any{"cities": []any{map[string]any{"name": "Katy"}, map[string]any{"name": " Kyle ", "status": "PLANNED"}}}, 200, "If-Match", `"3"`)
	if got := len(region(added, "TX")["cities"].([]any)); got != 4 {
		t.Errorf("after append, %d cities, want 4 (appended, not replaced)", got)
	}
	if f.ifMatch["POST /v1/markets/draft/countries/US/regions/TX/cities"] != `"3"` {
		t.Errorf("If-Match relayed = %q", f.ifMatch["POST /v1/markets/draft/countries/US/regions/TX/cities"])
	}
	if b := f.bodies["POST /v1/markets/draft/countries/US/regions/TX/cities"]; mustJSON(t, b) != `{"cities":[{"name":"Katy","status":"PLANNED"},{"name":"Kyle","status":"PLANNED"}]}` {
		t.Errorf("content body = %s", mustJSON(t, b))
	}
	stale := raw("admin", "POST", "/v1/staff/markets/countries/US/regions/TX/cities", map[string]any{"cities": []any{map[string]any{"name": "Waco"}}}, "If-Match", `"3"`)
	if stale.Code != 412 || jsonOf(t, stale)["code"] != "VERSION_CONFLICT" {
		t.Fatalf("stale add = %d %s", stale.Code, stale.Body.String())
	}
	do("admin", "PATCH", "/v1/staff/markets/countries/US/regions/TX/cities", map[string]any{"names": []string{"Katy"}, "status": "LIVE"}, 200, "If-Match", `"4"`)
	do("admin", "PATCH", "/v1/staff/markets/countries/US/regions/TX", map[string]any{"status": "LIVE", "everyCity": true}, 200, "If-Match", `"5"`)
	do("admin", "PATCH", "/v1/staff/markets/countries/US", map[string]any{"status": "LIVE"}, 200, "If-Match", `"2"`)
	do("admin", "POST", "/v1/staff/markets/countries/US/regions", map[string]any{"code": "oh", "name": "Ohio"}, 201)
	if b := f.bodies["POST /v1/markets/draft/countries/US/regions"]; b["code"] != "OH" {
		t.Errorf("region code sent = %v", b["code"])
	}

	// Removing a city an open pre-order sells to is refused before content is called; others go through.
	inUse := raw("admin", "DELETE", "/v1/staff/markets/countries/US/regions/TX/cities/Lubbock", nil, "If-Match", `"6"`)
	if inUse.Code != 409 || !strings.Contains(inUse.Body.String(), "LBK-7") {
		t.Fatalf("remove Lubbock = %d %s", inUse.Code, inUse.Body.String())
	}
	removed := do("admin", "DELETE", "/v1/staff/markets/countries/US/regions/TX/cities/Kyle", nil, 200, "If-Match", `"6"`)
	for _, c := range region(removed, "TX")["cities"].([]any) {
		if c.(map[string]any)["name"] == "Kyle" {
			t.Error("Kyle still listed")
		}
	}
	do("admin", "POST", "/v1/staff/markets/publications", map[string]any{"note": "Katy live"}, 201)

	// Validation happens in the BFF.
	for _, bad := range []struct {
		method, path string
		body         any
	}{
		{"POST", "/v1/staff/markets/countries/US/regions/TX/cities", map[string]any{"cities": []any{map[string]any{"name": "A/B"}}}},
		{"PATCH", "/v1/staff/markets/countries/US/regions/TX/cities", map[string]any{"names": []string{"Katy"}, "status": "ON"}},
		{"PATCH", "/v1/staff/markets/countries/US/regions/TX", map[string]any{"status": "MAYBE"}},
		{"POST", "/v1/staff/markets/countries/US/regions", map[string]any{"code": "", "name": "Nowhere"}},
	} {
		do("admin", bad.method, bad.path, bad.body, 422)
	}

	// Place pickers: countries, states, cities (geo).
	if got := do("ops", "GET", "/v1/staff/places", nil, 200); got["label"] != "Country" {
		t.Errorf("countries label = %v", got["label"])
	}
	if got := do("ops", "GET", "/v1/staff/places?country=US", nil, 200); got["label"] != "State" || len(got["items"].([]any)) != 1 {
		t.Errorf("states = %v", got)
	}
	cities := do("ops", "GET", "/v1/staff/places?country=US&region=TX&q=K", nil, 200)
	if cities["total"] != float64(2) {
		t.Errorf("cities = %v", cities)
	}
	if !strings.Contains(strings.Join(f.calls, "\n"), "GET /v1/places/countries/US/regions/TX/cities?limit=200&q=K") {
		t.Errorf("geo city call missing: %v", f.calls)
	}

	// Operations can view, not edit; customers and guests are refused.
	if got := do("ops", "GET", "/v1/staff/markets/countries", nil, 200); got["canEdit"] != false {
		t.Errorf("ops canEdit = %v", got["canEdit"])
	}
	do("ops", "POST", "/v1/staff/markets/countries/US/regions/TX/cities", map[string]any{"cities": []any{map[string]any{"name": "Waco"}}}, 403)
	do("customer", "GET", "/v1/staff/markets/countries", nil, 403)
	do("", "GET", "/v1/staff/markets/countries", nil, 401)
	do("admin", "GET", "/v1/staff/markets/countries/USA", nil, 404)
}
