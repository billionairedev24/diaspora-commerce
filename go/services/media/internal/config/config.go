// Package config is the media service configuration (config/<APP_ENV>.yaml + env overrides).
package config

import (
	"strings"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// Store configures one object store.
type Store struct {
	Kind          string `yaml:"kind"` // uploadthing | s3 | azure-blob
	Name          string `yaml:"name"` // r2 | s3 | gcs | minio (s3 kind)
	Endpoint      string `yaml:"endpoint"`
	Region        string `yaml:"region"`
	Bucket        string `yaml:"bucket"`
	PathStyle     bool   `yaml:"pathStyle"`
	PublicBaseURL string `yaml:"publicBaseUrl"`
	// PublicBaseURLEnv names an env var that, when set, replaces PublicBaseURL: deployed public stores use
	// MEDIA_PUBLIC_URL, the shared media CDN URL injected by the Helm library (docs/engineering/environments.md).
	PublicBaseURLEnv string `yaml:"publicBaseUrlEnv"`
	// EndpointEnv names an env var that, when set, replaces Endpoint. Local LAN mode (make up MOBILE_HOST=lan) points
	// it at http://<LAN IP>:9000 so presigned upload and download links work from a phone on the Wi-Fi; the public
	// URLs stored with assets keep PublicBaseURL (localhost), which gateway-bff rewrites for the phone.
	EndpointEnv  string `yaml:"endpointEnv"`
	AccessKeyEnv string `yaml:"accessKeyEnv"` // names of env vars holding credentials
	SecretKeyEnv string `yaml:"secretKeyEnv"`
	Account      string `yaml:"account"` // azure
}

// ResolvedEndpoint is the value of EndpointEnv when that variable is set and not blank, else Endpoint.
func (s Store) ResolvedEndpoint(getenv func(string) string) string {
	if s.EndpointEnv != "" {
		if v := strings.TrimSpace(getenv(s.EndpointEnv)); v != "" {
			return v
		}
	}
	return s.Endpoint
}

// ResolvedPublicBaseURL is the value of PublicBaseURLEnv when that variable is set and not blank, else PublicBaseURL.
func (s Store) ResolvedPublicBaseURL(getenv func(string) string) string {
	if s.PublicBaseURLEnv != "" {
		if v := strings.TrimSpace(getenv(s.PublicBaseURLEnv)); v != "" {
			return v
		}
	}
	return s.PublicBaseURL
}

// Config for media.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	DatabaseURL string `yaml:"databaseUrl" env:"DB_URL"`
	// PublicBaseURL is media's public API URL, the base of media-hosted links (<PublicBaseURL>/v1/l/<token>). Unset,
	// it is APIBaseURL + "/media"; APIBaseURL is the shared API_BASE_URL (docs/engineering/environments.md).
	PublicBaseURL string `yaml:"publicBaseUrl" env:"PUBLIC_BASE_URL"`
	APIBaseURL    string `yaml:"apiBaseUrl" env:"API_BASE_URL"`
	UploadThing   struct {
		APIKey string `env:"UPLOADTHING_API_KEY"`
		AppID  string `yaml:"appId" env:"UPLOADTHING_APP_ID"`
		// Token is the dashboard's UPLOADTHING_TOKEN (base64 JSON with apiKey and appId); it fills APIKey / AppID
		// when those are not set.
		Token string `env:"UPLOADTHING_TOKEN"`
	} `yaml:"uploadthing"`
	Stores   map[string]Store  `yaml:"stores"`   // named stores
	Purposes map[string]string `yaml:"purposes"` // purpose → store name
	// Links bounds signed download links of private assets.
	Links struct {
		DefaultTTL time.Duration `yaml:"defaultTtl" env:"SIGNED_URL_DEFAULT_TTL"`
		// MaxLongTTL caps the links of purposes with long-lived links (purchase orders, shipping documents,
		// résumés, exports); every other purpose stays at DefaultTTL.
		MaxLongTTL time.Duration `yaml:"maxLongTtl" env:"SIGNED_URL_MAX_TTL"`
		// SigningKey (secret, ≥ 32 characters) signs media-hosted long-lived links (GET /v1/l/{token}): a long link
		// then works for maxLongTtl on every store and cloud, because each click is redirected to a fresh storage URL
		// of RedirectTTL. Without it, long links are storage-signed URLs (7 days at most on S3-compatible stores and
		// UploadThing, and shorter on AWS with IRSA credentials). PreviousKeys still verify links after a rotation.
		SigningKey   string        `env:"LINK_SIGNING_KEY"`
		PreviousKeys []string      `env:"LINK_SIGNING_KEYS_PREVIOUS"`
		RedirectTTL  time.Duration `yaml:"redirectTtl" env:"LINK_REDIRECT_TTL"`
	} `yaml:"links"`
	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URL"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
		// ForwardedAudiences are clients whose signed-in users' tokens media accepts as those users (S250: the BFF
		// relays the caller's own access token, minted for the gateway-bff audience).
		ForwardedAudiences []string `yaml:"forwardedAudiences" env:"AUTH_FORWARDED_AUDIENCES"`
	} `yaml:"auth"`
	// Moderation gates public images (product-image, storefront) before they become public (S030).
	Moderation struct {
		// MaxPixels bounds decoding (larger images are rejected).
		MaxPixels int `yaml:"maxPixels" env:"MODERATION_MAX_PIXELS"`
		// DuplicateDistance is the largest Hamming distance between perceptual hashes treated as the same photo;
		// a negative value disables duplicate detection.
		DuplicateDistance int `yaml:"duplicateDistance" env:"MODERATION_DUPLICATE_DISTANCE"`
		// AI is the moderation hook on ai-gateway (POST /v1/ai/moderate-image), called with media's own
		// client-credentials token (client media-service, scope ai:use).
		AI struct {
			Enabled bool   `yaml:"enabled" env:"MODERATION_AI_ENABLED"`
			BaseURL string `yaml:"baseUrl" env:"AI_GATEWAY_URL"`
			// FailMode is "closed" (hold images for review while the hook is unavailable) or "open" (publish them,
			// noted "AI moderation skipped").
			FailMode     string        `yaml:"failMode" env:"MODERATION_AI_FAIL_MODE"`
			Timeout      time.Duration `yaml:"timeout" env:"MODERATION_AI_TIMEOUT"`
			TokenURL     string        `yaml:"tokenUrl" env:"AUTH_TOKEN_URL"`
			ClientID     string        `yaml:"clientId" env:"MEDIA_CLIENT_ID"`
			ClientSecret string        `env:"MEDIA_CLIENT_SECRET"`
			Scopes       []string      `yaml:"scopes" env:"MEDIA_CLIENT_SCOPES"`
		} `yaml:"ai"`
	} `yaml:"moderation"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}

// FailOpen reports whether images are published while the AI hook is unavailable.
func (c Config) FailOpen() bool {
	return strings.EqualFold(strings.TrimSpace(c.Moderation.AI.FailMode), "open")
}

// Resolve fills the settings derived from the shared platform URLs; call it after Load. Explicit values win.
// Outside local, the AI client's token endpoint defaults to the issuer's; locally an empty token URL means "no token".
func (c *Config) Resolve(env string) {
	c.PublicBaseURL = pkgconfig.FirstNonEmpty(c.PublicBaseURL, pkgconfig.JoinURL(c.APIBaseURL, "/media"))
	if env != pkgconfig.EnvLocal {
		c.Moderation.AI.TokenURL = pkgconfig.FirstNonEmpty(c.Moderation.AI.TokenURL, pkgconfig.TokenURL(c.Auth.Issuer))
	}
}
