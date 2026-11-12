package config

import (
	"errors"
	"fmt"
	"net/mail"
	"slices"
	"strings"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// localOnlyProviders never deliver anything to a real person: Mailpit (smtp) and the log senders.
var localOnlyProviders = []string{"smtp", "log"}

// Validate refuses a configuration that must not run in the given environment. In prod email goes out through Resend
// (API key present, Resend first on every market's route, the webhook secret set so bounces and complaints reach the
// suppression list), nothing is routed to Mailpit or the log, one-click unsubscribe links are signed with a real key
// and the sender address is on a real domain. Other environments are not restricted.
func (c Config) Validate(env string) error {
	if env != pkgconfig.EnvProd {
		return nil
	}
	var errs []error
	fail := func(format string, a ...any) { errs = append(errs, fmt.Errorf(format, a...)) }

	if strings.TrimSpace(c.Providers.Resend.APIKey) == "" {
		fail("RESEND_API_KEY is not set: prod sends email through Resend")
	}
	if len(c.Routing.Email) == 0 {
		fail("routing.email has no markets")
	}
	for market, names := range c.Routing.Email {
		if len(names) == 0 || names[0] != "resend" {
			fail("routing.email.%s must start with resend, got %v", market, names)
		}
	}
	if _, ok := c.Routing.Email["default"]; !ok {
		fail("routing.email.default is required")
	}
	for channel, routes := range map[string]Routes{"email": c.Routing.Email, "sms": c.Routing.SMS,
		"push": c.Routing.Push, "chat": c.Routing.Chat} {
		for market, names := range routes {
			for _, n := range names {
				if slices.Contains(localOnlyProviders, n) {
					fail("routing.%s.%s uses %q, which never reaches a real recipient", channel, market, n)
				}
			}
		}
	}
	if c.Routing.PreferConfigured {
		fail("routing.preferConfigured is for the local profile only")
	}
	if !c.Webhooks.Verify {
		fail("webhooks.verify must be true")
	}
	if strings.TrimSpace(c.Webhooks.ResendSecret) == "" {
		fail("RESEND_WEBHOOK_SECRET is not set: bounces and complaints would not reach the suppression list")
	}
	if len(c.UnsubscribeSigningKey) < 32 {
		fail("UNSUBSCRIBE_SIGNING_KEY must be at least 32 characters")
	}
	if !c.Auth.Enabled {
		fail("auth.enabled must be true")
	}
	if err := validSender(c.Email.From); err != nil {
		fail("email.from: %v", err)
	}
	return errors.Join(errs...)
}

// validSender accepts "Name <user@domain>" or "user@domain" on a real domain.
func validSender(from string) error {
	addr, err := mail.ParseAddress(from)
	if err != nil {
		return fmt.Errorf("%q is not an address: %w", from, err)
	}
	at := strings.LastIndex(addr.Address, "@")
	domain := strings.ToLower(addr.Address[at+1:])
	switch {
	case !strings.Contains(domain, "."):
		return fmt.Errorf("%q has no real domain", domain)
	case strings.HasSuffix(domain, ".test"), strings.HasSuffix(domain, ".local"), strings.HasSuffix(domain, ".invalid"),
		strings.HasSuffix(domain, ".example"), domain == "example.com", domain == "resend.dev":
		return fmt.Errorf("%q is not a verified sending domain (verify your own domain in Resend)", domain)
	}
	return nil
}
