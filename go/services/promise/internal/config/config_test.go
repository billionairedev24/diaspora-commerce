package config_test

import (
	"testing"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/nodes"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/ziptable"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/config"
)

func TestEveryEnvironmentLoads(t *testing.T) {
	for _, env := range []string{"local", "dev", "uat", "prod"} {
		t.Run(env, func(t *testing.T) {
			var cfg config.Config
			if err := pkgconfig.NewLoaderFor(promise.Files, env, func(string) (string, bool) { return "", false }).Load(&cfg); err != nil {
				t.Fatal(err)
			}
			if cfg.SnapshotTTL != 24*time.Hour || len(cfg.ServiceLevels) != 3 || !cfg.ServiceLevels["OVERNIGHT"].SaturdayDelivery {
				t.Fatalf("%+v", cfg)
			}
			dir, err := nodes.New(cfg.FulfilmentCentres)
			if err != nil || len(dir.All()) != 3 {
				t.Fatalf("FCs: %v", err)
			}
			if _, err := ziptable.Load(promise.Files, cfg.Data.Regions, cfg.Data.Transit); err != nil {
				t.Fatal(err)
			}
			for _, fc := range dir.All() {
				if fc.PickupHours[0].Open == "" || len(fc.SameDayZIP3) == 0 {
					t.Fatalf("FC %s incomplete", fc.ID)
				}
			}
			if env == "prod" && cfg.DocsEnabled {
				t.Fatal("docs off in prod")
			}
		})
	}
}
