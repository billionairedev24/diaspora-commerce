package config_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

func TestTokenURL(t *testing.T) {
	cases := map[string]string{
		"https://auth.dev.afrotransact.com":  "https://auth.dev.afrotransact.com/oauth2/token",
		"https://auth.dev.afrotransact.com/": "https://auth.dev.afrotransact.com/oauth2/token",
		"http://localhost:8100":              "http://localhost:8100/oauth2/token",
		"":                                   "",
		"   ":                                "",
	}
	for issuer, want := range cases {
		if got := config.TokenURL(issuer); got != want {
			t.Errorf("TokenURL(%q) = %q, want %q", issuer, got, want)
		}
	}
}

func TestJoinURL(t *testing.T) {
	if got := config.JoinURL("https://api.uat.afrotransact.com/", "/notifications"); got != "https://api.uat.afrotransact.com/notifications" {
		t.Fatalf("JoinURL = %q", got)
	}
	if got := config.JoinURL("http://localhost:3001", ""); got != "http://localhost:3001" {
		t.Fatalf("JoinURL without path = %q", got)
	}
	if got := config.JoinURL("", "/x"); got != "" {
		t.Fatalf("JoinURL with empty base = %q", got)
	}
}

func TestFirstNonEmpty(t *testing.T) {
	if got := config.FirstNonEmpty("", "  ", "b", "c"); got != "b" {
		t.Fatalf("FirstNonEmpty = %q", got)
	}
	if got := config.FirstNonEmpty(); got != "" {
		t.Fatalf("FirstNonEmpty() = %q", got)
	}
}

// The shared names are what the Helm library chart renders; services bind them through the normal env overrides.
func TestSharedNamesOverrideYAML(t *testing.T) {
	type svc struct {
		Links struct {
			Storefront string `yaml:"storefront" env:"PUBLIC_WEB_URL"`
		} `yaml:"links"`
	}
	env := map[string]string{config.EnvPublicWebURL: "https://uat.afrotransact.com"}
	l := config.NewLoaderFor(nil, "uat", func(k string) (string, bool) { v, ok := env[k]; return v, ok })
	var c svc
	if err := l.Load(&c); err != nil {
		t.Fatal(err)
	}
	if c.Links.Storefront != "https://uat.afrotransact.com" {
		t.Fatalf("storefront = %q", c.Links.Storefront)
	}
}
