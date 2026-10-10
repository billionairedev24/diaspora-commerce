package config_test

import (
	"testing"
	"testing/fstest"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

type sample struct {
	Port    int           `yaml:"port" env:"PORT"`
	Name    string        `yaml:"name" env:"NAME"`
	Timeout time.Duration `yaml:"timeout" env:"TIMEOUT"`
	Nested  struct {
		Hosts []string `yaml:"hosts" env:"HOSTS"`
	} `yaml:"nested"`
}

func TestLoadYAMLThenEnvOverrides(t *testing.T) {
	fsys := fstest.MapFS{"config/dev.yaml": {Data: []byte("port: 8080\nname: search\ntimeout: 2s\nnested:\n  hosts: [a]\n")}}
	env := map[string]string{"PORT": "9000", "HOSTS": "x, y"}
	l := config.NewLoaderFor(fsys, "dev", func(k string) (string, bool) { v, ok := env[k]; return v, ok })

	var c sample
	if err := l.Load(&c); err != nil {
		t.Fatal(err)
	}
	if c.Port != 9000 || c.Name != "search" || c.Timeout != 2*time.Second {
		t.Fatalf("unexpected config %+v", c)
	}
	if len(c.Nested.Hosts) != 2 || c.Nested.Hosts[1] != "y" {
		t.Fatalf("env slice override failed: %v", c.Nested.Hosts)
	}
}

func TestRejectsUnknownEnvironment(t *testing.T) {
	l := config.NewLoaderFor(nil, "staging", func(string) (string, bool) { return "", false })
	var c sample
	if err := l.Load(&c); err == nil {
		t.Fatal("expected error for unknown env")
	}
}
