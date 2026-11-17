package host_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/host"
	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/inproc"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
)

type check struct {
	name string
	err  error
}

func (c check) Name() string                { return c.name }
func (c check) Check(context.Context) error { return c.err }

// fakeSpec builds a module from the real platform runtime (httpserver: middleware chain, own Prometheus registry,
// standard endpoints) with a handful of fake routes ("METHOD /path" -> response body) and a matching OpenAPI document.
func fakeSpec(name string, routes map[string]string, mut ...func(*fakeOpts)) host.Spec {
	o := &fakeOpts{name: name, routes: routes}
	for _, m := range mut {
		m(o)
	}
	return host.Spec{Name: name, Default: o.def, Edge: o.edge, Mount: o.mount, PeerVars: o.peerVars, New: o.ctor}
}

type fakeOpts struct {
	name     string
	routes   map[string]string
	def      bool
	edge     bool
	mount    host.Mount
	peerVars []string
	checks   []httpserver.ReadinessCheck
	ctor     module.Constructor
	built    *atomic.Int32
	onDeps   func(module.Deps)
	extra    func(*http.ServeMux)
}

func (o *fakeOpts) build(d module.Deps) (module.Module, error) {
	if o.built != nil {
		o.built.Add(1)
	}
	if o.onDeps != nil {
		o.onDeps(d)
	}
	var spec strings.Builder
	spec.WriteString("openapi: 3.1.0\npaths:\n")
	paths := map[string][]string{}
	for p := range o.routes {
		method, path, _ := strings.Cut(p, " ")
		paths[path] = append(paths[path], strings.ToLower(method))
	}
	keys := make([]string, 0, len(paths))
	for p := range paths {
		keys = append(keys, p)
	}
	sort.Strings(keys)
	for _, p := range keys {
		fmt.Fprintf(&spec, "  %s:\n", p)
		for _, m := range paths[p] {
			fmt.Fprintf(&spec, "    %s: {}\n", m)
		}
	}
	srv := httpserver.New(httpserver.Options{Name: o.name, OpenAPI: []byte(spec.String()), Checks: o.checks,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	for p, body := range o.routes {
		srv.Mux().HandleFunc(p, func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, body) })
	}
	if o.extra != nil {
		o.extra(srv.Mux())
	}
	return module.FromServer(o.name, srv, slog.Default(), "local", module.Telemetry{}, []byte(spec.String())), nil
}

func withDefaults(o *fakeOpts) {
	o.def = true
	o.ctor = o.build
}

func spec(name string, routes map[string]string, mut ...func(*fakeOpts)) host.Spec {
	return fakeSpec(name, routes, append([]func(*fakeOpts){withDefaults}, mut...)...)
}

func env(m map[string]string) func(string) (string, bool) {
	return func(k string) (string, bool) { v, ok := m[k]; return v, ok }
}

func get(t *testing.T, h http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// allModules is the real catalogue's shape: ten modules in build order with the real mount defaults and peer variables,
// each with fake routes taken from the real collisions found in the OpenAPI documents.
func allModules(built *atomic.Int32, bffSeen func(module.Deps)) []host.Spec {
	count := func(o *fakeOpts) { o.built = built }
	return []host.Spec{
		spec("cart", map[string]string{"GET /v1/cart": "cart-internal", "POST /v1/cart/items": "x"}, count,
			func(o *fakeOpts) { o.mount, o.peerVars = host.MountPrefixed, []string{"CART_URL"} }),
		spec("promise", map[string]string{"POST /v1/promises": "promise"}, count,
			func(o *fakeOpts) { o.peerVars = []string{"PROMISE_URL"} }),
		spec("geo", map[string]string{"POST /v1/addresses/validate": "geo-validate"}, count,
			func(o *fakeOpts) { o.peerVars = []string{"GEO_URL"} }),
		spec("search", map[string]string{"GET /v1/search": "search-internal"}, count,
			func(o *fakeOpts) { o.mount, o.peerVars = host.MountPrefixed, []string{"SEARCH_URL"} }),
		spec("indexer", map[string]string{}, count),
		spec("notifications", map[string]string{"POST /v1/webhooks/resend": "resend"}, count,
			func(o *fakeOpts) { o.peerVars = []string{"NOTIFICATIONS_URL"} }),
		spec("media", map[string]string{"GET /v1/assets/{id}": "asset"}, count,
			func(o *fakeOpts) { o.peerVars = []string{"MEDIA_URL"} }),
		spec("carrier-gateway", map[string]string{"POST /v1/addresses/validate": "carrier-validate", "POST /v1/webhooks/{carrier}": "carrier-hook"}, count,
			func(o *fakeOpts) { o.mount, o.peerVars = host.MountPrefixed, []string{"CARRIER_URL"} }),
		spec("authz-sync", map[string]string{}, count),
		spec("gateway-bff", map[string]string{"GET /v1/cart": "bff-cart", "GET /v1/search": "bff-search", "GET /v1/home": "home"}, count,
			func(o *fakeOpts) { o.edge, o.onDeps = true, bffSeen }),
	}
}

func allOn() map[string]string {
	m := map[string]string{}
	for _, n := range []string{"CART", "PROMISE", "GEO", "SEARCH", "INDEXER", "NOTIFICATIONS", "MEDIA", "CARRIER_GATEWAY", "AUTHZ_SYNC", "GATEWAY_BFF"} {
		m["AT_MODULES_"+n+"_ENABLED"] = "true"
	}
	return m
}

func newHost(t *testing.T, vars map[string]string, specs []host.Spec) host.Host {
	t.Helper()
	t.Setenv("MANAGEMENT_ADDR", "")
	h, err := host.New(host.Config{Env: "local", Getenv: env(vars), MetricsPublic: true,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, specs)
	if err != nil {
		t.Fatalf("host.New: %v", err)
	}
	t.Cleanup(func() { _ = h.Close(context.Background()) })
	return h
}

func TestAllModulesBootWithFakes(t *testing.T) {
	var built atomic.Int32
	h := newHost(t, allOn(), allModules(&built, nil))
	if built.Load() != 10 {
		t.Fatalf("built %d modules, want 10", built.Load())
	}
	if err := h.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	handler := h.Handler()

	// Flat routes reach their own module; the edge owns the paths cart and search also document.
	for path, want := range map[string]string{"/v1/cart": "bff-cart", "/v1/search": "bff-search", "/v1/home": "home", "/v1/assets/7": "asset"} {
		if rec := get(t, handler, path); rec.Code != 200 || rec.Body.String() != want {
			t.Errorf("GET %s = %d %q, want %q", path, rec.Code, rec.Body.String(), want)
		}
	}
	// Prefixed modules answer under /modules/<name>/ with their own copy of the route.
	if rec := get(t, handler, "/modules/cart/v1/cart"); rec.Body.String() != "cart-internal" {
		t.Errorf("/modules/cart/v1/cart = %q", rec.Body.String())
	}
	if rec := get(t, handler, "/modules/search/v1/search"); rec.Body.String() != "search-internal" {
		t.Errorf("/modules/search/v1/search = %q", rec.Body.String())
	}
	// Unknown paths fall to the edge module's own 404.
	if rec := get(t, handler, "/nope"); rec.Code != http.StatusNotFound {
		t.Errorf("/nope = %d", rec.Code)
	}
	// The standard pages at the root belong to the edge; every module keeps its own under /modules/<name>/.
	if rec := get(t, handler, "/openapi.yaml"); rec.Code != 200 || !strings.Contains(rec.Body.String(), "/v1/home") {
		t.Errorf("/openapi.yaml should be the edge's: %d %q", rec.Code, rec.Body.String())
	}
	if rec := get(t, handler, "/modules/geo/openapi.yaml"); rec.Code != 200 || !strings.Contains(rec.Body.String(), "/v1/addresses/validate") {
		t.Errorf("/modules/geo/openapi.yaml: %d %q", rec.Code, rec.Body.String())
	}
	// Aggregate and per-module probes name the modules.
	rec := get(t, handler, "/healthz")
	for _, n := range []string{"cart", "promise", "geo", "search", "indexer", "notifications", "media", "carrier-gateway", "authz-sync", "gateway-bff"} {
		if !strings.Contains(rec.Body.String(), `"`+n+`"`) {
			t.Errorf("/healthz does not name %s: %s", n, rec.Body.String())
		}
	}
	if rec := get(t, handler, "/modules/media/readyz"); rec.Code != 200 || !strings.Contains(rec.Body.String(), `"media":"up"`) {
		t.Errorf("/modules/media/readyz: %d %s", rec.Code, rec.Body.String())
	}
	if rec := get(t, handler, "/modules/nope/healthz"); rec.Code != 404 {
		t.Errorf("unknown module = %d", rec.Code)
	}
}

func TestDisabledModulesAreNotBuilt(t *testing.T) {
	var built atomic.Int32
	specs := allModules(&built, nil)
	for i := range specs {
		switch specs[i].Name {
		case "promise", "carrier-gateway", "authz-sync": // the launch defaults
			specs[i].Default = false
		}
	}
	h := newHost(t, nil, specs)
	if built.Load() != 7 {
		t.Fatalf("built %d modules, want 7 (promise, carrier-gateway and authz-sync are off by default)", built.Load())
	}
	if rec := get(t, h.Handler(), "/modules/promise/healthz"); rec.Code != 404 {
		t.Errorf("a disabled module is reachable: %d", rec.Code)
	}
	if rec := get(t, h.Handler(), "/healthz"); strings.Contains(rec.Body.String(), "promise") {
		t.Errorf("a disabled module is listed: %s", rec.Body.String())
	}
	enabled := map[string]bool{}
	for _, m := range h.Modules() {
		enabled[m.Name] = m.Enabled
	}
	if enabled["promise"] || !enabled["cart"] {
		t.Errorf("Modules() = %v", enabled)
	}
}

func TestEnableFlagOverridesTheDefault(t *testing.T) {
	var built atomic.Int32
	specs := []host.Spec{spec("a", nil, func(o *fakeOpts) { o.built = &built }), spec("b", nil, func(o *fakeOpts) { o.built = &built })}
	specs[1].Default = false
	newHost(t, map[string]string{"AT_MODULES_A_ENABLED": "false", "AT_MODULES_B_ENABLED": "true"}, specs)
	if built.Load() != 1 {
		t.Fatalf("built %d, want only b", built.Load())
	}
}

func TestInvalidSwitchesFailTheStart(t *testing.T) {
	for name, vars := range map[string]map[string]string{
		"enabled": {"AT_MODULES_A_ENABLED": "maybe"},
		"mode":    {"AT_MODULES_A_MODE": "grpc"},
		"mount":   {"AT_MODULES_A_MOUNT": "sideways"},
	} {
		_, err := host.New(host.Config{Getenv: env(vars)}, []host.Spec{spec("a", nil)})
		if err == nil || !strings.Contains(err.Error(), "AT_MODULES_A_") {
			t.Errorf("%s: err = %v", name, err)
		}
	}
}

func TestDuplicateFlatRoutesFailNamingBothModules(t *testing.T) {
	t.Setenv("MANAGEMENT_ADDR", "")
	specs := []host.Spec{
		spec("geo", map[string]string{"POST /v1/addresses/validate": "a"}),
		spec("carrier-gateway", map[string]string{"POST /v1/addresses/validate": "b"}),
	}
	_, err := host.New(host.Config{Getenv: env(nil), Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, specs)
	if err == nil {
		t.Fatal("two modules claiming POST /v1/addresses/validate must fail the start")
	}
	for _, want := range []string{`"geo"`, `"carrier-gateway"`, "POST /v1/addresses/validate", "AT_MODULES_CARRIER_GATEWAY_MOUNT"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error %q does not mention %s", err, want)
		}
	}
	// A parameterised pattern that overlaps a literal one in another module is a duplicate too.
	specs = []host.Spec{
		spec("notifications", map[string]string{"POST /v1/webhooks/resend": "a"}),
		spec("carrier-gateway", map[string]string{"POST /v1/webhooks/{carrier}": "b"}),
	}
	if _, err := host.New(host.Config{Getenv: env(nil), Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, specs); err == nil ||
		!strings.Contains(err.Error(), `"notifications"`) || !strings.Contains(err.Error(), `"carrier-gateway"`) {
		t.Errorf("overlapping patterns: err = %v", err)
	}
	// Prefixing one of them resolves it.
	specs[1].Mount = host.MountPrefixed
	if _, err := host.New(host.Config{Getenv: env(nil), Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, specs); err != nil {
		t.Errorf("prefixed mount should resolve the conflict: %v", err)
	}
}

func TestAConstructorFailureNamesTheModuleAndClosesTheRest(t *testing.T) {
	var closed atomic.Int32
	ok := spec("a", nil, func(o *fakeOpts) {
		o.ctor = func(d module.Deps) (module.Module, error) {
			m, err := o.build(d)
			m.Close = func(context.Context) error { closed.Add(1); return nil }
			return m, err
		}
	})
	bad := host.Spec{Name: "b", Default: true, New: func(module.Deps) (module.Module, error) { return module.Module{}, errors.New("boom") }}
	_, err := host.New(host.Config{Getenv: env(nil), Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}, []host.Spec{ok, bad})
	if err == nil || !strings.Contains(err.Error(), "module b") || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("err = %v", err)
	}
	if closed.Load() != 1 {
		t.Errorf("modules built before the failure must be closed, closed=%d", closed.Load())
	}
}

func TestReadinessReportsTheFailingModule(t *testing.T) {
	down := spec("cart", nil, func(o *fakeOpts) {
		o.checks = []httpserver.ReadinessCheck{check{name: "postgres", err: errors.New("down")}}
	})
	up := spec("geo", nil)
	h := newHost(t, nil, []host.Spec{down, up})
	rec := get(t, h.Handler(), "/readyz")
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "postgres not ready") || !strings.Contains(rec.Body.String(), `"geo":"up"`) {
		t.Errorf("/readyz = %d %s", rec.Code, rec.Body.String())
	}
	if rec := get(t, h.Handler(), "/modules/geo/readyz"); rec.Code != 200 {
		t.Errorf("a healthy module stays ready on its own probe: %d", rec.Code)
	}
	if rec := get(t, h.Handler(), "/modules/cart/readyz"); rec.Code != http.StatusServiceUnavailable {
		t.Errorf("/modules/cart/readyz = %d", rec.Code)
	}
}

func TestMetricsAreMergedWithModuleAndServiceLabels(t *testing.T) {
	h := newHost(t, allOn(), allModules(new(atomic.Int32), nil))
	handler := h.Handler()
	get(t, handler, "/v1/home")
	get(t, handler, "/modules/cart/v1/cart")
	body := get(t, handler, "/metrics").Body.String()
	for _, want := range []string{
		`http_server_requests_total{method="GET",module="gateway-bff",route=`,
		`module="cart"`, `service="cart"`, `service="gateway-bff"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("/metrics lacks %s", want)
		}
	}
	if n := strings.Count(body, "# TYPE go_goroutines "); n != 1 {
		t.Errorf("process collectors should appear once, found %d", n)
	}
	one := get(t, handler, "/modules/cart/metrics").Body.String()
	if strings.Contains(one, `module="gateway-bff"`) || !strings.Contains(one, `module="cart"`) {
		t.Errorf("per-module metrics must hold only that module: %.300s", one)
	}
}

func TestModuleUpGaugeFollowsReadiness(t *testing.T) {
	down := spec("cart", nil, func(o *fakeOpts) {
		o.checks = []httpserver.ReadinessCheck{check{name: "postgres", err: errors.New("down")}}
	})
	h := newHost(t, nil, []host.Spec{down, spec("geo", nil)})
	body := get(t, h.Handler(), "/metrics").Body.String()
	for _, want := range []string{
		`at_module_up{module="cart",service="cart"} 0`,
		`at_module_up{module="geo",service="geo"} 1`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("/metrics lacks %s", want)
		}
	}
	one := get(t, h.Handler(), "/modules/geo/metrics").Body.String()
	if strings.Contains(one, `at_module_up{module="cart"`) || !strings.Contains(one, `at_module_up{module="geo"`) {
		t.Errorf("per-module metrics must hold only that module's at_module_up: %.300s", one)
	}
}

func TestMetricsAreNotPublicWhenTheHostSaysSo(t *testing.T) {
	t.Setenv("MANAGEMENT_ADDR", "")
	h, err := host.New(host.Config{Getenv: env(nil), MetricsPublic: false, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))},
		[]host.Spec{spec("a", map[string]string{"GET /v1/a": "a"})})
	if err != nil {
		t.Fatal(err)
	}
	if rec := get(t, h.Handler(), "/metrics"); rec.Code == 200 && strings.Contains(rec.Body.String(), "go_goroutines") {
		t.Error("/metrics must not be served on the public handler")
	}
	get(t, h.Handler(), "/v1/a")
	if rec := get(t, h.Management(), "/metrics"); rec.Code != 200 || !strings.Contains(rec.Body.String(), `module="a"`) {
		t.Errorf("the management handler serves metrics: %d", rec.Code)
	}
	if rec := get(t, h.Management(), "/v1/a"); rec.Code != 404 {
		t.Errorf("the management handler serves probes and metrics only: %d", rec.Code)
	}
}

func TestPeerURLsResolveInProcessUnlessOverridden(t *testing.T) {
	seen := map[string]string{}
	capture := func(vars ...string) func(*fakeOpts) {
		return func(o *fakeOpts) {
			o.onDeps = func(d module.Deps) {
				for _, v := range vars {
					seen[v], _ = d.Lookup(v)
				}
			}
		}
	}
	mk := func(vars map[string]string) {
		for k := range seen {
			delete(seen, k)
		}
		specs := []host.Spec{
			spec("search", map[string]string{"GET /v1/search": "s"}, func(o *fakeOpts) { o.peerVars = []string{"SEARCH_URL"}; o.mount = host.MountPrefixed }),
			spec("gateway-bff", map[string]string{"GET /v1/home": "h"}, capture("SEARCH_URL", "GEO_URL", "PORT"), func(o *fakeOpts) { o.edge = true }),
		}
		newHost(t, vars, specs)
	}

	mk(map[string]string{"SEARCH_URL": "http://search:8201", "GEO_URL": "http://geo:8205", "PORT": "9000", "GATEWAY_BFF_PORT": "8200"})
	if seen["SEARCH_URL"] != "http://search.inproc" {
		t.Errorf("SEARCH_URL = %q, want the in-process URL", seen["SEARCH_URL"])
	}
	if seen["GEO_URL"] != "http://geo:8205" {
		t.Errorf("GEO_URL (module not running here) = %q", seen["GEO_URL"])
	}
	if seen["PORT"] != "8200" {
		t.Errorf("<MODULE>_<NAME> must win over <NAME>: PORT = %q", seen["PORT"])
	}

	mk(map[string]string{"SEARCH_URL": "http://search:8201", "AT_MODULES_SEARCH_MODE": "http"})
	if seen["SEARCH_URL"] != "http://search:8201" {
		t.Errorf("mode=http must keep the configured URL, got %q", seen["SEARCH_URL"])
	}

	mk(map[string]string{"GATEWAY_BFF_SEARCH_URL": "http://elsewhere:1"})
	if seen["SEARCH_URL"] != "http://elsewhere:1" {
		t.Errorf("an explicit <MODULE>_<NAME> wins over the in-process URL, got %q", seen["SEARCH_URL"])
	}
}

func TestInProcessCallRunsTheCalleesAuthMiddleware(t *testing.T) {
	var gotAuth atomic.Value
	search := spec("search", nil, func(o *fakeOpts) {
		o.mount, o.peerVars = host.MountPrefixed, []string{"SEARCH_URL"}
		o.extra = func(mux *http.ServeMux) {
			// A callee that authenticates like the real services: no bearer token, no answer.
			mux.HandleFunc("GET /v1/search", func(w http.ResponseWriter, r *http.Request) {
				gotAuth.Store(r.Header.Get("Authorization"))
				if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
					http.Error(w, "unauthorized", http.StatusUnauthorized)
					return
				}
				_, _ = io.WriteString(w, "results:"+r.URL.Query().Get("q"))
			})
		}
	})
	var base string
	bff := spec("gateway-bff", map[string]string{"GET /v1/home": "h"}, func(o *fakeOpts) {
		o.edge = true
		o.onDeps = func(d module.Deps) { base, _ = d.Lookup("SEARCH_URL") }
	})
	h := newHost(t, nil, []host.Spec{search, bff})
	client := &http.Client{Transport: h.Transport()}

	req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, base+"/v1/search?q=berbere", nil)
	req.Header.Set("Authorization", "Bearer token-123")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != 200 || string(body) != "results:berbere" || gotAuth.Load() != "Bearer token-123" {
		t.Fatalf("in-process call = %d %q auth=%v", resp.StatusCode, body, gotAuth.Load())
	}
	noAuth, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, base+"/v1/search?q=x", nil)
	resp, err = client.Do(noAuth)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("without a token the callee's middleware must refuse: %d", resp.StatusCode)
	}
	// The call went through the callee's own chain, so its metrics saw it.
	metrics := get(t, h.Handler(), "/modules/search/metrics").Body.String()
	if !strings.Contains(metrics, `http_server_requests_total{method="GET",module="search",`) {
		t.Errorf("the callee's request metrics should count in-process calls: %.400s", metrics)
	}
	geoReq, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, "http://geo"+inproc.HostSuffix+"/x", nil)
	if resp, err := client.Do(geoReq); !errors.Is(err, inproc.ErrModuleDown) {
		if resp != nil {
			_ = resp.Body.Close()
		}
		t.Errorf("a call to a module that is not running = %v", err)
	}
}

func TestSharedDatabaseSuppliesPerModuleSchemas(t *testing.T) {
	got := map[string]string{}
	mk := func(name string) host.Spec {
		return spec(name, nil, func(o *fakeOpts) {
			o.onDeps = func(d module.Deps) { got[name], _ = d.Lookup("DB_URL") }
		}, func(o *fakeOpts) {})
	}
	cart, geo := mk("cart"), mk("geo")
	cart.Schema, geo.Schema = "cart", "geo,public"
	noDB := mk("promise")
	newHost(t, map[string]string{"AT_GO_DB_URL": "postgres://app:pw@db:5432/go?sslmode=require", "GEO_DB_URL": "postgres://geo:pw@db:5432/go"},
		[]host.Spec{cart, geo, noDB})
	if want := "postgres://app:pw@db:5432/go?search_path=cart&sslmode=require"; got["cart"] != want {
		t.Errorf("cart DB_URL = %q, want %q", got["cart"], want)
	}
	if got["geo"] != "postgres://geo:pw@db:5432/go" {
		t.Errorf("an explicit GEO_DB_URL (per-module role) wins, got %q", got["geo"])
	}
	if got["promise"] != "" {
		t.Errorf("a module without a schema gets no database: %q", got["promise"])
	}
}
