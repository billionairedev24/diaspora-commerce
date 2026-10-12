// Package config is the gateway BFF configuration (config/<APP_ENV>.yaml + env overrides).
package config

import (
	"fmt"
	"os"
	"slices"
	"strings"
	"time"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
)

// Upstream is one downstream service: base URL plus resilience tuning.
type Upstream struct {
	URL     string        `yaml:"url"`
	Timeout time.Duration `yaml:"timeout"`
	// SlowTimeout bounds calls that wait for a downstream saga or call chain (orders: POST /v1/checkout and its
	// confirm, above orders' checkout-response-timeout; deliveryRuns: late pre-order adds, above its 15s orders
	// timeout; seller: a final approval, which activates the Stripe subscription). Defaults to Timeout.
	SlowTimeout time.Duration `yaml:"slowTimeout"`
	Retries     int           `yaml:"retries"` // extra attempts for GETs and for calls that carry an Idempotency-Key
	Breaker     struct {
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
	// Storefront account, returns and Lola upstreams (TAX_RETURNS_URL, AI_GATEWAY_URL).
	TaxReturns Upstream `yaml:"taxReturns"`
	AIGateway  Upstream `yaml:"aiGateway"`
	// Staff console upstreams (TRUST_OPS_URL, RETAIL_URL, FULFILMENT_URL).
	TrustOps   Upstream `yaml:"trustOps"`
	Retail     Upstream `yaml:"retail"`
	Fulfilment Upstream `yaml:"fulfilment"`
	// DeliveryRuns owns pre-orders: storefront resolution, waitlist and the staff Pre-orders module (DELIVERY_RUNS_URL).
	DeliveryRuns Upstream `yaml:"deliveryRuns"`
}

// Named returns the upstreams keyed by their metric/log name.
func (u *Upstreams) Named() map[string]*Upstream {
	return map[string]*Upstream{
		"search": &u.Search, "catalog": &u.Catalog, "inventory": &u.Inventory, "cart": &u.Cart, "geo": &u.Geo,
		"identity": &u.Identity, "content": &u.Content, "payments": &u.Payments, "promise": &u.Promise,
		"orders": &u.Orders, "notifications": &u.Notifications, "seller": &u.Seller, "offers": &u.Offers, "bookings": &u.Bookings,
		"storefront": &u.Storefront, "media": &u.Media, "carrier": &u.Carrier,
		"tax_returns": &u.TaxReturns, "ai_gateway": &u.AIGateway,
		"trust_ops": &u.TrustOps, "retail": &u.Retail, "fulfilment": &u.Fulfilment, "delivery_runs": &u.DeliveryRuns,
	}
}

// Config for the gateway BFF.
type Config struct {
	Port        int    `yaml:"port" env:"PORT"`
	DocsEnabled bool   `yaml:"docsEnabled" env:"DOCS_ENABLED"`
	RedisURL    string `yaml:"redisUrl" env:"REDIS_URL"`
	Market      string `yaml:"market" env:"MARKET"`

	// Platform holds the shared, environment-wide public URLs (names in go/pkg/config/platform.go). The Helm library
	// chart injects them from global.domain; config/<env>.yaml carries that environment's own hosts as the fallback,
	// config/local.yaml localhost. Env vars override YAML. Resolve derives the web and OIDC settings from them.
	Platform struct {
		PublicWebURL    string `yaml:"publicWebUrl" env:"PUBLIC_WEB_URL"`       // storefront
		SellerCenterURL string `yaml:"sellerCenterUrl" env:"SELLER_CENTER_URL"` // Seller Center & Provider Hub
		StaffConsoleURL string `yaml:"staffConsoleUrl" env:"STAFF_CONSOLE_URL"` // staff console
		AuthIssuerURL   string `yaml:"authIssuerUrl" env:"AUTH_ISSUER_URL"`     // identity OIDC issuer
	} `yaml:"platform"`

	Web struct {
		// Origins allowed for CORS and the CSRF Origin check. Default (Resolve): the storefront, Seller Center and staff
		// console URLs of the platform section. WEB_ORIGINS (comma-separated) replaces the list, e.g. to add previews.
		Origins []string `yaml:"origins" env:"WEB_ORIGINS"`
		// PublicOrigin is where the storefront lives; sign-in callbacks redirect back to PublicOrigin + returnTo.
		// Default (Resolve): PUBLIC_WEB_URL; WEB_PUBLIC_ORIGIN overrides.
		PublicOrigin string `yaml:"publicOrigin" env:"WEB_PUBLIC_ORIGIN"`
		// BFFPath is the path prefix under PublicOrigin that proxies to the BFF (the storefront's /api/bff).
		BFFPath string `yaml:"bffPath" env:"WEB_BFF_PATH"`
		// LANMediaURL (local LAN mode only, set by tools/dev/local.sh): image URLs stored with this stack's MinIO
		// address (LocalMediaURL) are rewritten to it in JSON responses, so a phone on the Wi-Fi can load them.
		// Ignored outside APP_ENV=local.
		LANMediaURL string `yaml:"lanMediaUrl" env:"LAN_MEDIA_URL"`
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
		// Issuer is the identity issuer (iss claim, JWKS at <issuer>/oauth2/jwks). Default (Resolve): AUTH_ISSUER_URL;
		// OIDC_ISSUER overrides.
		Issuer string `yaml:"issuer" env:"OIDC_ISSUER"`
		// PublicURL is identity as the browser reaches it (authorize redirects). Defaults to Issuer.
		PublicURL    string `yaml:"publicUrl" env:"OIDC_PUBLIC_URL"`
		ClientID     string `yaml:"clientId" env:"OIDC_CLIENT_ID"`
		ClientSecret string `env:"OIDC_CLIENT_SECRET"`
		// RedirectURI is the storefront's BFF callback registered at identity. Default (Resolve):
		// <PublicOrigin><BFFPath>/v1/auth/oidc/callback. Seller Center and the staff console sign in through their own
		// origin's callback (RedirectURIs), so each app's session cookie stays first-party on its own host.
		RedirectURI string   `yaml:"redirectUri" env:"OIDC_REDIRECT_URI"`
		Scopes      []string `yaml:"scopes"`
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

	// Stripe is the public half of the Stripe account (Stripe.js / PaymentSheet); secrets stay in payments.
	Stripe struct {
		PublishableKey string `yaml:"publishableKey" env:"STRIPE_PUBLISHABLE_KEY"`
	} `yaml:"stripe"`

	// Staff console exports: "local" keeps files on the BFF's disk behind signed links (default); "media" stores them
	// in media-service's object store once it accepts export files.
	Staff struct {
		ExportStore string `yaml:"exportStore" env:"STAFF_EXPORT_STORE"`
		ExportDir   string `yaml:"exportDir" env:"STAFF_EXPORT_DIR"`
	} `yaml:"staff"`

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

// OIDCCallbackPath is the BFF's browser callback under Web.BFFPath (each web app proxies <BFFPath> to the BFF).
const OIDCCallbackPath = "/v1/auth/oidc/callback"

// RedirectURIs maps each web app origin (Web.Origins) to the callback the BFF sends for sign-ins started there:
// <origin><BFFPath>/v1/auth/oidc/callback, the storefront's being OIDC.RedirectURI (OIDC_REDIRECT_URI overrides it).
// Identity registers the same URIs for the gateway-bff client (application-<env>.yml).
func (c *Config) RedirectURIs() map[string]string {
	out := make(map[string]string, len(c.Web.Origins)+1)
	for _, o := range c.Web.Origins {
		o = strings.TrimRight(strings.TrimSpace(o), "/")
		if o != "" {
			out[o] = pkgconfig.JoinURL(o, strings.TrimRight(c.Web.BFFPath, "/")+OIDCCallbackPath)
		}
	}
	if c.Web.PublicOrigin != "" && c.OIDC.RedirectURI != "" {
		out[c.Web.PublicOrigin] = c.OIDC.RedirectURI
	}
	return out
}

// Resolve fills the web and OIDC settings from the shared platform URLs; call it after Load (and ApplyUpstreamEnv),
// before Validate. Explicit values (YAML or WEB_ORIGINS, WEB_PUBLIC_ORIGIN, OIDC_ISSUER, OIDC_REDIRECT_URI) win.
func (c *Config) Resolve() {
	p := c.Platform
	c.Web.PublicOrigin = strings.TrimRight(pkgconfig.FirstNonEmpty(c.Web.PublicOrigin, p.PublicWebURL), "/")
	if len(c.Web.Origins) == 0 {
		for _, o := range []string{p.PublicWebURL, p.SellerCenterURL, p.StaffConsoleURL} {
			o = strings.TrimRight(strings.TrimSpace(o), "/")
			if o != "" && !slices.Contains(c.Web.Origins, o) {
				c.Web.Origins = append(c.Web.Origins, o)
			}
		}
	}
	c.OIDC.Issuer = pkgconfig.FirstNonEmpty(c.OIDC.Issuer, p.AuthIssuerURL)
	c.OIDC.RedirectURI = pkgconfig.FirstNonEmpty(c.OIDC.RedirectURI,
		pkgconfig.JoinURL(c.Web.PublicOrigin, strings.TrimRight(c.Web.BFFPath, "/")+OIDCCallbackPath))
}

// LocalMediaURL is the public base URL the local media service gives images (go/services/media/config/local.yaml).
const LocalMediaURL = "http://localhost:9000/media/"

// MediaRewrite is the local LAN-mode image URL rewrite (from, to); empty strings when off.
func (c *Config) MediaRewrite() (string, string) {
	to := strings.TrimRight(strings.TrimSpace(c.Web.LANMediaURL), "/")
	if to == "" {
		return "", ""
	}
	return LocalMediaURL, to + "/"
}

// Validate checks the settings the BFF cannot run without.
func (c *Config) Validate(env string) error {
	if env != "local" {
		c.Web.LANMediaURL = "" // a development convenience only
	}
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
	if c.OIDC.Issuer == "" {
		return fmt.Errorf("config: OIDC issuer is required (AUTH_ISSUER_URL or OIDC_ISSUER)")
	}
	if c.Web.PublicOrigin == "" || len(c.Web.Origins) == 0 || c.OIDC.RedirectURI == "" {
		return fmt.Errorf("config: storefront origin is required (PUBLIC_WEB_URL or WEB_PUBLIC_ORIGIN)")
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
		if u.SlowTimeout < u.Timeout {
			u.SlowTimeout = u.Timeout
		}
		if u.Breaker.Failures <= 0 {
			u.Breaker.Failures = 5
		}
		if u.Breaker.Cooldown <= 0 {
			u.Breaker.Cooldown = 15 * time.Second
		}
	}
	return c.validateStripe(env)
}

// validateStripe checks the publishable key the storefront's Stripe Elements (and the apps' PaymentSheet) use. It comes
// only from STRIPE_PUBLISHABLE_KEY. Locally it may be empty: the storefront then shows its labelled, dev-only test form
// ("Stripe test mode is not configured"). Every deployed environment needs a real key, so that fallback can never
// reach dev, uat or prod: pk_test_… in dev and uat, pk_live_… in prod (payments enforces the same split for sk_).
func (c *Config) validateStripe(env string) error {
	key := strings.TrimSpace(c.Stripe.PublishableKey)
	c.Stripe.PublishableKey = key
	live := strings.HasPrefix(key, "pk_live_")
	if env == "local" {
		if live {
			return fmt.Errorf("config: STRIPE_PUBLISHABLE_KEY must be a Stripe test key (pk_test_…) locally")
		}
		return nil
	}
	switch {
	case key == "":
		return fmt.Errorf("config: STRIPE_PUBLISHABLE_KEY is required in %s (pk_test_… in dev and uat, pk_live_… in prod)", env)
	case strings.HasPrefix(key, "pk_test_mock"):
		return fmt.Errorf("config: STRIPE_PUBLISHABLE_KEY %q is the mock BFF's key; set a real Stripe key in %s", key, env)
	case env == "prod" && !live:
		return fmt.Errorf("config: prod needs a live Stripe publishable key (pk_live_…)")
	case env != "prod" && !strings.HasPrefix(key, "pk_test_"):
		return fmt.Errorf("config: %s needs a Stripe test publishable key (pk_test_…)", env)
	}
	return nil
}
