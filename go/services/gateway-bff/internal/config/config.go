// Package config is the gateway BFF configuration (config/<APP_ENV>.yaml + env overrides).
package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// Upstream is one downstream service: base URL plus resilience tuning.
type Upstream struct {
	URL     string        `yaml:"url"`
	Timeout time.Duration `yaml:"timeout"`
	Retries int           `yaml:"retries"` // extra attempts for idempotent GETs only
	Breaker struct {
		Failures int           `yaml:"failures"` // consecutive failures that open the breaker
		Cooldown time.Duration `yaml:"cooldown"` // open → half-open after this long
	} `yaml:"breaker"`
}

// Upstreams lists every service the BFF composes. URLs are overridden with <NAME>_URL env vars.
type Upstreams struct {
	Search        Upstream `yaml:"search"`
	Catalog       Upstream `yaml:"catalog"`
	Inventory     Upstream `yaml:"inventory"`
	Cart          Upstream `yaml:"cart"`
	Geo           Upstream `yaml:"geo"`
	Identity      Upstream `yaml:"identity"`
	Content       Upstream `yaml:"content"`
	Payments      Upstream `yaml:"payments"`
	Promise       Upstream `yaml:"promise"`
	Orders        Upstream `yaml:"orders"`
	Notifications Upstream `yaml:"notifications"`
	// Seller Center & Provider Hub upstreams.
	Seller     Upstream `yaml:"seller"`
	Offers     Upstream `yaml:"offers"`
	Bookings   Upstream `yaml:"bookings"`
	Storefront Upstream `yaml:"storefront"`
	Media      Upstream `yaml:"media"`
	Carrier    Upstream `yaml:"carrier"`
}

// Named returns the upstreams keyed by their metric/log name.
func (u *Upstreams) Named() map[string]*Upstream {
	return map[string]*Upstream{
		"search": &u.Search, "catalog": &u.Catalog, "inventory": &u.Inventory, "cart": &u.Cart, "geo": &u.Geo,
		"identity": &u.Identity, "content": &u.Content, "payments": &u.Payments, "promise": &u.Promise,
		"orders": &u.Orders, "notifications": &u.Notifications, "seller": &u.Seller, "offers": &u.Offers, "bookings": &u.Bookings,
		"storefront": &u.Storefront, "media": &u.Media, "carrier": &u.Carrier,
	}
}

// Config for the gateway BFF.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	RedisURL    string `yaml:"redisUrl" env:"REDIS_URL"`
	Market      string `yaml:"market" env:"MARKET"`

	Web struct {
		// Origins allowed for CORS and the CSRF Origin check (storefront, previews).
		Origins []string `yaml:"origins" env:"WEB_ORIGINS"`
		// PublicOrigin is where the storefront lives; sign-in callbacks redirect back to PublicOrigin + returnTo.
		PublicOrigin string `yaml:"publicOrigin" env:"WEB_PUBLIC_ORIGIN"`
		// BFFPath is the path prefix under PublicOrigin that proxies to the BFF (the storefront's /api/bff).
		BFFPath string `yaml:"bffPath" env:"WEB_BFF_PATH"`
	} `yaml:"web"`

	Session struct {
		CookieName   string        `yaml:"cookieName"`
		CookieSecure bool          `yaml:"cookieSecure" env:"SESSION_COOKIE_SECURE"`
		CookieDomain string        `yaml:"cookieDomain" env:"SESSION_COOKIE_DOMAIN"`
		IdleTTL      time.Duration `yaml:"idleTtl"`
		// SigningKey signs the cookie value and derives the key that encrypts tokens at rest (secret).
		SigningKey string `env:"SESSION_SIGNING_KEY"`
	} `yaml:"session"`

	OIDC struct {
		// Issuer is the identity issuer (iss claim, JWKS at <issuer>/oauth2/jwks).
		Issuer string `yaml:"issuer" env:"OIDC_ISSUER"`
		// PublicURL is identity as the browser reaches it (authorize redirects). Defaults to Issuer.
		PublicURL    string   `yaml:"publicUrl" env:"OIDC_PUBLIC_URL"`
		ClientID     string   `yaml:"clientId" env:"OIDC_CLIENT_ID"`
		ClientSecret string   `env:"OIDC_CLIENT_SECRET"`
		RedirectURI  string   `yaml:"redirectUri" env:"OIDC_REDIRECT_URI"`
		Scopes       []string `yaml:"scopes"`
		// Audience expected in bearer tokens from the mobile app (empty = not checked).
		Audience string `yaml:"audience" env:"OIDC_AUDIENCE"`
		// VerifyBearer enables JWKS verification of app bearer tokens (off only in tests).
		VerifyBearer bool `yaml:"verifyBearer" env:"OIDC_VERIFY_BEARER"`
	} `yaml:"oidc"`

	Upstreams Upstreams `yaml:"upstreams"`

	RateLimit struct {
		AuthPerMinute   int  `yaml:"authPerMinute" env:"RATE_LIMIT_AUTH_PER_MINUTE"`
		LookupPerMinute int  `yaml:"lookupPerMinute" env:"RATE_LIMIT_LOOKUP_PER_MINUTE"`
		TrustForwarded  bool `yaml:"trustForwardedFor" env:"TRUST_FORWARDED_FOR"`
		MaxBodyBytes    int  `yaml:"maxBodyBytes" env:"MAX_BODY_BYTES"`
	} `yaml:"rateLimit"`

	Cache struct {
		MaxEntries   int           `yaml:"maxEntries"`
		DefaultTTL   time.Duration `yaml:"defaultTtl"`
		CatalogTTL   time.Duration `yaml:"catalogTtl"`
		ReferenceTTL time.Duration `yaml:"referenceTtl"` // categories, ZIP lookups, identity providers
	} `yaml:"cache"`

	Commerce struct {
		DefaultZip                 string            `yaml:"defaultZip"`
		DefaultCity                string            `yaml:"defaultCity"`
		DefaultState               string            `yaml:"defaultState"`
		TimeZone                   string            `yaml:"timeZone"`
		SellerShippingMinor        int64             `yaml:"sellerShippingMinor"`
		FreeShippingThresholdMinor int64             `yaml:"freeShippingThresholdMinor"`
		RetailOrgID                string            `yaml:"retailOrgId" env:"RETAIL_ORG_ID"`
		SellerNames                map[string]string `yaml:"sellerNames"`
	} `yaml:"commerce"`

	Telemetry struct {
		OTLPEndpoint string  `yaml:"otlpEndpoint" env:"OTEL_EXPORTER_OTLP_ENDPOINT_HOST"`
		SampleRatio  float64 `yaml:"sampleRatio" env:"TRACE_SAMPLING"`
	} `yaml:"telemetry"`
}

// ApplyUpstreamEnv overrides upstream URLs from <NAME>_URL variables (SEARCH_URL, CATALOG_URL, …).
func (c *Config) ApplyUpstreamEnv(lookup func(string) (string, bool)) {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	for name, u := range c.Upstreams.Named() {
		if v, ok := lookup(strings.ToUpper(name) + "_URL"); ok && v != "" {
			u.URL = v
		}
	}
}

// Validate checks the settings the BFF cannot run without.
func (c *Config) Validate(env string) error {
	if c.RedisURL == "" {
		return fmt.Errorf("config: REDIS_URL is required")
	}
	if len(c.Session.SigningKey) < 32 {
		if env != "local" {
			return fmt.Errorf("config: SESSION_SIGNING_KEY must be at least 32 characters")
		}
		c.Session.SigningKey = "local-only-session-signing-key-change-me-0000"
	}
	if c.OIDC.ClientSecret == "" && env == "local" {
		c.OIDC.ClientSecret = "local-bff-secret" // identity's local default for the gateway-bff client
	}
	if c.OIDC.PublicURL == "" {
		c.OIDC.PublicURL = c.OIDC.Issuer
	}
	if c.Session.CookieName == "" {
		c.Session.CookieName = "at_session"
	}
	if c.RateLimit.MaxBodyBytes <= 0 {
		c.RateLimit.MaxBodyBytes = 64 << 10
	}
	for name, u := range c.Upstreams.Named() {
		if u.URL == "" {
			return fmt.Errorf("config: upstream %s has no url", name)
		}
		if u.Timeout <= 0 {
			u.Timeout = 3 * time.Second
		}
		if u.Breaker.Failures <= 0 {
			u.Breaker.Failures = 5
		}
		if u.Breaker.Cooldown <= 0 {
			u.Breaker.Cooldown = 15 * time.Second
		}
	}
	return nil
}
