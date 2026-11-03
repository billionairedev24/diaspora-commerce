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

// Google Places (New) is the only autocomplete provider when a key is configured (GOOGLE_PLACES_API_KEY or
// GOOGLE_MAPS_API_KEY); Nominatim and fixtures answer only without one.
func TestAddressProvidersPreferGoogleWhenAKeyIsSet(t *testing.T) {
	for _, env := range []string{"local", "dev", "uat", "prod"} {
		t.Run(env, func(t *testing.T) {
			t.Setenv("APP_ENV", env)
			t.Setenv("GOOGLE_PLACES_API_KEY", "")
			t.Setenv("GOOGLE_MAPS_API_KEY", "")
			var cfg config.Config
			if err := pkgconfig.NewLoader(geo.Files).Load(&cfg); err != nil {
				t.Fatal(err)
			}
			for _, p := range cfg.AddressProviders() {
				if p == config.ProviderGooglePlaces {
					t.Fatalf("no key: google-places must be skipped, got %v", cfg.AddressProviders())
				}
			}
			if len(cfg.AddressProviders()) == 0 || cfg.ReverseGeocoders()[0] == config.ProviderGoogleGeocoding {
				t.Fatalf("no key: %v / %v", cfg.AddressProviders(), cfg.ReverseGeocoders())
			}
			for _, key := range []string{"GOOGLE_PLACES_API_KEY", "GOOGLE_MAPS_API_KEY"} {
				t.Setenv("GOOGLE_PLACES_API_KEY", "")
				t.Setenv("GOOGLE_MAPS_API_KEY", "")
				t.Setenv(key, "AIza-test")
				var withKey config.Config
				if err := pkgconfig.NewLoader(geo.Files).Load(&withKey); err != nil {
					t.Fatal(err)
				}
				if got := withKey.AddressProviders(); len(got) != 1 || got[0] != config.ProviderGooglePlaces || withKey.GoogleKey() != "AIza-test" {
					t.Fatalf("%s: providers %v key %q", key, got, withKey.GoogleKey())
				}
				if got := withKey.ReverseGeocoders(); got[0] != config.ProviderGoogleGeocoding || len(got) < 2 {
					t.Fatalf("%s: reverse %v", key, got)
				}
			}
		})
	}
}
