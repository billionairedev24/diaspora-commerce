// Package config is the search service configuration (config/<APP_ENV>.yaml + env overrides).
package config

// Config for the search service.
type Config struct {
	Port          int      `yaml:"port" env:"PORT"`
	DocsEnabled   bool     `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	Markets       []string `yaml:"markets" env:"MARKETS"`
	Elasticsearch struct {
		URLs     []string `yaml:"urls" env:"ES_URLS"`
		Username string   `yaml:"username" env:"ES_USERNAME"`
		Password string   `env:"ES_PASSWORD"`
		APIKey   string   `env:"ES_API_KEY"`
	} `yaml:"elasticsearch"`
	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URI"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	AIGatewayURL string `yaml:"aiGatewayUrl" env:"AI_GATEWAY_URL"`
	Telemetry    struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
