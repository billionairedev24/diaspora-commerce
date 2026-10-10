// Package config is the authz-sync configuration (config/<APP_ENV>.yaml + env overrides).
package config

import "time"

// Config for authz-sync.
type Config struct {
	Port        int  `yaml:"port" env:"PORT"`
	DocsEnabled bool `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	Kafka       struct {
		Brokers []string `yaml:"brokers" env:"KAFKA_BOOTSTRAP"`
		Group   string   `yaml:"group" env:"KAFKA_GROUP"`
		Topics  []string `yaml:"topics" env:"KAFKA_TOPICS"`
	} `yaml:"kafka"`
	OpenFGA struct {
		APIURL string `yaml:"apiUrl" env:"OPENFGA_API_URL"`
		// APIToken is one of OpenFGA's pre-shared keys (secret; empty when authn is off, e.g. local).
		APIToken  string `env:"OPENFGA_API_TOKEN"`
		StoreName string `yaml:"storeName" env:"OPENFGA_STORE_NAME"`
		// StoreID pins the store; empty = find (or create) the store named StoreName.
		StoreID string        `yaml:"storeId" env:"OPENFGA_STORE_ID"`
		Timeout time.Duration `yaml:"timeout" env:"OPENFGA_TIMEOUT"`
		// ManageModel writes the embedded model (contracts/openfga/model.json) at start-up when the store's latest
		// model differs. Only authz-sync deploys the model.
		ManageModel bool `yaml:"manageModel" env:"OPENFGA_MANAGE_MODEL"`
	} `yaml:"openfga"`
	// RetailOrgID is AfroTransact Retail's organisation; platform.retail_buyer gets its catalog role.
	RetailOrgID string `yaml:"retailOrgId" env:"RETAIL_ORG_ID"`
	Retry       struct {
		BackoffMs    int `yaml:"backoffMs" env:"RETRY_BACKOFF_MS"`
		MaxBackoffMs int `yaml:"maxBackoffMs" env:"RETRY_MAX_BACKOFF_MS"`
	} `yaml:"retry"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
