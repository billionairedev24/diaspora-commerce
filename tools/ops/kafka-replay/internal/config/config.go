// Package config reads the connection settings from flags' defaults and the environment.
package config

import (
	"fmt"
	"os"
	"strings"
)

// Kafka is how to reach the cluster.
type Kafka struct {
	Brokers       []string
	TLS           bool
	SASLMechanism string // "", plain, scram-sha-256, scram-sha-512
	SASLUser      string
	SASLPassword  string
}

// FromEnv reads KAFKA_BOOTSTRAP (default localhost:9092), KAFKA_TLS, KAFKA_SASL_MECHANISM, KAFKA_SASL_USER and
// KAFKA_SASL_PASSWORD. brokersOverride (the --brokers flag) wins over KAFKA_BOOTSTRAP when not empty.
func FromEnv(brokersOverride string) (Kafka, error) {
	raw := brokersOverride
	if raw == "" {
		raw = os.Getenv("KAFKA_BOOTSTRAP")
	}
	if raw == "" {
		raw = "localhost:9092"
	}
	var brokers []string
	for _, b := range strings.Split(raw, ",") {
		if b = strings.TrimSpace(b); b != "" {
			brokers = append(brokers, b)
		}
	}
	cfg := Kafka{
		Brokers:       brokers,
		TLS:           truthy(os.Getenv("KAFKA_TLS")),
		SASLMechanism: strings.ToLower(os.Getenv("KAFKA_SASL_MECHANISM")),
		SASLUser:      os.Getenv("KAFKA_SASL_USER"),
		SASLPassword:  os.Getenv("KAFKA_SASL_PASSWORD"),
	}
	switch cfg.SASLMechanism {
	case "":
		if cfg.SASLUser != "" {
			cfg.SASLMechanism = "plain"
		}
	case "plain", "scram-sha-256", "scram-sha-512":
	default:
		return Kafka{}, fmt.Errorf("KAFKA_SASL_MECHANISM %q: want plain, scram-sha-256 or scram-sha-512", cfg.SASLMechanism)
	}
	if cfg.SASLMechanism != "" && (cfg.SASLUser == "" || cfg.SASLPassword == "") {
		return Kafka{}, fmt.Errorf("KAFKA_SASL_USER and KAFKA_SASL_PASSWORD are required with SASL")
	}
	return cfg, nil
}

func truthy(v string) bool {
	switch strings.ToLower(v) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}
