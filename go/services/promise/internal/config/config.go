// Package config is the promise configuration (config/<APP_ENV>.yaml + env overrides).
package config

import (
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/nodes"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

// ServiceLevel configures one carrier service level.
type ServiceLevel struct {
	MaxDays          int  `yaml:"maxDays"`
	SaturdayDelivery bool `yaml:"saturdayDelivery"`
}

// Config for promise.
type Config struct {
	Port        int           `yaml:"port" env:"PORT"`
	DocsEnabled bool          `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	RedisURL    string        `yaml:"redisUrl" env:"REDIS_URL"`
	SnapshotTTL time.Duration `yaml:"snapshotTtl" env:"SNAPSHOT_TTL"`
	MaxOffers   int           `yaml:"maxOffers" env:"MAX_OFFERS"`
	Data        struct {
		Regions string `yaml:"regions"`
		Transit string `yaml:"transit"`
	} `yaml:"data"`
	ServiceLevels map[string]ServiceLevel `yaml:"serviceLevels"`
	Seller        struct {
		Cutoff           string                `yaml:"cutoff"`
		PickupReadyHours int                   `yaml:"pickupReadyHours"`
		PickupHours      []domain.OpeningHours `yaml:"pickupHours"`
		MaxHandlingDays  int                   `yaml:"maxHandlingDays"`
	} `yaml:"seller"`
	FulfilmentCentres []nodes.FCConfig `yaml:"fulfilmentCentres"`
	Auth              struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URI"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
