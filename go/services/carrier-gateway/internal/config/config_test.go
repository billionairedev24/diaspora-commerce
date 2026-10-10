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
			if cfg.Rates.Budget != 1200*time.Millisecond || cfg.Rates.QuoteTTL != 10*time.Minute || cfg.Polling.Interval != 2*time.Hour {
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
