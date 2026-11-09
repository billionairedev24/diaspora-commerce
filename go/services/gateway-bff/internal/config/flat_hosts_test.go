package config_test

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/config"
)

// The OVH test environment on the free Cloudflare certificate (docs/engineering/environments.md, "Free certificate layout (flat
// web hosts)"): base domain test.example.test, storefront at the apex, Seller Center and staff console on hosts that are NOT
// subdomains of the base. Helm injects the three origins; everything else derives from them, as for every other environment.
var flatHosts = map[string]string{
	pkgconfig.EnvPublicWebURL:    "https://test.example.test",
	pkgconfig.EnvSellerCenterURL: "https://seller-test.example.test",
	pkgconfig.EnvStaffConsoleURL: "https://admin-test.example.test",
	pkgconfig.EnvAuthIssuerURL:   "https://auth.test.example.test",
}

func TestExplicitWebHostsAreTheOnlyDerivedOrigins(t *testing.T) {
	c := load(t, "dev", flatHosts)
	want := []string{"https://test.example.test", "https://seller-test.example.test", "https://admin-test.example.test"}
	if !slices.Equal(c.Web.Origins, want) {
		t.Fatalf("origins = %v, want exactly %v (no www./seller./admin. under the base, no wildcard)", c.Web.Origins, want)
	}
	if c.Web.PublicOrigin != want[0] || c.OIDC.Issuer != "https://auth.test.example.test" {
		t.Fatalf("storefront origin %q, issuer %q", c.Web.PublicOrigin, c.OIDC.Issuer)
	}
	// Each app signs in through its own origin's callback, so every session cookie stays first-party and host-only.
	wantCallbacks := map[string]string{
		want[0]: want[0] + "/api/bff/v1/auth/oidc/callback",
		want[1]: want[1] + "/api/bff/v1/auth/oidc/callback",
		want[2]: want[2] + "/api/bff/v1/auth/oidc/callback",
	}
	got := c.RedirectURIs()
	if len(got) != len(wantCallbacks) {
		t.Fatalf("redirect URIs = %v, want exactly %v", got, wantCallbacks)
	}
	for o, cb := range wantCallbacks {
		if got[o] != cb {
			t.Fatalf("callback for %s = %q, want %q", o, got[o], cb)
		}
	}
	apps := c.AppOrigins()
	if apps[config.AppStorefront] != want[0] || apps[config.AppSellerCenter] != want[1] || apps[config.AppStaffConsole] != want[2] {
		t.Fatalf("app origins = %v", apps)
	}
	if c.Session.CookieDomain != "" {
		t.Fatalf("the session cookie must stay host-only, Domain=%q", c.Session.CookieDomain)
	}
}

func TestDefaultWebHostsStillDeriveFromTheBaseDomain(t *testing.T) {
	c := load(t, "dev", map[string]string{
		pkgconfig.EnvPublicWebURL:    "https://www.test.example.test",
		pkgconfig.EnvSellerCenterURL: "https://seller.test.example.test",
		pkgconfig.EnvStaffConsoleURL: "https://admin.test.example.test",
	})
	want := []string{"https://www.test.example.test", "https://seller.test.example.test", "https://admin.test.example.test"}
	if !slices.Equal(c.Web.Origins, want) {
		t.Fatalf("origins = %v, want %v", c.Web.Origins, want)
	}
}

// CORS and the CSRF origin check compare exact origins: the explicit hosts are allowed, their siblings, parents and suffix
// look-alikes are not.
func TestExplicitWebHostsAreMatchedExactly(t *testing.T) {
	c := load(t, "dev", flatHosts)
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{}, nil, httpapi.SecurityOptions{AllowedOrigins: c.Web.Origins}, slog.Default()).Register(mux)
	preflight := func(origin string) string {
		req := httptest.NewRequestWithContext(context.Background(), http.MethodOptions, "http://bff.internal/v1/me", nil)
		req.Header.Set("Origin", origin)
		req.Header.Set("Access-Control-Request-Method", "GET")
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec.Header().Get("Access-Control-Allow-Origin")
	}
	for _, ok := range c.Web.Origins {
		if got := preflight(ok); got != ok {
			t.Errorf("origin %s must be allowed, Allow-Origin = %q", ok, got)
		}
	}
	for _, bad := range []string{
		"https://seller.test.example.test",           // the default host is not allowed once an explicit one is set
		"https://admin.test.example.test",            //
		"https://www.test.example.test",              //
		"https://evil.seller-test.example.test",      // a subdomain of an allowed host
		"https://seller-test.example.test.evil.test", // a suffix look-alike
		"https://xseller-test.example.test",          // a prefix look-alike
		"http://seller-test.example.test",            // wrong scheme
		"https://seller-test.example.test:8443",      // wrong port
		"https://example.test",                       // the zone apex
	} {
		if got := preflight(bad); got != "" {
			t.Errorf("origin %s must NOT be allowed, Allow-Origin = %q", bad, got)
		}
	}
}
