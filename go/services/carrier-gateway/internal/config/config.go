// Package config is the carrier-gateway configuration (config/<APP_ENV>.yaml + env overrides). Carrier secrets
// come only from environment variables; endpoint paths and API versions live in YAML so they change without code.
package config

import (
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/accounts"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/dhl"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/fedex"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/ups"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/usps"
)

// Config for carrier-gateway.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL string `yaml:"databaseUrl" env:"DB_URL"`
	RedisURL    string `yaml:"redisUrl" env:"REDIS_URL"`
	// PublicBaseURL is where carriers reach our webhooks (UPS Track Alert subscriptions need it). Unset, it is
	// APIBaseURL + "/carrier-gateway"; APIBaseURL is the shared API_BASE_URL (docs/engineering/environments.md).
	PublicBaseURL string `yaml:"publicBaseUrl" env:"PUBLIC_BASE_URL"`
	APIBaseURL    string `yaml:"apiBaseUrl" env:"API_BASE_URL"`

	Kafka struct {
		Enabled bool     `yaml:"enabled" env:"KAFKA_ENABLED"`
		Brokers []string `yaml:"brokers" env:"KAFKA_BROKERS"`
	} `yaml:"kafka"`
	Rates struct {
		Budget   time.Duration `yaml:"budget" env:"RATE_BUDGET"`
		QuoteTTL time.Duration `yaml:"quoteTtl" env:"QUOTE_TTL"`
	} `yaml:"rates"`
	Outbox struct {
		Interval time.Duration `yaml:"interval"`
		Batch    int           `yaml:"batch"`
	} `yaml:"outbox"`
	Polling struct {
		Enabled    bool          `yaml:"enabled" env:"POLLING_ENABLED"`
		Carriers   []string      `yaml:"carriers"`
		Tick       time.Duration `yaml:"tick"`
		Interval   time.Duration `yaml:"interval"`
		MaxBackoff time.Duration `yaml:"maxBackoff"`
		Lease      time.Duration `yaml:"lease"`
		Batch      int           `yaml:"batch"`
	} `yaml:"polling"`
	LabelStore struct {
		Kind string `yaml:"kind" env:"LABEL_STORE"` // fs | s3
		Dir  string `yaml:"dir" env:"LABEL_DIR"`
		S3   struct {
			Endpoint  string `yaml:"endpoint" env:"S3_ENDPOINT"`
			Region    string `yaml:"region" env:"S3_REGION"`
			Bucket    string `yaml:"bucket" env:"S3_BUCKET"`
			AccessKey string `yaml:"-" env:"S3_ACCESS_KEY_ID"`
			SecretKey string `yaml:"-" env:"S3_SECRET_ACCESS_KEY"`
			PathStyle bool   `yaml:"pathStyle" env:"S3_PATH_STYLE"`
		} `yaml:"s3"`
	} `yaml:"labelStore"`
	CarrierHTTP struct {
		Timeout    time.Duration `yaml:"timeout"`
		MaxRetries int           `yaml:"maxRetries"`
	} `yaml:"carrierHttp"`

	USPS struct {
		Enabled      bool       `yaml:"enabled" env:"USPS_ENABLED"`
		BaseURL      string     `yaml:"baseUrl" env:"USPS_BASE_URL"`
		ClientID     string     `yaml:"-" env:"USPS_CLIENT_ID"`
		ClientSecret string     `yaml:"-" env:"USPS_CLIENT_SECRET"`
		PaymentToken string     `yaml:"-" env:"USPS_PAYMENT_TOKEN"`
		MailClasses  []string   `yaml:"mailClasses"`
		PriceType    string     `yaml:"priceType"`
		ImageType    string     `yaml:"imageType"`
		Paths        usps.Paths `yaml:"paths"`
	} `yaml:"usps"`
	UPS struct {
		Enabled           bool      `yaml:"enabled" env:"UPS_ENABLED"`
		BaseURL           string    `yaml:"baseUrl" env:"UPS_BASE_URL"`
		ClientID          string    `yaml:"-" env:"UPS_CLIENT_ID"`
		ClientSecret      string    `yaml:"-" env:"UPS_CLIENT_SECRET"`
		Account           string    `yaml:"-" env:"UPS_ACCOUNT"`
		WebhookCredential string    `yaml:"-" env:"UPS_WEBHOOK_CREDENTIAL"`
		Paths             ups.Paths `yaml:"paths"`
	} `yaml:"ups"`
	FedEx struct {
		Enabled         bool        `yaml:"enabled" env:"FEDEX_ENABLED"`
		BaseURL         string      `yaml:"baseUrl" env:"FEDEX_BASE_URL"`
		ClientID        string      `yaml:"-" env:"FEDEX_CLIENT_ID"`
		ClientSecret    string      `yaml:"-" env:"FEDEX_CLIENT_SECRET"`
		Account         string      `yaml:"-" env:"FEDEX_ACCOUNT"`
		WebhookSecret   string      `yaml:"-" env:"FEDEX_WEBHOOK_SECRET"`
		SignatureHeader string      `yaml:"signatureHeader"`
		ImageType       string      `yaml:"imageType"`
		Paths           fedex.Paths `yaml:"paths"`
	} `yaml:"fedex"`
	DHL struct {
		Enabled         bool      `yaml:"enabled" env:"DHL_ENABLED"`
		BaseURL         string    `yaml:"baseUrl" env:"DHL_BASE_URL"`
		Username        string    `yaml:"-" env:"DHL_USERNAME"`
		Password        string    `yaml:"-" env:"DHL_PASSWORD"`
		Account         string    `yaml:"-" env:"DHL_ACCOUNT"`
		WebhookSecret   string    `yaml:"-" env:"DHL_WEBHOOK_SECRET"`
		SignatureHeader string    `yaml:"signatureHeader"`
		LabelFormat     string    `yaml:"labelFormat"`
		Paths           dhl.Paths `yaml:"paths"`
	} `yaml:"dhl"`

	Accounts accounts.Config `yaml:"accounts"`

	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URL"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}

// Resolve fills the settings derived from the shared platform URLs; call it after Load. Explicit values win.
func (c *Config) Resolve() {
	c.PublicBaseURL = pkgconfig.FirstNonEmpty(c.PublicBaseURL, pkgconfig.JoinURL(c.APIBaseURL, "/carrier-gateway"))
}
