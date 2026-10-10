// Package config is the notifications service configuration (config/<APP_ENV>.yaml + env overrides).
// Secrets are never in YAML: they come from env vars only (fields without a yaml tag).
package config

import "time"

// Routes are ordered provider names per market ("default" is the fallback) for one channel.
type Routes map[string][]string

// Config for the notifications service.
type Config struct {
	Port          int    `yaml:"port" env:"PORT"`
	DocsEnabled   bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	PublicBaseURL string `yaml:"publicBaseUrl" env:"PUBLIC_BASE_URL"`
	Database      struct {
		URL     string `yaml:"url" env:"DB_URL"`
		Migrate bool   `yaml:"migrate" env:"DB_MIGRATE"`
	} `yaml:"database"`
	Kafka struct {
		Enabled bool     `yaml:"enabled" env:"KAFKA_ENABLED"`
		Brokers []string `yaml:"brokers" env:"KAFKA_BROKERS"`
		// Topics maps logical topic names (identity, orders, sellerApplications, sellerCompliance, payouts,
		// bookings, content, inventory) to addresses; missing names default to the AsyncAPI channel addresses.
		Topics map[string]string `yaml:"topics"`
	} `yaml:"kafka"`
	// Identity resolves recipients by user id / org id for events that carry no contact details.
	Identity struct {
		Enabled      bool          `yaml:"enabled" env:"IDENTITY_ENABLED"`
		BaseURL      string        `yaml:"baseUrl" env:"IDENTITY_URL"`
		TokenURL     string        `yaml:"tokenUrl" env:"AUTH_TOKEN_URI"`
		ClientID     string        `yaml:"clientId" env:"IDENTITY_CLIENT_ID"`
		ClientSecret string        `env:"NOTIFICATIONS_CLIENT_SECRET"`
		Timeout      time.Duration `yaml:"timeout" env:"IDENTITY_TIMEOUT"`
		CacheTTL     time.Duration `yaml:"cacheTtl" env:"IDENTITY_CACHE_TTL"`
		NotFoundTTL  time.Duration `yaml:"notFoundTtl"`
		CacheMax     int           `yaml:"cacheMaxEntries"`
	} `yaml:"identity"`
	// Links are the public base URLs templates link to.
	Links struct {
		Storefront   string `yaml:"storefront" env:"LINKS_STOREFRONT"`
		SellerCenter string `yaml:"sellerCenter" env:"LINKS_SELLER_CENTER"`
		ProviderHub  string `yaml:"providerHub" env:"LINKS_PROVIDER_HUB"`
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
