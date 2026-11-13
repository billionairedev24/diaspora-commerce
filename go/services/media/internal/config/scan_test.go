package config_test

import (
	"strings"
	"testing"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/config"
)

func TestProdRefusesDisabledOrFailOpenScanning(t *testing.T) {
	cases := map[string]func(c *config.Config){
		"scanner off":        func(c *config.Config) { c.Scan.Provider = "none" },
		"unset provider":     func(c *config.Config) { c.Scan.Provider = "" },
		"documents open":     func(c *config.Config) { c.Scan.Documents = "best-effort" },
		"clamd without addr": func(c *config.Config) { c.Scan.Address = "" },
		"http not https":     func(c *config.Config) { c.Scan.Provider, c.Scan.URL = "http", "http://scan.local/v1" },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			c := loadEnv(t, pkgconfig.EnvProd)
			mutate(&c)
			if err := c.Validate(pkgconfig.EnvProd, lookup(prodEnv())); err == nil || !strings.Contains(err.Error(), "scan") {
				t.Fatalf("want a scan error, got %v", err)
			}
		})
	}
}

func TestProdAcceptsHostedScanner(t *testing.T) {
	c := loadEnv(t, pkgconfig.EnvProd)
	c.Scan.Provider, c.Scan.URL = "http", "https://scan.example.com/v1/scan"
	if err := c.Validate(pkgconfig.EnvProd, lookup(prodEnv())); err != nil {
		t.Fatalf("hosted scanner refused: %v", err)
	}
}

func TestLocalMayDisableScanning(t *testing.T) {
	c := loadEnv(t, pkgconfig.EnvLocal)
	if c.Scan.Provider != "none" {
		t.Fatalf("local scan provider = %q", c.Scan.Provider)
	}
	if err := c.Validate(pkgconfig.EnvLocal, lookup(nil)); err != nil {
		t.Fatal(err)
	}
}
