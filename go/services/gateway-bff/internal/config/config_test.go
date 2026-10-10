package config_test

import (
	"maps"
	"slices"
	"strings"
	"testing"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	gatewaybff "github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/config"
)

func TestEveryEnvironmentLoads(t *testing.T) {
	secrets := map[string]string{"REDIS_URL": "redis://valkey:6379/0", "SESSION_SIGNING_KEY": "0123456789abcdef0123456789abcdef",
		"OIDC_CLIENT_SECRET": "s", "CATALOG_URL": "http://catalog.override"}
	for _, env := range []string{"local", "dev", "uat", "prod"} {
		var c config.Config
		lookup := func(k string) (string, bool) { v, ok := secrets[k]; return v, ok }
		if err := pkgconfig.NewLoaderFor(gatewaybff.Files, env, lookup).Load(&c); err != nil {
			t.Fatalf("%s: %v", env, err)
		}
		c.ApplyUpstreamEnv(lookup)
		c.Resolve()
		if err := c.Validate(env); err != nil {
			t.Fatalf("%s: %v", env, err)
		}
		if c.Upstreams.Catalog.URL != "http://catalog.override" || c.Upstreams.Search.Timeout != 2*time.Second || c.Upstreams.Promise.Timeout != 800*time.Millisecond {
			t.Fatalf("%s: upstreams = %+v", env, c.Upstreams)
		}
		if len(c.Web.Origins) == 0 || c.OIDC.ClientID != "gateway-bff" || c.Session.IdleTTL != 720*time.Hour || c.Cache.ReferenceTTL != 10*time.Minute {
			t.Fatalf("%s: %+v", env, c)
		}
		if env != "local" && (!c.Session.CookieSecure || c.DocsEnabled && env == "prod") {
			t.Fatalf("%s: cookies must be Secure and docs off in prod", env)
		}
	}
	var c config.Config
	_ = pkgconfig.NewLoaderFor(gatewaybff.Files, "prod", func(string) (string, bool) { return "", false }).Load(&c)
	c.Resolve()
	if err := c.Validate("prod"); err == nil {
		t.Fatal("prod without REDIS_URL / SESSION_SIGNING_KEY must fail")
	}
}

// Each environment's YAML derives the web origins, storefront origin, issuer and callback from its own hosts.
func TestPlatformURLsPerEnvironment(t *testing.T) {
	cases := map[string]struct{ web, seller, staff, issuer, redirect string }{
		"local": {"http://localhost:3000", "http://localhost:3001", "http://localhost:3002", "http://localhost:8100",
			"http://localhost:8200/login/oauth2/code/afrotransact"},
		"dev": {"https://dev.afrotransact.com", "https://seller.dev.afrotransact.com", "https://admin.dev.afrotransact.com",
			"https://auth.dev.afrotransact.com", "https://dev.afrotransact.com/api/bff/v1/auth/oidc/callback"},
		"uat": {"https://uat.afrotransact.com", "https://seller.uat.afrotransact.com", "https://admin.uat.afrotransact.com",
			"https://auth.uat.afrotransact.com", "https://uat.afrotransact.com/api/bff/v1/auth/oidc/callback"},
		"prod": {"https://afrotransact.com", "https://seller.afrotransact.com", "https://admin.afrotransact.com",
			"https://auth.afrotransact.com", "https://afrotransact.com/api/bff/v1/auth/oidc/callback"},
	}
	for env, want := range cases {
		c := load(t, env, nil)
		if c.Web.PublicOrigin != want.web || c.OIDC.Issuer != want.issuer || c.OIDC.PublicURL != want.issuer ||
			c.OIDC.RedirectURI != want.redirect {
			t.Fatalf("%s: web=%q issuer=%q public=%q redirect=%q", env, c.Web.PublicOrigin, c.OIDC.Issuer,
				c.OIDC.PublicURL, c.OIDC.RedirectURI)
		}
		if got := c.Web.Origins; !slices.Equal(got, []string{want.web, want.seller, want.staff}) {
			t.Fatalf("%s: origins = %v", env, got)
		}
	}
}

// The values Helm injects (go/pkg/config/platform.go names) override YAML; explicit BFF settings override both.
func TestPlatformEnvOverrides(t *testing.T) {
	c := load(t, "prod", map[string]string{
		pkgconfig.EnvPublicWebURL:    "https://shop.example.test/",
		pkgconfig.EnvSellerCenterURL: "https://seller.example.test",
		pkgconfig.EnvStaffConsoleURL: "https://admin.example.test",
		pkgconfig.EnvAuthIssuerURL:   "https://auth.example.test",
	})
	if c.Web.PublicOrigin != "https://shop.example.test" || c.OIDC.Issuer != "https://auth.example.test" ||
		c.OIDC.RedirectURI != "https://shop.example.test/api/bff/v1/auth/oidc/callback" ||
		!slices.Equal(c.Web.Origins, []string{"https://shop.example.test", "https://seller.example.test", "https://admin.example.test"}) {
		t.Fatalf("platform env not applied: %+v %+v", c.Web, c.OIDC)
	}

	c = load(t, "dev", map[string]string{
		pkgconfig.EnvPublicWebURL: "https://shop.example.test",
		"WEB_ORIGINS":             "https://a.example.test, https://b.example.test",
		"WEB_PUBLIC_ORIGIN":       "https://a.example.test",
		"OIDC_ISSUER":             "http://identity:8080",
		"OIDC_PUBLIC_URL":         "https://login.example.test",
		"OIDC_REDIRECT_URI":       "https://a.example.test/cb",
	})
	if c.Web.PublicOrigin != "https://a.example.test" || c.OIDC.Issuer != "http://identity:8080" ||
		c.OIDC.PublicURL != "https://login.example.test" || c.OIDC.RedirectURI != "https://a.example.test/cb" ||
		!slices.Equal(c.Web.Origins, []string{"https://a.example.test", "https://b.example.test"}) {
		t.Fatalf("explicit overrides must win: %+v %+v", c.Web, c.OIDC)
	}
}

func TestMissingPlatformURLsFail(t *testing.T) {
	var c config.Config
	c.RedisURL = "redis://x"
	c.Session.SigningKey = "0123456789abcdef0123456789abcdef"
	c.Resolve()
	if err := c.Validate("dev"); err == nil || !strings.Contains(err.Error(), "AUTH_ISSUER_URL") {
		t.Fatalf("no issuer must fail, got %v", err)
	}
	c.Platform.AuthIssuerURL = "http://identity"
	c.Resolve()
	if err := c.Validate("dev"); err == nil || !strings.Contains(err.Error(), "PUBLIC_WEB_URL") {
		t.Fatalf("no storefront origin must fail, got %v", err)
	}
}

func load(t *testing.T, env string, extra map[string]string) config.Config {
	t.Helper()
	vars := map[string]string{"REDIS_URL": "redis://valkey:6379/0", "SESSION_SIGNING_KEY": "0123456789abcdef0123456789abcdef"}
	maps.Copy(vars, extra)
	lookup := func(k string) (string, bool) { v, ok := vars[k]; return v, ok }
	var c config.Config
	if err := pkgconfig.NewLoaderFor(gatewaybff.Files, env, lookup).Load(&c); err != nil {
		t.Fatalf("%s: %v", env, err)
	}
	c.ApplyUpstreamEnv(lookup)
	c.Resolve()
	if err := c.Validate(env); err != nil {
		t.Fatalf("%s: %v", env, err)
	}
	return c
}
