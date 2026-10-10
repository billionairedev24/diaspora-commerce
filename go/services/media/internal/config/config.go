// Package config is the media service configuration (config/<APP_ENV>.yaml + env overrides).
package config

import "time"

// Store configures one object store.
type Store struct {
	Kind          string `yaml:"kind"` // uploadthing | s3 | azure-blob
	Name          string `yaml:"name"` // r2 | s3 | gcs | minio (s3 kind)
	Endpoint      string `yaml:"endpoint"`
	Region        string `yaml:"region"`
	Bucket        string `yaml:"bucket"`
	PathStyle     bool   `yaml:"pathStyle"`
	PublicBaseURL string `yaml:"publicBaseUrl"`
	AccessKeyEnv  string `yaml:"accessKeyEnv"` // names of env vars holding credentials
	SecretKeyEnv  string `yaml:"secretKeyEnv"`
	Account       string `yaml:"account"` // azure
}

// Config for media.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL string `yaml:"databaseUrl" env:"DB_URL"`
	UploadThing struct {
		APIKey string `env:"UPLOADTHING_API_KEY"`
		AppID  string `yaml:"appId" env:"UPLOADTHING_APP_ID"`
	} `yaml:"uploadthing"`
	Stores   map[string]Store  `yaml:"stores"`   // named stores
	Purposes map[string]string `yaml:"purposes"` // purpose → store name
	// Links bounds signed download links of private assets.
	Links struct {
		DefaultTTL time.Duration `yaml:"defaultTtl" env:"SIGNED_URL_DEFAULT_TTL"`
		// MaxLongTTL caps the links of purposes with long-lived links (purchase orders, shipping documents,
		// résumés, exports); every other purpose stays at DefaultTTL.
		MaxLongTTL time.Duration `yaml:"maxLongTtl" env:"SIGNED_URL_MAX_TTL"`
	} `yaml:"links"`
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
