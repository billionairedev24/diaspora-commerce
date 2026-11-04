package config_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/config"
)

func TestFromEnv(t *testing.T) {
	t.Setenv("KAFKA_BOOTSTRAP", "a:9092, b:9092")
	t.Setenv("KAFKA_TLS", "true")
	t.Setenv("KAFKA_SASL_USER", "svc")
	t.Setenv("KAFKA_SASL_PASSWORD", "pw")
	cfg, err := config.FromEnv("")
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Brokers) != 2 || cfg.Brokers[1] != "b:9092" || !cfg.TLS || cfg.SASLMechanism != "plain" {
		t.Fatalf("cfg = %+v", cfg)
	}
	override, _ := config.FromEnv("c:9092")
	if len(override.Brokers) != 1 || override.Brokers[0] != "c:9092" {
		t.Fatalf("flag should win: %+v", override)
	}
}

func TestFromEnvDefaultsAndValidation(t *testing.T) {
	t.Setenv("KAFKA_BOOTSTRAP", "")
	cfg, err := config.FromEnv("")
	if err != nil || cfg.Brokers[0] != "localhost:9092" || cfg.SASLMechanism != "" {
		t.Fatalf("cfg = %+v err = %v", cfg, err)
	}
	t.Setenv("KAFKA_SASL_MECHANISM", "kerberos")
	if _, err := config.FromEnv(""); err == nil {
		t.Fatal("unknown mechanism accepted")
	}
	t.Setenv("KAFKA_SASL_MECHANISM", "scram-sha-512")
	if _, err := config.FromEnv(""); err == nil {
		t.Fatal("SASL without credentials accepted")
	}
}
