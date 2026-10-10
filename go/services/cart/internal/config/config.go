// Package config is the cart configuration (config/<APP_ENV>.yaml + env overrides).
package config

// Config for cart.
type Config struct {
	Port         int               `yaml:"port" env:"PORT"`
	DocsEnabled  bool              `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL  string            `yaml:"databaseUrl" env:"DB_URL"`
	RedisURL     string            `yaml:"redisUrl" env:"REDIS_URL"`
	GuestTTLDays int               `yaml:"guestTtlDays" env:"GUEST_TTL_DAYS"`
	CatalogURL   string            `yaml:"catalogUrl" env:"CATALOG_URL"`
	InventoryURL string            `yaml:"inventoryUrl" env:"INVENTORY_URL"`
	OffersURL    string            `yaml:"offersUrl" env:"OFFERS_URL"`
	RetailOrgID  string            `yaml:"retailOrgId" env:"RETAIL_ORG_ID"`
	SellerNames  map[string]string `yaml:"sellerNames"`
	Auth         struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URL"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}
