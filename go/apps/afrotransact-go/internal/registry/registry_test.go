package registry_test

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"

	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/host"
	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/registry"
	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart"
	gatewaybff "github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
)

func TestBuildOrderAndLaunchDefaults(t *testing.T) {
	want := []string{"cart", "promise", "geo", "search", "indexer", "notifications", "media", "carrier-gateway", "authz-sync", "gateway-bff"}
	off := map[string]bool{"promise": true, "carrier-gateway": true, "authz-sync": true}
	specs := registry.Specs()
	if len(specs) != len(want) {
		t.Fatalf("%d specs, want %d", len(specs), len(want))
	}
	for i, s := range specs {
		if s.Name != want[i] {
			t.Errorf("spec %d = %s, want %s", i, s.Name, want[i])
		}
		if s.Default == off[s.Name] {
			t.Errorf("%s: Default = %v, want %v (launch set)", s.Name, s.Default, !off[s.Name])
		}
		if s.Edge != (s.Name == "gateway-bff") {
			t.Errorf("%s: Edge = %v", s.Name, s.Edge)
		}
	}
}

// operations lists "METHOD path-template" of an embedded OpenAPI document.
func operations(t *testing.T, raw []byte) []string {
	t.Helper()
	var doc struct {
		Paths map[string]map[string]yaml.Node `yaml:"paths"`
	}
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	var out []string
	for p, ops := range doc.Paths {
		for m := range ops {
			switch mm := strings.ToUpper(m); mm {
			case "GET", "POST", "PUT", "PATCH", "DELETE":
				out = append(out, mm+" "+p)
			}
		}
	}
	return out
}

func overlap(a, b string) bool {
	ma, pa, _ := strings.Cut(a, " ")
	mb, pb, _ := strings.Cut(b, " ")
	if ma != mb {
		return false
	}
	sa, sb := strings.Split(pa, "/"), strings.Split(pb, "/")
	if len(sa) != len(sb) {
		return false
	}
	for i := range sa {
		if sa[i] != sb[i] && !strings.HasPrefix(sa[i], "{") && !strings.HasPrefix(sb[i], "{") {
			return false
		}
	}
	return true
}

// TestRealRouteSetsDoNotCollideOnTheSharedListener checks the real OpenAPI documents of the Go services against the
// registry's mounts. Today cart and search document the same paths as gateway-bff's facade, and carrier-gateway shares
// POST /v1/addresses/validate with geo and /v1/webhooks/* with notifications: those three are prefixed. A new route
// on main that collides again fails here (and at startup) instead of in production.
func TestRealRouteSetsDoNotCollideOnTheSharedListener(t *testing.T) {
	docs := map[string][][]byte{}
	read := func(files interface{ ReadFile(string) ([]byte, error) }, names ...string) [][]byte {
		var out [][]byte
		for _, n := range names {
			b, err := files.ReadFile(n)
			if err != nil {
				t.Fatal(err)
			}
			out = append(out, b)
		}
		return out
	}
	docs["cart"] = read(cart.Files, "api/openapi.yaml")
	docs["promise"] = read(promise.Files, "api/openapi.yaml")
	docs["geo"] = read(geo.Files, "api/openapi.yaml")
	docs["search"] = read(search.Files, "api/openapi.yaml")
	docs["indexer"] = read(indexer.Files, "api/openapi.yaml")
	docs["notifications"] = read(notifications.Files, "api/openapi.yaml")
	docs["media"] = read(media.Files, "api/openapi.yaml")
	docs["carrier-gateway"] = read(carriergateway.Files, "api/openapi.yaml")
	docs["authz-sync"] = read(authzsync.Files, "api/openapi.yaml")
	docs["gateway-bff"] = read(gatewaybff.Files, "api/openapi.yaml", "api/openapi-seller.yaml", "api/openapi-staff.yaml")

	flat := map[string][]string{}
	for _, s := range registry.Specs() {
		if s.Mount == host.MountPrefixed || s.Mount == host.MountInternal {
			continue
		}
		for _, raw := range docs[s.Name] {
			for _, op := range operations(t, raw) {
				switch strings.Fields(op)[1] {
				case "/healthz", "/readyz", "/metrics", "/openapi.yaml", "/docs", "/redoc":
					continue
				}
				flat[s.Name] = append(flat[s.Name], op)
			}
		}
	}
	names := make([]string, 0, len(flat))
	for n := range flat {
		names = append(names, n)
	}
	for i, a := range names {
		for _, b := range names[i+1:] {
			for _, x := range flat[a] {
				for _, y := range flat[b] {
					if overlap(x, y) {
						t.Errorf("flat routes collide: %s %q and %s %q (prefix one module or rename the route)", a, x, b, y)
					}
				}
			}
		}
	}
}

// TestRealPromiseModuleBootsInsideTheHost builds the real promise service (it opens no connection until used) and serves
// its own pages and probes through the host.
func TestRealPromiseModuleBootsInsideTheHost(t *testing.T) {
	t.Setenv("MANAGEMENT_ADDR", "")
	vars := map[string]string{"AT_MODULES_PROMISE_ENABLED": "true", "PROMISE_REDIS_URL": "redis://127.0.0.1:1/0"}
	var specs []host.Spec
	for _, s := range registry.Specs() {
		if s.Name == "promise" {
			specs = append(specs, s)
		}
	}
	h, err := host.New(host.Config{Env: "local", MetricsPublic: true, Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Getenv: func(k string) (string, bool) { v, ok := vars[k]; return v, ok }}, specs)
	if err != nil {
		t.Fatalf("host.New: %v", err)
	}
	defer func() { _ = h.Close(t.Context()) }()
	get := func(path string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		h.Handler().ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil))
		return rec
	}
	if rec := get("/modules/promise/openapi.yaml"); rec.Code != 200 || !strings.Contains(rec.Body.String(), "openapi") {
		t.Errorf("openapi: %d", rec.Code)
	}
	if rec := get("/readyz"); rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "valkey not ready") {
		t.Errorf("readyz without Redis = %d %s", rec.Code, rec.Body.String())
	}
	if body := get("/metrics").Body.String(); !strings.Contains(body, `module="promise"`) || !strings.Contains(body, `service="promise"`) {
		t.Error("promise metrics must carry module and service labels")
	}
}
