// Package config is the storefront configuration (config/<APP_ENV>.yaml + env overrides).
package config

// Plan limits keyed by plan code.
type Plan struct {
	MaxGallery int `yaml:"maxGallery"`
}

// Config for storefront.
type Config struct {
	Port          int             `yaml:"port" env:"PORT"`
	DocsEnabled   bool            `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL   string          `yaml:"databaseUrl" env:"DB_URL"`
	PublicBaseURL string          `yaml:"publicBaseUrl" env:"PUBLIC_BASE_URL"`
	MaxSections   int             `yaml:"maxSections"`
	MaxFeatured   int             `yaml:"maxFeatured"`
	MaxTagline    int             `yaml:"maxTagline"`
	Plans         map[string]Plan `yaml:"plans"`
	Cloudflare    struct {
		ZoneID string `yaml:"zoneId" env:"CLOUDFLARE_ZONE_ID"`
		Token  string `env:"CLOUDFLARE_API_TOKEN"`
	} `yaml:"cloudflare"`
	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URI"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
