package config_test

import (
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
)

func load(t *testing.T, env string, vars map[string]string) config.Config {
	t.Helper()
	var c config.Config
	lookup := func(k string) (string, bool) { v, ok := vars[k]; return v, ok }
	if err := pkgconfig.NewLoaderFor(indexer.Files, env, lookup).Load(&c); err != nil {
		t.Fatal(err)
	}
	c.Resolve(env)
	return c
}

func TestSellerDirectoryTokenURL(t *testing.T) {
	if got := load(t, pkgconfig.EnvLocal, nil).SellerDirectory.TokenURL; got != "" {
		t.Fatalf("local runs without a token, got %q", got)
	}
	want := map[string]string{
		pkgconfig.EnvDev:  "https://auth.dev.afrotransact.com/oauth2/token",
		pkgconfig.EnvUAT:  "https://auth.uat.afrotransact.com/oauth2/token",
		pkgconfig.EnvProd: "https://auth.afrotransact.com/oauth2/token",
	}
	for env, w := range want {
		if got := load(t, env, nil).SellerDirectory.TokenURL; got != w {
			t.Errorf("%s: token URL %q, want %q", env, got, w)
		}
	}
	// The issuer injected by the Helm library drives it; an explicit AUTH_TOKEN_URL wins.
	if got := load(t, pkgconfig.EnvUAT, map[string]string{"AUTH_ISSUER_URL": "https://auth.example.test"}).SellerDirectory.TokenURL; got != "https://auth.example.test/oauth2/token" {
		t.Errorf("from AUTH_ISSUER_URL: %q", got)
	}
	if got := load(t, pkgconfig.EnvUAT, map[string]string{"AUTH_TOKEN_URL": "http://identity/oauth2/token"}).SellerDirectory.TokenURL; got != "http://identity/oauth2/token" {
		t.Errorf("explicit AUTH_TOKEN_URL: %q", got)
	}
}
