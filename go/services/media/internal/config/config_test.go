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

// S030 / S250: deployed environments derive media's public link base from API_BASE_URL, accept BFF-forwarded user
// tokens and moderate with the AI hook (failing closed in uat and prod).
func TestDeployedLinksForwardedTokensAndModeration(t *testing.T) {
	failModes := map[string]bool{pkgconfig.EnvDev: true, pkgconfig.EnvUAT: false, pkgconfig.EnvProd: false}
	for env, failOpen := range failModes {
		var c config.Config
		lookup := func(k string) (string, bool) {
			if k == pkgconfig.EnvAPIBaseURL {
				return "https://api.example.test/", true
			}
			return "", false
		}
		if err := pkgconfig.NewLoaderFor(media.Files, env, lookup).Load(&c); err != nil {
			t.Fatal(err)
		}
		c.Resolve(env)
		if c.PublicBaseURL != "https://api.example.test/media" {
			t.Errorf("%s: public base %q", env, c.PublicBaseURL)
		}
		if len(c.Auth.ForwardedAudiences) != 1 || c.Auth.ForwardedAudiences[0] != "gateway-bff" {
			t.Errorf("%s: forwarded audiences %v", env, c.Auth.ForwardedAudiences)
		}
		ai := c.Moderation.AI
		if !ai.Enabled || ai.BaseURL == "" || ai.ClientID != "media-service" || ai.TokenURL == "" || c.FailOpen() != failOpen {
			t.Errorf("%s: AI moderation %+v (fail open %v)", env, ai, c.FailOpen())
		}
	}
	var local config.Config
	if err := pkgconfig.NewLoaderFor(media.Files, pkgconfig.EnvLocal, func(string) (string, bool) { return "", false }).Load(&local); err != nil {
		t.Fatal(err)
	}
	local.Resolve(pkgconfig.EnvLocal)
	if local.PublicBaseURL != "http://localhost:8207" || local.Moderation.AI.Enabled || local.Moderation.AI.TokenURL != "" {
		t.Errorf("local: %q %+v", local.PublicBaseURL, local.Moderation.AI)
	}
}

// Local LAN mode: the MinIO endpoint (presigned links) follows LAN_MINIO_ENDPOINT; stored public URLs do not change.
func TestLocalStoresFollowTheLANMinioEndpoint(t *testing.T) {
	s := config.Store{Endpoint: "http://localhost:9000", EndpointEnv: "LAN_MINIO_ENDPOINT", PublicBaseURL: "http://localhost:9000/media"}
	env := map[string]string{}
	getenv := func(k string) string { return env[k] }
	if got := s.ResolvedEndpoint(getenv); got != "http://localhost:9000" {
		t.Fatalf("localhost mode = %q", got)
	}
	env["LAN_MINIO_ENDPOINT"] = "http://192.168.1.50:9000"
	if got := s.ResolvedEndpoint(getenv); got != "http://192.168.1.50:9000" {
		t.Fatalf("LAN mode = %q", got)
	}
	if got := s.ResolvedPublicBaseURL(getenv); got != "http://localhost:9000/media" {
		t.Fatalf("public URL changed: %q", got)
	}
}
