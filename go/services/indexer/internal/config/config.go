// Package config is the indexer configuration (config/<APP_ENV>.yaml + env overrides).
package config

// Config for the indexer.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	Market      string `yaml:"market" env:"MARKET"`
	Kafka       struct {
		Brokers []string `yaml:"brokers" env:"KAFKA_BOOTSTRAP"`
		Group   string   `yaml:"group" env:"KAFKA_GROUP"`
		Topics  []string `yaml:"topics" env:"KAFKA_TOPICS"`
	} `yaml:"kafka"`
	Elasticsearch struct {
		URLs     []string `yaml:"urls" env:"ES_URLS"`
		Username string   `yaml:"username" env:"ES_USERNAME"`
		Password string   `env:"ES_PASSWORD"`
		APIKey   string   `env:"ES_API_KEY"`
		Refresh  bool     `yaml:"refresh" env:"ES_REFRESH"`
	} `yaml:"elasticsearch"`
	CatalogURL  string            `yaml:"catalogUrl" env:"CATALOG_URL"`
	SellerNames map[string]string `yaml:"sellerNames"`
	Retry       struct {
		Attempts  int `yaml:"attempts" env:"RETRY_ATTEMPTS"`
		BackoffMs int `yaml:"backoffMs" env:"RETRY_BACKOFF_MS"`
	} `yaml:"retry"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
