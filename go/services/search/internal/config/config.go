// Package config is the search service configuration (config/<APP_ENV>.yaml + env overrides).
package config

import (
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// Config for the search service.
type Config struct {
	Port        int      `yaml:"port" env:"PORT"`
	DocsEnabled bool     `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	Markets     []string `yaml:"markets" env:"MARKETS"`
	// SearchProvider selects the engine: "elasticsearch" (default) or "meilisearch". The indexer must use the same one.
	SearchProvider string `yaml:"searchProvider" env:"SEARCH_PROVIDER"`
	Meilisearch    struct {
		URL    string `yaml:"url" env:"MEILISEARCH_URL"`
		APIKey string `yaml:"apiKey" env:"MEILISEARCH_API_KEY"`
	} `yaml:"meilisearch"`
	Elasticsearch struct {
		URLs     []string `yaml:"urls" env:"ES_URLS"`
		Username string   `yaml:"username" env:"ES_USERNAME"`
		Password string   `env:"ES_PASSWORD"`
		APIKey   string   `env:"ES_API_KEY"`
	} `yaml:"elasticsearch"`
	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URL"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	AIGatewayURL string `yaml:"aiGatewayUrl" env:"AI_GATEWAY_URL"`
	// Semantic is hybrid retrieval (S036): queries of MinWords+ words are embedded by ai-gateway within Timeout
	// (150 ms), with search's own client-credentials token (the content client's id and secret, Scopes ai:use).
	Semantic struct {
		Timeout  time.Duration `yaml:"timeout" env:"SEMANTIC_TIMEOUT"`
		MinWords int           `yaml:"minWords" env:"SEMANTIC_MIN_WORDS"`
		Scopes   []string      `yaml:"scopes" env:"SEMANTIC_SCOPES"`
	} `yaml:"semantic"`
	Kafka struct {
		// Brokers for search.* and ads.* events; empty disables publishing (events are counted as dropped).
		Brokers []string `yaml:"brokers" env:"KAFKA_BOOTSTRAP"`
		Buffer  int      `yaml:"buffer" env:"EVENT_BUFFER"`
	} `yaml:"kafka"`
	Synonyms struct {
		// File overrides config/synonyms.yaml (e.g. a ConfigMap mount).
		File string `yaml:"file" env:"SYNONYMS_FILE"`
		// Overwrite replaces the set on start; otherwise the file only seeds a missing set (staff edits are kept).
		Overwrite bool `yaml:"overwrite" env:"SYNONYMS_OVERWRITE"`
		// MigrateIndex switches an index created with inline synonyms to the set on start (close, update, reopen:
		// a few seconds of unavailability, so off in uat/prod — run it once in a maintenance window).
		MigrateIndex bool `yaml:"migrateIndex" env:"SYNONYMS_MIGRATE_INDEX"`
	} `yaml:"synonyms"`
	Analytics struct {
		Enabled       bool          `yaml:"enabled" env:"ANALYTICS_ENABLED"`
		FlushInterval time.Duration `yaml:"flushInterval" env:"ANALYTICS_FLUSH_INTERVAL"`
		RetentionDays int           `yaml:"retentionDays" env:"ANALYTICS_RETENTION_DAYS"`
		ClickDedupe   time.Duration `yaml:"clickDedupe" env:"CLICK_DEDUPE_WINDOW"`
	} `yaml:"analytics"`
	Ads struct {
		Enabled bool `yaml:"enabled" env:"ADS_ENABLED"`
		// Slots are the 1-based result positions ads take.
		Slots        []int         `yaml:"slots"`
		MinQuality   float64       `yaml:"minQuality" env:"ADS_MIN_QUALITY"`
		ReserveMinor int64         `yaml:"reserveMinor" env:"ADS_RESERVE_MINOR"`
		TokenKey     string        `env:"ADS_TOKEN_KEY"`
		TokenTTL     time.Duration `yaml:"tokenTtl" env:"ADS_TOKEN_TTL"`
		CacheTTL     time.Duration `yaml:"cacheTtl" env:"ADS_CACHE_TTL"`
		Content      struct {
			BaseURL      string   `yaml:"baseUrl" env:"CONTENT_URL"`
			TokenURL     string   `yaml:"tokenUrl" env:"AUTH_TOKEN_URL"`
			ClientID     string   `yaml:"clientId" env:"SEARCH_CLIENT_ID"`
			ClientSecret string   `env:"SEARCH_CLIENT_SECRET"`
			Scopes       []string `yaml:"scopes" env:"SEARCH_CLIENT_SCOPES"`
		} `yaml:"content"`
	} `yaml:"ads"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}

// Resolve fills the settings derived from the shared platform URLs; call it after Load. Explicit values win.
// Outside local, the content client's token endpoint defaults to the issuer's (AUTH_ISSUER_URL); locally an empty
// token URL means "no token", so nothing is derived there.
func (c *Config) Resolve(env string) {
	if env != pkgconfig.EnvLocal {
		c.Ads.Content.TokenURL = pkgconfig.FirstNonEmpty(c.Ads.Content.TokenURL, pkgconfig.TokenURL(c.Auth.Issuer))
	}
}
