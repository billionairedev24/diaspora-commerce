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

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var homeStaff = map[string]map[string]any{
	"marketing": {"id": "stf-30", "firstName": "Bisi", "lastName": "Ade", "email": "bisi@afrotransact.com", "principalType": "STAFF",
		"roles": []string{"platform.marketing"}, "permissions": []string{"content:home.view", "content:home.edit"}},
	"finance": {"id": "stf-31", "firstName": "Efe", "lastName": "Oko", "email": "efe@afrotransact.com", "principalType": "STAFF",
		"roles": []string{"platform.finance"}, "permissions": []string{}},
}

// fakeHomeContent is content's home settings and registry plus the identity calls staff routes make.
type fakeHomeContent struct {
	*fakePlatform
	mu       sync.Mutex
	version  int
	puts     []map[string]any
	registry map[string]map[string]any
}

func (f *fakeHomeContent) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if p == "/v1/me" {
		if me, ok := homeStaff[token]; ok {
			writeJ(w, 200, me)
			return
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	var body map[string]any
	if r.Body != nil {
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &body)
	}
	switch {
	case p == "/v1/home-settings" && r.Method == http.MethodGet:
		s := domain.DefaultHomeSettings()
		s.Version = f.version
		writeJ(w, 200, s)
	case p == "/v1/home-settings" && r.Method == http.MethodPut:
		if int(body["expectedVersion"].(float64)) != f.version {
			problemDetail(w, 409, "content.stale-version", "Someone else saved first")
			return
		}
		f.version++
		f.puts = append(f.puts, body)
		s := domain.DefaultHomeSettings()
		s.Version, s.UpdatedBy = f.version, "stf-30"
		writeJ(w, 200, s)
	case p == "/v1/home-settings/versions":
		writeJ(w, 200, []any{map[string]any{"version": 1, "reason": "Launch the new home", "changedBy": "stf-30", "changedAt": "2026-11-13T10:00:00Z", "changes": []any{}}})
	case p == "/v1/community-registry" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{})
	case strings.HasPrefix(p, "/v1/community-registry/") && r.Method == http.MethodPut:
		f.puts = append(f.puts, body)
		writeJ(w, 200, map[string]any{"key": strings.TrimPrefix(p, "/v1/community-registry/"), "labels": body["labels"], "aliases": []any{}, "updatedAt": "2026-11-13T10:00:00Z"})
	default:
		f.fakePlatform.ServeHTTP(w, r)
	}
}

// siteStub answers the public home routes and records what the handler passed.
type noLocation struct{ app.LocationService }

func (noLocation) Detect(context.Context, *domain.Caller, domain.EdgeHints) {}

type siteStub struct {
	app.SiteService
	mu  sync.Mutex
	req app.HomeRequest
}

func (s *siteStub) HomeFor(_ context.Context, req app.HomeRequest) (domain.Home, error) {
	s.mu.Lock()
	s.req = req
	s.mu.Unlock()
	return domain.Home{Version: 2, Blocks: []domain.HomeBlock{}, Hero: []domain.HeroSlide{}, Tiles: []domain.Tile{}, Announcements: []domain.Announcement{}}, nil
}

func (s *siteStub) Community(context.Context, string, string) (domain.SiteCommunity, bool, error) {
	return domain.SiteCommunity{}, false, port.ErrNotFound
}

func (s *siteStub) RecordModuleEvent(ctx context.Context, ev domain.HomeModuleEvent) error {
	if ev.Module != "deals" {
		return domain.ValidationProblem(map[string]string{"module": "Unknown module"})
	}
	return nil
}

func (s *siteStub) PreviewHome(context.Context, app.HomeRequest) (domain.Home, []domain.ModuleDecision, error) {
	return domain.Home{Version: 2, Locale: "en", Blocks: []domain.HomeBlock{}, Debug: &domain.HomeDebug{Seed: "00aa"}}, []domain.ModuleDecision{}, nil
}

func homeBFF(t *testing.T) (*fakeHomeContent, *siteStub, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeHomeContent{fakePlatform: &fakePlatform{}, registry: map[string]map[string]any{}}
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
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Content: up("content")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Jobs:   exportfiles.NewMemoryJobs(), Blobs: files, Renderer: exportfiles.NewRenderer(), Log: log, Background: func(fn func()) { fn() }}
	site := &siteStub{}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Home: app.NewStaffHomeService(d, site, "prod")}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Site: site, Location: noLocation{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	return f, site, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder {
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

func TestHomeCachingAndVisitHeaders(t *testing.T) {
	_, site, do := homeBFF(t)
	rec := do("", "GET", "/v1/home", nil, "X-Visitor-Id", "v-1", "X-Visit-Window", "w-1", "X-Home-Variant", "b", "X-Home-Seen", "aaaaaaaa,bbbbbbbb", "X-Home-Exclude", "AT00000001,AT00000002")
	if rec.Code != 200 || rec.Header().Get("Cache-Control") != "private, no-store" {
		t.Fatalf("a visitor's page is never cached: %d %q", rec.Code, rec.Header().Get("Cache-Control"))
	}
	r := site.req
	if r.Visitor != "v-1" || r.Window != "w-1" || r.Variant != "b" || len(r.Seen) != 2 || len(r.Exclude) != 2 || r.Crawler {
		t.Errorf("headers not passed: %+v", r)
	}
	rec = do("", "GET", "/v1/home", nil, "X-Crawler", "1")
	if cc := rec.Header().Get("Cache-Control"); cc != "public, max-age=60, stale-while-revalidate=300" || !strings.Contains(rec.Header().Get("Vary"), "X-Crawler") || !site.req.Crawler {
		t.Errorf("crawler data is cached 60 s with Vary: %q %q", cc, rec.Header().Get("Vary"))
	}
	rec = do("", "GET", "/v1/home", nil)
	_ = rec
	do("", "GET", "/v1/home", nil, "User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
	if !site.req.Crawler {
		t.Error("a known bot user agent is a crawler")
	}
}

func TestDebugAndSeedAreStaffOnly(t *testing.T) {
	_, site, do := homeBFF(t)
	do("", "GET", "/v1/home?debug=1&seed=00aa00aa00aa00aa", nil)
	if site.req.Debug || site.req.Seed != "" {
		t.Error("debug and seed are ignored for a shopper (no error, no field)")
	}
	do("finance", "GET", "/v1/home?debug=1", nil)
	if site.req.Debug {
		t.Error("staff without Marketing get no debug")
	}
	rec := do("marketing", "GET", "/v1/home?debug=1&seed=00aa00aa00aa00aa", nil)
	if !site.req.Debug || site.req.Seed != "00aa00aa00aa00aa" || rec.Header().Get("Cache-Control") != "private, no-store" {
		t.Errorf("marketing staff can debug and replay: %+v", site.req)
	}
}

func TestHomeEventsAndCommunity404(t *testing.T) {
	_, _, do := homeBFF(t)
	if rec := do("", "POST", "/v1/home/events", map[string]any{"module": "deals", "event": "impression", "position": 2}); rec.Code != 202 {
		t.Errorf("events: %d %s", rec.Code, rec.Body)
	}
	if rec := do("", "POST", "/v1/home/events", map[string]any{"module": "AT0000123", "event": "click"}); rec.Code != 422 {
		t.Errorf("an unknown module key is rejected: %d", rec.Code)
	}
	rec := do("", "GET", "/v1/site/communities/nigerian", nil)
	if rec.Code != 404 || !strings.Contains(rec.Body.String(), "community.not-visible") {
		t.Errorf("a community below the thresholds is a 404 problem, not a zero-result page: %d %s", rec.Code, rec.Body)
	}
}

func TestStaffHomeSettingsPermissionsReasonValidationAndAudit(t *testing.T) {
	f, _, do := homeBFF(t)
	if rec := do("finance", "GET", "/v1/staff/home/settings", nil); rec.Code != 403 {
		t.Errorf("finance cannot open Marketing: %d", rec.Code)
	}
	rec := do("marketing", "GET", "/v1/staff/home/settings", nil)
	if rec.Code != 200 {
		t.Fatalf("settings: %d %s", rec.Code, rec.Body)
	}
	var got struct {
		Version          int               `json:"version"`
		EffectiveDynamic string            `json:"effectiveDynamic"`
		ModuleNames      map[string]string `json:"moduleNames"`
		Modules          map[string]struct {
			UnitMinProducts int `json:"unitMinProducts"`
			MinUnits        int `json:"minUnits"`
		} `json:"modules"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.EffectiveDynamic != "off" || got.ModuleNames["communities"] != "Shop by community" || got.Modules["communities"].MinUnits != 3 || got.Modules["communities"].UnitMinProducts != 8 {
		t.Errorf("prod defaults to off, with module names and the documented thresholds: %+v", got)
	}
	save := func(mut func(m map[string]any)) *httptest.ResponseRecorder {
		body := map[string]any{"expectedVersion": 0, "dynamic": nil, "modules": domain.DefaultHomeRules(), "caps": domain.DefaultHomeCaps(), "departmentLabels": map[string]any{}, "reason": "Launch the new home"}
		mut(body)
		return do("marketing", "PUT", "/v1/staff/home/settings", body)
	}
	if rec := save(func(m map[string]any) { m["reason"] = "" }); rec.Code != 422 || !strings.Contains(rec.Body.String(), "reason") {
		t.Errorf("a reason is mandatory: %d %s", rec.Code, rec.Body)
	}
	if rec := save(func(m map[string]any) { m["reason"] = "too short" }); rec.Code != 422 || !strings.Contains(rec.Body.String(), "10 to 300 characters") {
		t.Errorf("a nine character reason is refused with the shared copy: %d %s", rec.Code, rec.Body)
	}
	if rec := save(func(m map[string]any) { m["reason"] = "          x          " }); rec.Code != 422 {
		t.Errorf("spaces do not count towards the 10: %d %s", rec.Code, rec.Body)
	}
	if rec := save(func(m map[string]any) {
		mods := domain.DefaultHomeRules()
		d := mods[domain.ModDeals]
		d.MinProducts, d.Weight = -1, 101
		mods[domain.ModDeals] = d
		mods["nonsense"] = domain.ModuleRule{}
		m["modules"] = mods
	}); rec.Code != 422 || !strings.Contains(rec.Body.String(), "modules.deals.weight") || !strings.Contains(rec.Body.String(), "modules.nonsense") {
		t.Errorf("negative, weight and unknown module are rejected with fields: %d %s", rec.Code, rec.Body)
	}
	if len(f.puts) != 0 {
		t.Fatal("invalid saves never reach content")
	}
	if rec := save(func(m map[string]any) { on := "on"; m["dynamic"] = on }); rec.Code != 200 {
		t.Fatalf("valid save: %d %s", rec.Code, rec.Body)
	}
	if len(f.puts) != 1 || f.puts[0]["reason"] != "Launch the new home" {
		t.Errorf("the reason travels to content for the audit log: %v", f.puts)
	}
	if rec := save(func(m map[string]any) {}); rec.Code != 409 {
		t.Errorf("a stale expectedVersion is a 409: %d", rec.Code)
	}
	if rec := do("marketing", "POST", "/v1/staff/home/settings/revert", map[string]any{"toVersion": 1, "reason": ""}); rec.Code != 422 {
		t.Errorf("revert needs a reason: %d", rec.Code)
	}
	if rec := do("marketing", "GET", "/v1/staff/home/settings/versions", nil); rec.Code != 200 {
		t.Errorf("versions: %d", rec.Code)
	}
	if rec := do("marketing", "PUT", "/v1/staff/home/communities/nigerian", map[string]any{"labels": map[string]any{"en": "Nigerian"}, "reason": ""}); rec.Code != 422 {
		t.Errorf("registry edits need a reason: %d", rec.Code)
	}
	if rec := do("marketing", "PUT", "/v1/staff/home/communities/nigerian", map[string]any{"labels": map[string]any{"en": "Nigerian"}, "reason": "Merge spellings"}); rec.Code != 200 {
		t.Errorf("registry save: %d %s", rec.Code, rec.Body)
	}
	if rec := do("marketing", "GET", "/v1/staff/home/preview?zip=7704", nil); rec.Code != 422 {
		t.Errorf("a bad ZIP is a 422: %d", rec.Code)
	}
	if rec := do("marketing", "GET", "/v1/staff/home/preview?zip=77042&catalog=10&asOf=2026-12-01T09:00:00Z", nil); rec.Code != 200 {
		t.Errorf("preview: %d %s", rec.Code, rec.Body)
	}
	if rec := do("finance", "GET", "/v1/staff/home/preview", nil); rec.Code != 403 {
		t.Errorf("preview needs Marketing: %d", rec.Code)
	}
}
