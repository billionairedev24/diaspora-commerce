package config_test

import (
	"net/http"
	"slices"
	"testing"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/mediafetch"
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
			c.Resolve()
			if c.PublicBaseURL == "" || c.Links.ProviderHub != c.Links.SellerCenter {
				t.Fatalf("derived public URL / Provider Hub link: %q %+v", c.PublicBaseURL, c.Links)
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
			if err != nil || len(topics) != len(app.DefaultTopics) || c.Kafka.Topics[app.TopicRetail] != "at.retail.events" {
				t.Fatalf("kafka.topics: %v %v", topics, err)
			}
			a := c.Attachments
			if !a.Enabled || a.MediaBaseURL == "" || len(a.AllowedHosts) == 0 || a.MaxBytes <= 0 || a.MaxTotalBytes < a.MaxBytes ||
				a.Timeout <= 0 {
				t.Fatalf("attachments: %+v", a)
			}
			if _, err := mediafetch.New(mediafetch.Config{MediaBaseURL: a.MediaBaseURL, AllowedHosts: a.AllowedHosts,
				MaxBytes: a.MaxBytes}, http.DefaultClient); err != nil {
				t.Fatalf("attachments: %v", err)
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

// The shared platform URLs injected by the Helm library chart drive the links; explicit settings still win.
func TestSharedPlatformURLsDriveLinks(t *testing.T) {
	env := map[string]string{
		"API_BASE_URL": "https://api.uat.afrotransact.com", "PUBLIC_WEB_URL": "https://uat.afrotransact.com",
		"SELLER_CENTER_URL": "https://seller.uat.afrotransact.com", "MEDIA_PUBLIC_URL": "https://media.uat.afrotransact.com",
		"AUTH_ISSUER_URL": "https://auth.uat.afrotransact.com",
	}
	lookup := func(k string) (string, bool) { v, ok := env[k]; return v, ok }
	var c config.Config
	if err := pkgconfig.NewLoaderFor(notifications.Files, pkgconfig.EnvUAT, lookup).Load(&c); err != nil {
		t.Fatal(err)
	}
	c.Resolve()
	if c.PublicBaseURL != "https://api.uat.afrotransact.com/notifications" || c.Auth.Issuer != "https://auth.uat.afrotransact.com" {
		t.Fatalf("public base / issuer: %q %q", c.PublicBaseURL, c.Auth.Issuer)
	}
	if c.Links.Storefront != "https://uat.afrotransact.com" || c.Links.ProviderHub != "https://seller.uat.afrotransact.com" {
		t.Fatalf("links: %+v", c.Links)
	}
	if !slices.Contains(c.Attachments.AllowedHosts, "media.uat.afrotransact.com") {
		t.Fatalf("media CDN host must be allowed: %v", c.Attachments.AllowedHosts)
	}
	// S250: media-hosted long-lived links (PO PDFs) are fetched from the API host, but only under /media/v1/l/.
	if !slices.Contains(c.Attachments.AllowedHosts, "api.uat.afrotransact.com/media/v1/l/") ||
		slices.Contains(c.Attachments.AllowedHosts, "api.uat.afrotransact.com") {
		t.Fatalf("media link prefix (not the whole API host) must be allowed: %v", c.Attachments.AllowedHosts)
	}

	env["PUBLIC_BASE_URL"] = "https://hooks.example.test/notifications"
	env["PROVIDER_HUB_URL"] = "https://providers.example.test"
	var o config.Config
	if err := pkgconfig.NewLoaderFor(notifications.Files, pkgconfig.EnvUAT, lookup).Load(&o); err != nil {
		t.Fatal(err)
	}
	o.Resolve()
	if o.PublicBaseURL != env["PUBLIC_BASE_URL"] || o.Links.ProviderHub != env["PROVIDER_HUB_URL"] {
		t.Fatalf("explicit settings must win: %q %q", o.PublicBaseURL, o.Links.ProviderHub)
	}
}
