package config_test

import (
	"strings"
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/config"
)

func prodConfig(t *testing.T) config.Config {
	t.Helper()
	env := map[string]string{
		"RESEND_API_KEY": "re_test_key", "RESEND_WEBHOOK_SECRET": "whsec_dGVzdA==", "DB_URL": "postgres://x",
		"UNSUBSCRIBE_SIGNING_KEY": strings.Repeat("k", 40),
	}
	var c config.Config
	lookup := func(k string) (string, bool) { v, ok := env[k]; return v, ok }
	if err := pkgconfig.NewLoaderFor(notifications.Files, pkgconfig.EnvProd, lookup).Load(&c); err != nil {
		t.Fatal(err)
	}
	c.Resolve()
	return c
}

func TestProdAcceptsResendWithItsSecrets(t *testing.T) {
	if err := prodConfig(t).Validate(pkgconfig.EnvProd); err != nil {
		t.Fatalf("a complete prod configuration must be accepted: %v", err)
	}
}

func TestProdRefusesUnsafeEmailSetups(t *testing.T) {
	cases := map[string]func(c *config.Config){
		"no resend key":      func(c *config.Config) { c.Providers.Resend.APIKey = "" },
		"no webhook secret":  func(c *config.Config) { c.Webhooks.ResendSecret = "" },
		"webhooks unchecked": func(c *config.Config) { c.Webhooks.Verify = false },
		"short unsub key":    func(c *config.Config) { c.UnsubscribeSigningKey = "short" },
		"mailpit route":      func(c *config.Config) { c.Routing.Email["us"] = []string{"resend", "smtp"} },
		"log sms":            func(c *config.Config) { c.Routing.SMS["default"] = []string{"log"} },
		"resend not first":   func(c *config.Config) { c.Routing.Email["default"] = []string{"sendgrid", "resend"} },
		"prefer configured":  func(c *config.Config) { c.Routing.PreferConfigured = true },
		"auth off":           func(c *config.Config) { c.Auth.Enabled = false },
		"sandbox sender":     func(c *config.Config) { c.Email.From = "AfroTransact <onboarding@resend.dev>" },
		"fake sender":        func(c *config.Config) { c.Email.From = "no-reply@afrotransact.test" },
		"garbage sender":     func(c *config.Config) { c.Email.From = "not an address" },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			c := prodConfig(t)
			mutate(&c)
			if err := c.Validate(pkgconfig.EnvProd); err == nil {
				t.Fatal("expected prod to refuse this configuration")
			}
		})
	}
}

func TestValidateOnlyRestrictsProd(t *testing.T) {
	var c config.Config
	for _, env := range []string{pkgconfig.EnvLocal, pkgconfig.EnvDev, pkgconfig.EnvUAT} {
		if err := c.Validate(env); err != nil {
			t.Fatalf("%s must not be restricted: %v", env, err)
		}
	}
}
