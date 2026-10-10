package config_test

import (
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/config"
)

func load(t *testing.T, env string, vars map[string]string) config.Config {
	t.Helper()
	var c config.Config
	lookup := func(k string) (string, bool) { v, ok := vars[k]; return v, ok }
	if err := pkgconfig.NewLoaderFor(search.Files, env, lookup).Load(&c); err != nil {
		t.Fatal(err)
	}
	c.Resolve(env)
	return c
}

func TestContentTokenURLFollowsIssuer(t *testing.T) {
	if got := load(t, pkgconfig.EnvLocal, nil).Ads.Content.TokenURL; got != "" {
		t.Fatalf("local runs without a token, got %q", got)
	}
	for _, env := range []string{pkgconfig.EnvDev, pkgconfig.EnvUAT, pkgconfig.EnvProd} {
		c := load(t, env, nil)
		if c.Ads.Content.TokenURL != pkgconfig.TokenURL(c.Auth.Issuer) || c.Auth.Issuer == "" {
			t.Errorf("%s: token URL %q for issuer %q", env, c.Ads.Content.TokenURL, c.Auth.Issuer)
		}
	}
	c := load(t, pkgconfig.EnvProd, map[string]string{"AUTH_ISSUER_URL": "https://auth.example.test"})
	if c.Auth.Issuer != "https://auth.example.test" || c.Ads.Content.TokenURL != "https://auth.example.test/oauth2/token" {
		t.Errorf("AUTH_ISSUER_URL must drive issuer and token URL: %q %q", c.Auth.Issuer, c.Ads.Content.TokenURL)
	}
}
