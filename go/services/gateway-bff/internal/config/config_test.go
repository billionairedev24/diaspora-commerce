package config_test

import (
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
	if err := c.Validate("prod"); err == nil {
		t.Fatal("prod without REDIS_URL / SESSION_SIGNING_KEY must fail")
	}
}
