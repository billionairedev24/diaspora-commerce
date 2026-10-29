package config_test

import (
	"testing"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/config"
)

func TestEveryEnvironmentLoads(t *testing.T) {
	for _, env := range []string{"local", "dev", "uat", "prod"} {
		t.Run(env, func(t *testing.T) {
			secrets := map[string]string{"USPS_CLIENT_ID": "id", "FEDEX_WEBHOOK_SECRET": "s", "RATE_BUDGET": "1200ms"}
			var cfg config.Config
			err := pkgconfig.NewLoaderFor(carriergateway.Files, env, func(k string) (string, bool) { v, ok := secrets[k]; return v, ok }).Load(&cfg)
			if err != nil {
				t.Fatal(err)
			}
			cfg.Resolve()
			if cfg.PublicBaseURL == "" {
				t.Fatal("publicBaseUrl (or apiBaseUrl) is required for carrier webhooks")
			}
			wantInterval := 2 * time.Hour
			if env == "local" {
				wantInterval = 1 * time.Minute
			}
			if cfg.Rates.Budget != 1200*time.Millisecond || cfg.Rates.QuoteTTL != 10*time.Minute || cfg.Polling.Interval != wantInterval {
				t.Fatalf("durations %+v %+v", cfg.Rates, cfg.Polling)
			}
			if cfg.USPS.ClientID != "id" || cfg.FedEx.WebhookSecret != "s" || cfg.UPS.Paths.Rate != "/api/rating/v2409/Shop" ||
				cfg.USPS.Paths.Label != "/labels/v3/label" || cfg.FedEx.Paths.Track != "/track/v1/trackingnumbers" || cfg.DHL.Paths.Track == "" {
				t.Fatalf("carrier config %+v", cfg)
			}
			if len(cfg.Accounts.Defaults) == 0 || len(cfg.Accounts.Nodes["fc_houston_tx"]) == 0 {
				t.Fatal("accounts missing")
			}
			if env == "prod" && (cfg.DocsEnabled || cfg.USPS.BaseURL != "https://apis.usps.com" || cfg.LabelStore.Kind != "s3") {
				t.Fatalf("prod must use production carriers, S3 and no docs: %+v", cfg)
			}
		})
	}
}

// API_BASE_URL (injected by the Helm library chart) drives the webhook base unless PUBLIC_BASE_URL is set.
func TestPublicBaseURLFollowsAPIBaseURL(t *testing.T) {
	env := map[string]string{"API_BASE_URL": "https://api.uat.afrotransact.com/"}
	lookup := func(k string) (string, bool) { v, ok := env[k]; return v, ok }
	var cfg config.Config
	if err := pkgconfig.NewLoaderFor(carriergateway.Files, "uat", lookup).Load(&cfg); err != nil {
		t.Fatal(err)
	}
	cfg.Resolve()
	if cfg.PublicBaseURL != "https://api.uat.afrotransact.com/carrier-gateway" {
		t.Fatalf("publicBaseUrl = %q", cfg.PublicBaseURL)
	}
	env["PUBLIC_BASE_URL"] = "https://hooks.example.test"
	var explicit config.Config
	if err := pkgconfig.NewLoaderFor(carriergateway.Files, "uat", lookup).Load(&explicit); err != nil {
		t.Fatal(err)
	}
	explicit.Resolve()
	if explicit.PublicBaseURL != "https://hooks.example.test" {
		t.Fatalf("explicit PUBLIC_BASE_URL must win: %q", explicit.PublicBaseURL)
	}
}
