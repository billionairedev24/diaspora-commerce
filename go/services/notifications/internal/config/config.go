// Package config is the notifications service configuration (config/<APP_ENV>.yaml + env overrides).
// Secrets are never in YAML: they come from env vars only (fields without a yaml tag).
package config

import (
	"net/url"
	"slices"
	"strings"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// Routes are ordered provider names per market ("default" is the fallback) for one channel.
type Routes map[string][]string

// Config for the notifications service.
type Config struct {
	Port        int  `yaml:"port" env:"PORT"`
	DocsEnabled bool `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	// PublicBaseURL is this service's public URL (webhook signatures, unsubscribe links). Unset, it is
	// APIBaseURL + "/notifications"; APIBaseURL is the shared API_BASE_URL (docs/engineering/environments.md).
	PublicBaseURL string `yaml:"publicBaseUrl" env:"PUBLIC_BASE_URL"`
	APIBaseURL    string `yaml:"apiBaseUrl" env:"API_BASE_URL"`
	Database      struct {
		URL     string `yaml:"url" env:"DB_URL"`
		Migrate bool   `yaml:"migrate" env:"DB_MIGRATE"`
	} `yaml:"database"`
	Kafka struct {
		Enabled bool     `yaml:"enabled" env:"KAFKA_ENABLED"`
		Brokers []string `yaml:"brokers" env:"KAFKA_BROKERS"`
		// Topics maps logical topic names (identity, orders, sellerApplications, sellerCompliance, payouts,
		// bookings, content, inventory, payments, trustOps, retail) to addresses; missing names default to the
		// AsyncAPI channel addresses.
		Topics map[string]string `yaml:"topics"`
	} `yaml:"kafka"`
	// Identity resolves recipients by user id / org id for events that carry no contact details.
	Identity struct {
		Enabled      bool          `yaml:"enabled" env:"IDENTITY_ENABLED"`
		BaseURL      string        `yaml:"baseUrl" env:"IDENTITY_URL"`
		TokenURL     string        `yaml:"tokenUrl" env:"AUTH_TOKEN_URL"`
		ClientID     string        `yaml:"clientId" env:"IDENTITY_CLIENT_ID"`
		ClientSecret string        `env:"NOTIFICATIONS_CLIENT_SECRET"`
		Timeout      time.Duration `yaml:"timeout" env:"IDENTITY_TIMEOUT"`
		CacheTTL     time.Duration `yaml:"cacheTtl" env:"IDENTITY_CACHE_TTL"`
		NotFoundTTL  time.Duration `yaml:"notFoundTtl"`
		CacheMax     int           `yaml:"cacheMaxEntries"`
	} `yaml:"identity"`
	// Links are the public base URLs templates link to: the shared PUBLIC_WEB_URL and SELLER_CENTER_URL. Provider
	// Hub is served by the Seller Center app, so ProviderHub defaults to SellerCenter (PROVIDER_HUB_URL overrides).
	Links struct {
		Storefront   string `yaml:"storefront" env:"PUBLIC_WEB_URL"`
		SellerCenter string `yaml:"sellerCenter" env:"SELLER_CENTER_URL"`
		ProviderHub  string `yaml:"providerHub" env:"PROVIDER_HUB_URL"`
	} `yaml:"links"`
	// Attachments are email attachments fetched from signed media links at send time (S243). Only hosts in the
	// allow-list (the media base URL's host plus allowedHosts, e.g. the object-storage host that signs the links)
	// are fetched; files over maxBytes, or over maxTotalBytes per email, stay links. Disabled = links only.
	Attachments struct {
		Enabled      bool     `yaml:"enabled" env:"ATTACHMENTS_ENABLED"`
		MediaBaseURL string   `yaml:"mediaBaseUrl" env:"MEDIA_BASE_URL"`
		AllowedHosts []string `yaml:"allowedHosts" env:"ATTACHMENTS_ALLOWED_HOSTS"`
		// MediaPublicURL is the shared public media CDN (MEDIA_PUBLIC_URL); its host is added to AllowedHosts.
		MediaPublicURL string `yaml:"mediaPublicUrl" env:"MEDIA_PUBLIC_URL"`
		// MediaLinkBaseURL is media's public URL (default <API_BASE_URL>/media): long-lived document links
		// (<base>/v1/l/<token>, S250) are fetched from it and redirect to object storage. Only that path prefix is
		// added to the allow-list, not the whole API host.
		MediaLinkBaseURL string        `yaml:"mediaLinkBaseUrl" env:"MEDIA_LINK_BASE_URL"`
		MaxBytes         int64         `yaml:"maxBytes" env:"ATTACHMENTS_MAX_BYTES"`
		MaxTotalBytes    int64         `yaml:"maxTotalBytes" env:"ATTACHMENTS_MAX_TOTAL_BYTES"`
		Timeout          time.Duration `yaml:"timeout" env:"ATTACHMENTS_TIMEOUT"`
	} `yaml:"attachments"`
	Auth struct {
		Enabled  bool   `yaml:"enabled" env:"AUTH_ENABLED"`
		Issuer   string `yaml:"issuer" env:"AUTH_ISSUER_URL"`
		Audience string `yaml:"audience" env:"AUTH_AUDIENCE"`
	} `yaml:"auth"`
	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
	Dispatcher struct {
		Enabled      bool          `yaml:"enabled" env:"DISPATCHER_ENABLED"`
		Batch        int           `yaml:"batch" env:"DISPATCHER_BATCH"`
		Concurrency  int           `yaml:"concurrency" env:"DISPATCHER_CONCURRENCY"`
		Lease        time.Duration `yaml:"lease" env:"DISPATCHER_LEASE"`
		PollInterval time.Duration `yaml:"pollInterval" env:"DISPATCHER_POLL_INTERVAL"`
		MaxAttempts  int           `yaml:"maxAttempts" env:"DISPATCHER_MAX_ATTEMPTS"`
		BackoffBase  time.Duration `yaml:"backoffBase" env:"DISPATCHER_BACKOFF_BASE"`
		BackoffMax   time.Duration `yaml:"backoffMax" env:"DISPATCHER_BACKOFF_MAX"`
	} `yaml:"dispatcher"`
	Routing struct {
		SendTimeout time.Duration `yaml:"sendTimeout" env:"SEND_TIMEOUT"`
		Email       Routes        `yaml:"email"`
		SMS         Routes        `yaml:"sms"`
		Push        Routes        `yaml:"push"`
		Chat        Routes        `yaml:"chat"`
	} `yaml:"routing"`
	Breaker struct {
		FailureThreshold int           `yaml:"failureThreshold" env:"BREAKER_FAILURE_THRESHOLD"`
		CoolDown         time.Duration `yaml:"coolDown" env:"BREAKER_COOL_DOWN"`
		HalfOpenProbes   int           `yaml:"halfOpenProbes" env:"BREAKER_HALF_OPEN_PROBES"`
	} `yaml:"breaker"`
	QuietHours struct {
		Start string `yaml:"start" env:"QUIET_HOURS_START"`
		End   string `yaml:"end" env:"QUIET_HOURS_END"`
	} `yaml:"quietHours"`
	MarketTimeZones map[string]string `yaml:"marketTimeZones"`
	Email           struct {
		From              string `yaml:"from" env:"EMAIL_FROM"`
		ReplyTo           string `yaml:"replyTo" env:"EMAIL_REPLY_TO"`
		UnsubscribeMailto string `yaml:"unsubscribeMailto" env:"EMAIL_UNSUBSCRIBE_MAILTO"`
	} `yaml:"email"`
	UnsubscribeSigningKey string `env:"UNSUBSCRIBE_SIGNING_KEY"`
	Webhooks              struct {
		Verify            bool   `yaml:"verify" env:"WEBHOOKS_VERIFY"`
		ResendSecret      string `env:"RESEND_WEBHOOK_SECRET"`
		SendGridPublicKey string `env:"SENDGRID_WEBHOOK_PUBLIC_KEY"`
	} `yaml:"webhooks"`
	SMSCompliance struct {
		Help        string `yaml:"help"`
		Stop        string `yaml:"stop"`
		Start       string `yaml:"start"`
		TextVersion string `yaml:"textVersion"`
	} `yaml:"smsCompliance"`
	TestSend struct {
		// LocalEmail/LocalPhone are the test-send targets when auth is disabled (local profile only).
		LocalEmail string `yaml:"localEmail"`
		LocalPhone string `yaml:"localPhone"`
	} `yaml:"testSend"`
	Providers Providers `yaml:"providers"`
}

// Providers configure each adapter. Only providers named in a route are constructed.
type Providers struct {
	Resend struct {
		APIKey  string `env:"RESEND_API_KEY"`
		BaseURL string `yaml:"baseUrl"`
	} `yaml:"resend"`
	SendGrid struct {
		APIKey  string `env:"SENDGRID_API_KEY"`
		BaseURL string `yaml:"baseUrl"`
	} `yaml:"sendgrid"`
	SES struct {
		Region           string `yaml:"region" env:"SES_REGION"`
		ConfigurationSet string `yaml:"configurationSet"`
	} `yaml:"ses"`
	SMTP struct {
		Host     string `yaml:"host" env:"SMTP_HOST"`
		Port     int    `yaml:"port" env:"SMTP_PORT"`
		Username string `yaml:"username" env:"SMTP_USERNAME"`
		Password string `env:"SMTP_PASSWORD"`
		StartTLS bool   `yaml:"startTls"`
	} `yaml:"smtp"`
	Twilio struct {
		AccountSID          string `env:"TWILIO_ACCOUNT_SID"`
		AuthToken           string `env:"TWILIO_AUTH_TOKEN"`
		From                string `yaml:"from" env:"TWILIO_FROM"`
		MessagingServiceSID string `yaml:"messagingServiceSid" env:"TWILIO_MESSAGING_SERVICE_SID"`
		BaseURL             string `yaml:"baseUrl"`
	} `yaml:"twilio"`
	Vonage struct {
		APIKey    string `env:"VONAGE_API_KEY"`
		APISecret string `env:"VONAGE_API_SECRET"`
		From      string `yaml:"from" env:"VONAGE_FROM"`
		BaseURL   string `yaml:"baseUrl"`
	} `yaml:"vonage"`
	AWSSMS struct {
		Region              string `yaml:"region" env:"AWS_SMS_REGION"`
		OriginationIdentity string `yaml:"originationIdentity" env:"AWS_SMS_ORIGINATION_IDENTITY"`
		ConfigurationSet    string `yaml:"configurationSet"`
	} `yaml:"aws"`
	FCM struct {
		ProjectID          string `yaml:"projectId" env:"FCM_PROJECT_ID"`
		ServiceAccountJSON string `env:"FCM_SERVICE_ACCOUNT_JSON"`
	} `yaml:"fcm"`
	APNs struct {
		KeyID      string `yaml:"keyId" env:"APNS_KEY_ID"`
		TeamID     string `yaml:"teamId" env:"APNS_TEAM_ID"`
		BundleID   string `yaml:"bundleId" env:"APNS_BUNDLE_ID"`
		Production bool   `yaml:"production"`
		KeyP8      string `env:"APNS_KEY_P8"`
	} `yaml:"apns"`
	WhatsApp struct {
		PhoneNumberID string `yaml:"phoneNumberId" env:"WHATSAPP_PHONE_NUMBER_ID"`
		Token         string `env:"WHATSAPP_TOKEN"`
		APIVersion    string `yaml:"apiVersion"`
	} `yaml:"whatsapp"`
}

// Resolve fills the settings derived from the shared platform URLs; call it after Load. Explicit values win.
func (c *Config) Resolve() {
	c.PublicBaseURL = pkgconfig.FirstNonEmpty(c.PublicBaseURL, pkgconfig.JoinURL(c.APIBaseURL, "/notifications"))
	c.Links.ProviderHub = pkgconfig.FirstNonEmpty(c.Links.ProviderHub, c.Links.SellerCenter)
	if u, err := url.Parse(c.Attachments.MediaPublicURL); err == nil && u.Host != "" &&
		!slices.Contains(c.Attachments.AllowedHosts, u.Host) {
		c.Attachments.AllowedHosts = append(slices.Clone(c.Attachments.AllowedHosts), u.Host)
	}
	c.Attachments.MediaLinkBaseURL = pkgconfig.FirstNonEmpty(c.Attachments.MediaLinkBaseURL,
		pkgconfig.JoinURL(c.APIBaseURL, "/media"))
	if u, err := url.Parse(c.Attachments.MediaLinkBaseURL); err == nil && u.Host != "" {
		entry := u.Host + strings.TrimRight(u.Path, "/") + "/v1/l/"
		if !slices.Contains(c.Attachments.AllowedHosts, entry) {
			c.Attachments.AllowedHosts = append(slices.Clone(c.Attachments.AllowedHosts), entry)
		}
	}
}
