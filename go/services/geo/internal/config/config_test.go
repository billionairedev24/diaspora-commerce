package config_test

import (
	"testing"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/config"
)

func TestRoutingConfigPerEnvironment(t *testing.T) {
	for env, providers := range map[string]int{"local": 0, "dev": 2, "uat": 2, "prod": 2} {
		t.Setenv("APP_ENV", env)
		var cfg config.Config
		if err := pkgconfig.NewLoader(geo.Files).Load(&cfg); err != nil {
			t.Fatalf("%s: %v", env, err)
		}
		if len(cfg.Routing.Providers) != providers || cfg.Routing.Timeout != 2*time.Second ||
			cfg.Routing.CacheTTL != 24*time.Hour || cfg.Routing.DetourFactor != 1.3 {
			t.Fatalf("%s: %+v", env, cfg.Routing)
		}
	}
	t.Setenv("APP_ENV", "prod")
	t.Setenv("GEO_ROUTING_PROVIDERS", "osrm")
	t.Setenv("OSRM_URL", "http://osrm:5000")
	var cfg config.Config
	if err := pkgconfig.NewLoader(geo.Files).Load(&cfg); err != nil {
		t.Fatal(err)
	}
	if len(cfg.Routing.Providers) != 1 || cfg.Routing.OSRMURL != "http://osrm:5000" {
		t.Fatalf("env override: %+v", cfg.Routing)
	}
}
