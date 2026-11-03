// Package config is the notifications service configuration (config/<APP_ENV>.yaml + env overrides).
// Secrets are never in YAML: they come from env vars only (fields without a yaml tag).
package config

import (
	"net/url"
	"slices"
	"strings"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
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
		// bookings, content, inventory, payments, trustOps, retail, deliveryRuns) to addresses; missing names default to the
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
	// DeliveryRuns serves pre-order and location audiences (GET /v1/service/preorders/{id}/audience and
	// /v1/service/locations/{id}/audience) with the identity client's credentials (scope
	// delivery-runs:audience.read). Disabled: announcement, reminder and last call reach nobody.
	DeliveryRuns struct {
		Enabled bool          `yaml:"enabled" env:"DELIVERY_RUNS_ENABLED"`
		BaseURL string        `yaml:"baseUrl" env:"DELIVERY_RUNS_URL"`
		Timeout time.Duration `yaml:"timeout" env:"DELIVERY_RUNS_TIMEOUT"`
		// WhatsApp sends run announcements (open, cutoff reminder) as WhatsApp templates to members who opted in on
		// WhatsApp. Off by default: the templates (run.opened / run.cutoff_reminder, channel chat) must be approved
		// in Meta Business Manager first, and WHATSAPP_TOKEN + WHATSAPP_PHONE_NUMBER_ID set.
		WhatsApp struct {
			Enabled bool `yaml:"enabled" env:"DELIVERY_RUNS_WHATSAPP_ENABLED"`
		} `yaml:"whatsapp"`
	} `yaml:"deliveryRuns"`
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
	// Brand is what the shared email layout shows (S279). The footer's socials, WhatsApp community link, app links,
	// phone, address and legal links come from the content service's site config (siteConfigUrl, refreshed every
	// refresh); the values here are the defaults used until it answers and for fields it leaves empty.
	Brand struct {
		Name string `yaml:"name"`
		// AssetBaseURL hosts the email images (logo-header.png, social/*.png, badges/*.png); default
		// <links.storefront>/email (web/apps/storefront/public/email).
		AssetBaseURL         string        `yaml:"assetBaseUrl" env:"EMAIL_ASSET_BASE_URL"`
		SiteConfigURL        string        `yaml:"siteConfigUrl" env:"SITE_CONFIG_URL"`
		Refresh              time.Duration `yaml:"refresh"`
		WhatsAppCommunityURL string        `yaml:"whatsappCommunityUrl" env:"WHATSAPP_COMMUNITY_URL"`
		AppStoreURL          string        `yaml:"appStoreUrl"`
		PlayStoreURL         string        `yaml:"playStoreUrl"`
		SupportPhone         string        `yaml:"supportPhone"`
		SupportPhoneE164     string        `yaml:"supportPhoneE164"`
		SupportEmail         string        `yaml:"supportEmail"`
		Address              string        `yaml:"address"`
		Socials              []struct {
			Network string `yaml:"network"`
			URL     string `yaml:"url"`
		} `yaml:"socials"`
		Legal []struct {
			Label string `yaml:"label"`
			URL   string `yaml:"url"`
		} `yaml:"legal"`
	} `yaml:"brand"`
	// Advertorials are the promotions below the main content of the emails app.AdvertorialPolicy allows (never
	// security emails, never for recipients who opted out of promotional email). Each source is a public read API;
	// an empty URL turns that source off. timeout bounds all lookups for one email.
	Advertorials struct {
		Enabled         bool          `yaml:"enabled" env:"ADVERTORIALS_ENABLED"`
		Timeout         time.Duration `yaml:"timeout"`
		SearchURL       string        `yaml:"searchUrl" env:"SEARCH_URL"`
		DeliveryRunsURL string        `yaml:"deliveryRunsUrl"`
		ContentURL      string        `yaml:"contentUrl" env:"CONTENT_URL"`
		CampaignSlot    string        `yaml:"campaignSlot"`
	} `yaml:"advertorials"`
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
	Routing Routing `yaml:"routing"`
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

// Routing is the ordered provider list per channel and market.
type Routing struct {
	SendTimeout time.Duration `yaml:"sendTimeout" env:"SEND_TIMEOUT"`
	// PreferConfigured (config/local.yaml only) puts a real provider whose credentials are set in front of the
	// channel's local fallback (Mailpit for email, the log for SMS, push and WhatsApp): see EffectiveRouting.
	// ROUTING_PREFER_CONFIGURED=false keeps the local fallbacks even when keys are set.
	PreferConfigured bool   `yaml:"preferConfigured" env:"ROUTING_PREFER_CONFIGURED"`
	Email            Routes `yaml:"email"`
	SMS              Routes `yaml:"sms"`
	Push             Routes `yaml:"push"`
	Chat             Routes `yaml:"chat"`
}

// ProviderChoice says which provider a channel uses after EffectiveRouting, for the start-up log.
type ProviderChoice struct {
	Channel string
	// Real is the provider put in front because its credentials are set ("" = the local fallback only).
	Real string
	// Providers is the resulting order for the default market.
	Providers []string
	// Reason is the variable that decided it: set, or what to set for the real provider.
	Reason string
}

// EffectiveRouting returns the routes to build. Unless PreferConfigured is on (the local profile), they are the
// configured routes unchanged. With it, each channel's first provider whose credentials are complete is put in
// front of every market's list (which keeps the local fallback behind it):
//
//	email  resend (RESEND_API_KEY), else sendgrid (SENDGRID_API_KEY)                          → then smtp (Mailpit)
//	sms    twilio (TWILIO_ACCOUNT_SID + TWILIO_AUTH_TOKEN + TWILIO_FROM or TWILIO_MESSAGING_SERVICE_SID),
//	       else vonage (VONAGE_API_KEY + VONAGE_API_SECRET)                                   → then log
//	push   fcm (FCM_SERVICE_ACCOUNT_JSON), apns (APNS_KEY_P8 + APNS_KEY_ID + APNS_TEAM_ID)    → then log
//	chat   whatsapp (WHATSAPP_TOKEN + WHATSAPP_PHONE_NUMBER_ID)                               → then log
//
// The returned maps are copies; the receiver is not changed.
func (c Config) EffectiveRouting() (Routing, []ProviderChoice) {
	out := c.Routing
	out.Email, out.SMS, out.Push, out.Chat = cloneRoutes(c.Routing.Email), cloneRoutes(c.Routing.SMS),
		cloneRoutes(c.Routing.Push), cloneRoutes(c.Routing.Chat)
	if !c.Routing.PreferConfigured {
		return out, nil
	}
	p := c.Providers
	twilioSender := p.Twilio.From != "" || p.Twilio.MessagingServiceSID != ""
	channels := []struct {
		name   string
		routes Routes
		cands  []candidate
	}{
		{"email", out.Email, []candidate{
			{"resend", p.Resend.APIKey != "", "RESEND_API_KEY"},
			{"sendgrid", p.SendGrid.APIKey != "", "SENDGRID_API_KEY"},
		}},
		{"sms", out.SMS, []candidate{
			{"twilio", p.Twilio.AccountSID != "" && p.Twilio.AuthToken != "" && twilioSender,
				"TWILIO_ACCOUNT_SID + TWILIO_AUTH_TOKEN + TWILIO_FROM (or TWILIO_MESSAGING_SERVICE_SID)"},
			{"vonage", p.Vonage.APIKey != "" && p.Vonage.APISecret != "", "VONAGE_API_KEY + VONAGE_API_SECRET"},
		}},
		{"push", out.Push, []candidate{
			{"fcm", p.FCM.ServiceAccountJSON != "", "FCM_SERVICE_ACCOUNT_JSON"},
			{"apns", p.APNs.KeyP8 != "" && p.APNs.KeyID != "" && p.APNs.TeamID != "", "APNS_KEY_P8 + APNS_KEY_ID + APNS_TEAM_ID"},
		}},
		{"chat", out.Chat, []candidate{
			{"whatsapp", p.WhatsApp.Token != "" && p.WhatsApp.PhoneNumberID != "", "WHATSAPP_TOKEN + WHATSAPP_PHONE_NUMBER_ID"},
		}},
	}
	choices := make([]ProviderChoice, 0, len(channels))
	for _, ch := range channels {
		choice := ProviderChoice{Channel: ch.name}
		for _, cand := range ch.cands {
			if cand.ready {
				choice.Real, choice.Reason = cand.name, cand.vars+" set"
				break
			}
		}
		if choice.Real == "" {
			choice.Reason = "set " + ch.cands[0].vars + " to use " + ch.cands[0].name
		} else {
			for market, names := range ch.routes {
				ch.routes[market] = append([]string{choice.Real}, slices.DeleteFunc(slices.Clone(names),
					func(n string) bool { return n == choice.Real })...)
			}
		}
		choice.Providers = slices.Clone(ch.routes["default"])
		choices = append(choices, choice)
	}
	return out, choices
}

type candidate struct {
	name  string
	ready bool
	vars  string
}

func cloneRoutes(r Routes) Routes {
	out := make(Routes, len(r))
	for market, names := range r {
		out[market] = slices.Clone(names)
	}
	return out
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
	c.Brand.AssetBaseURL = pkgconfig.FirstNonEmpty(c.Brand.AssetBaseURL, pkgconfig.JoinURL(c.Links.Storefront, "/email"))
	c.Advertorials.DeliveryRunsURL = pkgconfig.FirstNonEmpty(c.Advertorials.DeliveryRunsURL, c.DeliveryRuns.BaseURL)
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

// EmailBrand is the configured email brand (brand.*): the defaults the site config overlays.
func (c Config) EmailBrand() domain.Brand {
	b := domain.Brand{Name: c.Brand.Name, StorefrontURL: c.Links.Storefront, AssetBaseURL: c.Brand.AssetBaseURL,
		WhatsAppCommunityURL: c.Brand.WhatsAppCommunityURL, AppStoreURL: c.Brand.AppStoreURL, PlayStoreURL: c.Brand.PlayStoreURL,
		SupportPhone: c.Brand.SupportPhone, SupportPhoneE164: c.Brand.SupportPhoneE164, SupportEmail: c.Brand.SupportEmail,
		Address: c.Brand.Address}
	for _, s := range c.Brand.Socials {
		b.Socials = append(b.Socials, domain.SocialLink{Network: s.Network, URL: s.URL})
	}
	for _, l := range c.Brand.Legal {
		b.Legal = append(b.Legal, domain.BrandLink{Label: l.Label, URL: l.URL})
	}
	return b
}
