package config_test

import (
	"testing"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestEveryEnvironmentLoads(t *testing.T) {
	secrets := map[string]string{"RESEND_API_KEY": "re_x", "TWILIO_AUTH_TOKEN": "tok", "DB_URL": "postgres://x"}
	lookup := func(k string) (string, bool) { v, ok := secrets[k]; return v, ok }
	for _, env := range []string{pkgconfig.EnvLocal, pkgconfig.EnvDev, pkgconfig.EnvUAT, pkgconfig.EnvProd} {
		t.Run(env, func(t *testing.T) {
			var c config.Config
			if err := pkgconfig.NewLoaderFor(notifications.Files, env, lookup).Load(&c); err != nil {
				t.Fatal(err)
			}
			if c.Providers.Resend.APIKey != "re_x" || c.Providers.Twilio.AuthToken != "tok" || c.Database.URL != "postgres://x" {
				t.Fatal("secrets must come from env")
			}
			if c.Routing.SendTimeout != 3*time.Second || c.Breaker.FailureThreshold == 0 || c.Breaker.CoolDown == 0 {
				t.Fatalf("routing/breaker: %+v %+v", c.Routing.SendTimeout, c.Breaker)
			}
			if _, err := domain.ParseQuietHours(c.QuietHours.Start, c.QuietHours.End); err != nil {
				t.Fatal(err)
			}
			if len(c.Routing.Email["default"]) == 0 || len(c.Routing.SMS["default"]) == 0 || c.SMSCompliance.Help == "" {
				t.Fatal("every env needs default email and SMS routes and HELP text")
			}
			if !c.Identity.Enabled || c.Identity.ClientID != "notifications-service" || c.Identity.BaseURL == "" ||
				c.Identity.TokenURL == "" || c.Identity.CacheTTL <= 0 {
				t.Fatalf("identity lookup config: %+v", c.Identity)
			}
			if c.Links.Storefront == "" || c.Links.SellerCenter == "" || c.Links.ProviderHub == "" {
				t.Fatalf("links: %+v", c.Links)
			}
			topics, err := app.ResolveTopics(c.Kafka.Topics)
			if err != nil || len(topics) != len(app.DefaultTopics) {
				t.Fatalf("kafka.topics: %v %v", topics, err)
			}
			switch env {
			case pkgconfig.EnvLocal:
				if c.Routing.Email["default"][0] != "smtp" || c.Routing.SMS["default"][0] != "log" || c.Providers.SMTP.Port != 1025 || c.Port != 8208 {
					t.Fatalf("local must use Mailpit and log SMS: %+v", c.Routing)
				}
			default:
				if c.Routing.Email["us"][0] != "resend" || c.Routing.SMS["us"][0] != "twilio" || !c.Webhooks.Verify || !c.Auth.Enabled {
					t.Fatalf("%s: Resend/Twilio first, webhooks verified, auth on", env)
				}
			}
			if env == pkgconfig.EnvProd && c.DocsEnabled {
				t.Fatal("docs off in prod")
			}
		})
	}
}
