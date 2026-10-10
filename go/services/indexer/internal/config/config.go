// Package config is the indexer configuration (config/<APP_ENV>.yaml + env overrides).
package config

import "time"

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
	// SynonymsSet is the Elasticsearch synonyms set new indices reference (seeded by search-service).
	SynonymsSet string            `yaml:"synonymsSet" env:"SYNONYMS_SET"`
	CatalogURL  string            `yaml:"catalogUrl" env:"CATALOG_URL"`
	SellerNames map[string]string `yaml:"sellerNames"`
	// SellerDirectory is seller-service's organisation directory (S213), read with a client-credentials token of
	// client indexer-service (scope sellers:directory) to warm seller names before events are consumed.
	SellerDirectory struct {
		Enabled      bool     `yaml:"enabled" env:"SELLER_DIRECTORY_ENABLED"`
		BaseURL      string   `yaml:"baseUrl" env:"SELLER_URL"`
		TokenURL     string   `yaml:"tokenUrl" env:"AUTH_TOKEN_URL"`
		ClientID     string   `yaml:"clientId" env:"INDEXER_CLIENT_ID"`
		ClientSecret string   `env:"INDEXER_CLIENT_SECRET"`
		Scopes       []string `yaml:"scopes" env:"INDEXER_CLIENT_SCOPES"`
		PageSize     int      `yaml:"pageSize" env:"SELLER_DIRECTORY_PAGE_SIZE"`
		// WarmTimeout bounds the start-up warm (with retries); after it the consumer starts anyway and already
		// indexed names are preserved for sellers not yet known.
		WarmTimeout     time.Duration `yaml:"warmTimeout" env:"SELLER_DIRECTORY_WARM_TIMEOUT"`
		RefreshInterval time.Duration `yaml:"refreshInterval" env:"SELLER_DIRECTORY_REFRESH_INTERVAL"`
	} `yaml:"sellerDirectory"`
	Retry struct {
		Attempts  int `yaml:"attempts" env:"RETRY_ATTEMPTS"`
		BackoffMs int `yaml:"backoffMs" env:"RETRY_BACKOFF_MS"`
	} `yaml:"retry"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
