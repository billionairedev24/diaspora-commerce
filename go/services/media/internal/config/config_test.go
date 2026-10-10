package config_test

import (
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/config"
)

func TestResolvedPublicBaseURL(t *testing.T) {
	s := config.Store{PublicBaseURL: "https://media.dev.afrotransact.com", PublicBaseURLEnv: "MEDIA_PUBLIC_URL"}
	env := map[string]string{}
	getenv := func(k string) string { return env[k] }
	if got := s.ResolvedPublicBaseURL(getenv); got != "https://media.dev.afrotransact.com" {
		t.Fatalf("fallback = %q", got)
	}
	env["MEDIA_PUBLIC_URL"] = "https://media.uat.afrotransact.com"
	if got := s.ResolvedPublicBaseURL(getenv); got != "https://media.uat.afrotransact.com" {
		t.Fatalf("env = %q", got)
	}
	if got := (config.Store{PublicBaseURL: "http://localhost:9000/media"}).ResolvedPublicBaseURL(getenv); got != "http://localhost:9000/media" {
		t.Fatalf("store without env = %q", got)
	}
}

// Every deployed environment's public store follows MEDIA_PUBLIC_URL (injected by the Helm library chart).
func TestDeployedPublicStoresFollowMediaPublicURL(t *testing.T) {
	for _, env := range []string{pkgconfig.EnvDev, pkgconfig.EnvUAT, pkgconfig.EnvProd} {
		var c config.Config
		if err := pkgconfig.NewLoaderFor(media.Files, env, func(string) (string, bool) { return "", false }).Load(&c); err != nil {
			t.Fatal(err)
		}
		found := false
		for name, s := range c.Stores {
			if s.PublicBaseURL != "" {
				found = true
				if s.PublicBaseURLEnv != pkgconfig.EnvMediaPublicURL {
					t.Errorf("%s: store %s must set publicBaseUrlEnv: MEDIA_PUBLIC_URL", env, name)
				}
			}
		}
		if !found {
			t.Errorf("%s: no public store", env)
		}
	}
}
