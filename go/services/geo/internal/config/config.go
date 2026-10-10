// Package config is the geo service configuration (config/<APP_ENV>.yaml + env overrides).
package config

// Config for geo.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL string `yaml:"databaseUrl" env:"DB_URL"`
	// Providers in priority order: google-places, osm-nominatim, fixture. Google is skipped without a key.
	Providers []string `yaml:"providers" env:"GEO_PROVIDERS"`
	// ReverseProviders in priority order: google-geocoding, osm-nominatim, fixture. Google is skipped without a key.
	ReverseProviders []string `yaml:"reverseProviders" env:"GEO_REVERSE_PROVIDERS"`
	Google           struct {
		APIKey string `env:"GOOGLE_PLACES_API_KEY"`
	} `yaml:"google"`
	Nominatim struct {
		URL       string `yaml:"url" env:"NOMINATIM_URL"`
		UserAgent string `yaml:"userAgent" env:"NOMINATIM_USER_AGENT"`
	} `yaml:"nominatim"`
	Smarty struct {
		AuthID    string `env:"SMARTY_AUTH_ID"`
		AuthToken string `env:"SMARTY_AUTH_TOKEN"`
	} `yaml:"smarty"`
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
