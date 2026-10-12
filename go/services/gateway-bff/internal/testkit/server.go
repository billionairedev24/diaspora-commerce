package testkit

import (
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/memcache"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/valkey"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// WebOrigin is the storefront origin the test BFF allows.
const WebOrigin = "http://localhost:3000"

// SellerCenterOrigin is the Seller Center origin the test BFF allows.
const SellerCenterOrigin = "http://localhost:3001"

// StaffConsoleOrigin is the staff console origin the test BFF allows.
const StaffConsoleOrigin = "http://localhost:3002"

func chicago() *time.Location {
	if l, err := time.LoadLocation("America/Chicago"); err == nil {
		return l
	}
	return time.UTC
}

// RedirectURI is the storefront's BFF callback registered at the fake identity.
const RedirectURI = WebOrigin + "/api/bff/v1/auth/oidc/callback"

// SellerCenterRedirectURI is Seller Center's own BFF callback (sign-ins started there return there).
const SellerCenterRedirectURI = SellerCenterOrigin + "/api/bff/v1/auth/oidc/callback"

// StaffConsoleRedirectURI is the staff console's own BFF callback.
const StaffConsoleRedirectURI = StaffConsoleOrigin + "/api/bff/v1/auth/oidc/callback"

// BFF is a fully wired BFF against fake upstreams.
type BFF struct {
	Handler   http.Handler
	Upstreams *Upstreams
	Store     valkey.Store
	Metrics   *prometheus.Registry
	Clock     *Clock
}

// Clock is a settable clock for refresh tests.
type Clock struct{ T time.Time }

// Now returns the clock's time (or the real time when unset).
func (c *Clock) Now() time.Time {
	if c.T.IsZero() {
		return time.Now()
	}
	return c.T
}

// Config tweaks the wiring.
type Config struct {
	// PendingURL is where not-yet-built upstreams point; defaults to the fake server (which 404s unknown routes).
	PendingURL string
	Store      valkey.Store
	// AuthPerMinute overrides the auth rate limit (default 1000).
	AuthPerMinute int
}

// NewBFF wires the BFF exactly like cmd/gateway-bff, against u.
func NewBFF(u *Upstreams, cfg Config) *BFF {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	reg := prometheus.NewRegistry()
	metrics := httpclient.NewMetrics(reg)
	client := func(name, url string) httpclient.Client {
		return httpclient.New(httpclient.Options{Name: name, BaseURL: url, Timeout: 2 * time.Second, Retries: 1,
			BreakerFailures: 5, BreakerCooldown: time.Second, Metrics: metrics, Transport: http.DefaultTransport, RetryBackoff: time.Millisecond})
	}
	base := u.URL()
	pending := cfg.PendingURL
	if pending == "" {
		pending = base
	}
	store := cfg.Store
	if store == nil {
		store = NewMemStore()
	}
	if cfg.AuthPerMinute == 0 {
		cfg.AuthPerMinute = 1000
	}
	clock := &Clock{}
	cache := memcache.New(1000, nil)
	search := upstreams.NewSearch(client("search", base))
	catalog := upstreams.NewCatalog(client("catalog", base), cache, time.Minute, 10*time.Minute)
	inventory := upstreams.NewInventory(client("inventory", base))
	cart := upstreams.NewCart(client("cart", base))
	geo := upstreams.NewGeo(client("geo", base), cache, 10*time.Minute)
	identity := upstreams.NewIdentity(client("identity", base), cache, upstreams.IdentityOptions{ClientID: u.ClientID, ClientSecret: u.Secret,
		ProvidersTTL: time.Minute, Now: clock.Now})
	content := upstreams.NewContent(client("content", pending), cache, time.Minute)
	payments := upstreams.NewPayments(client("payments", pending))
	promise := upstreams.NewPromise(client("promise", pending))
	orders := upstreams.NewOrders(client("orders", pending))
	notifications := upstreams.NewNotifications(client("notifications", pending))
	offersClient := client("offers", pending)
	storefrontOffers := upstreams.NewStorefrontOffers(offersClient, cache, time.Minute)
	verifier := upstreams.NewTokenVerifier(base, "", u.ClientID, &http.Client{Timeout: 2 * time.Second})
	identityClient := client("identity", base)
	clientCreds := upstreams.IdentityOptions{ClientID: u.ClientID, ClientSecret: u.Secret, Now: clock.Now}
	media := upstreams.NewMedia(client("media", base))
	sellerDeps := app.SellerDeps{Orgs: upstreams.NewOrgAccess(identityClient), Sellers: upstreams.NewSellerService(client("seller", base)), Addresses: identity,
		Catalog: upstreams.NewSellerCatalog(client("catalog", base), cache, 10*time.Minute), Stock: upstreams.NewStock(client("inventory", base)),
		Listing: upstreams.NewSellerListing(client("catalog", base)), Writer: upstreams.NewListingWriter(client("ai-gateway", base)),
		Offers: upstreams.NewOffers(offersClient), Media: media,
		Orders: upstreams.NewSellerOrders(client("orders", pending)), Carrier: upstreams.NewCarrier(client("carrier-gateway", base)),
		Payouts: upstreams.NewPayouts(client("payments", base)), Fees: upstreams.NewFees(client("payments", base), upstreams.NewServiceTokens(identityClient, clientCreds)),
		Storefront: upstreams.NewStorefront(client("storefront", base)), InventoryAPI: upstreams.NewStaffUpstream(client("inventory", base)),
		Bookings: upstreams.NewBookings(client("bookings", pending), clock.Now), Cache: cache, MatrixTTL: time.Millisecond, Location: chicago(),
		Clock: clock.Now, Log: log, PublishableKey: u.opts.SellerPublishableKey}

	estimator := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 4900})
	sellers := app.SellerDirectory{RetailOrgID: "org_afrotransact_retail", Names: map[string]string{"org_kente": "Kente Naturals"}}
	sessions := app.NewSessionService(store, identity, verifier, app.SessionOptions{SigningKey: "test-signing-key-0123456789abcdef0123",
		VerifyBearer: true, Clock: clock.Now, Log: log})
	location := app.NewLocationService(geo, content, sessions, app.LocationOptions{DefaultZIP: "77042", DefaultCity: "Houston", DefaultState: "TX", Log: log})
	carts := app.NewCartService(app.CartDeps{Cart: cart, Catalog: catalog, Inventory: inventory, Offers: storefrontOffers, Selection: store, Sessions: sessions,
		Estimator: estimator, Clock: clock.Now, Log: log})
	services := httpapi.Services{
		Sessions: sessions,
		Auth: app.NewAuthService(sessions, store, store, identity, verifier, cart, payments, app.AuthOptions{
			IdentityPublicURL: base, ClientID: u.ClientID, RedirectURI: RedirectURI, WebOrigin: WebOrigin,
			RedirectURIs: map[string]string{WebOrigin: RedirectURI, SellerCenterOrigin: SellerCenterRedirectURI,
				StaffConsoleOrigin: StaffConsoleRedirectURI},
			AppOrigins: map[string]string{"storefront": WebOrigin, "seller-center": SellerCenterOrigin, "staff-console": StaffConsoleOrigin},
			BFFPath:    "/api/bff", Applications: sellerDeps.Sellers, Clock: clock.Now, Log: log}),
		Site: app.NewSiteService(app.SiteDeps{Content: content, Identity: identity, Search: search, Catalog: catalog, Inventory: inventory, Offers: storefrontOffers, Location: location,
			Estimator: estimator, Sellers: sellers, Market: "us", Clock: clock.Now, Log: log}),
		Location: location,
		Search:   app.NewSearchService(search, storefrontOffers, location, estimator, "us", clock.Now),
		Products: app.NewProductService(app.ProductDeps{Catalog: catalog, Inventory: inventory, Promise: promise, Notifications: notifications, Offers: storefrontOffers,
			Location: location, Estimator: estimator, Sellers: sellers, Clock: clock.Now, Log: log}),
		Cart:     carts,
		Account:  app.NewAccountService(identity, geo, payments, orders),
		Checkout: app.NewCheckoutService(carts, identity, payments, orders),
		Careers:  app.NewCareersService(media, upstreams.NewServiceTokens(identityClient, clientCreds)),
		Preorders: app.NewPreorderService(upstreams.NewPreorders(client("delivery-runs", base), cache, 0), location, "us",
			app.WithPreorderCatalog(catalog)),

		SellerShell:     app.NewSellerShellService(sellerDeps),
		SellerDashboard: app.NewSellerDashboardService(sellerDeps),
		SellerOrders:    app.NewSellerOrdersService(sellerDeps),
		SellerCatalog:   app.NewSellerCatalogService(sellerDeps),
		SellerListing:   app.NewSellerListingService(sellerDeps),
		SellerImports:   app.NewSellerImportService(sellerDeps, "/api/bff"),
		SellerMoney:     app.NewSellerMoneyService(sellerDeps),
		SellerFees:      app.NewSellerFeesService(sellerDeps),
		SellerTeam:      app.NewSellerTeamService(sellerDeps),
		ProviderHub:     app.NewProviderHubService(sellerDeps),
		SellerShipFrom:  app.NewSellerShipFromService(sellerDeps),
		SellerOffers:    app.NewSellerOffersService(sellerDeps),
		SellerFba:       app.NewSellerFbaService(sellerDeps),
	}
	// Seller Center exports: the BFF's own file store (signed links served by /v1/seller/export-files/{id}).
	if files, err := exportfiles.NewLocal(exportfiles.Options{Dir: filepath.Join(os.TempDir(), "gateway-bff-testkit-exports"), SigningKey: "testkit",
		BasePath: "/api/bff", FilesPath: "/v1/seller/export-files/", Now: clock.Now}); err == nil {
		services.SellerExports = app.NewSellerExportsService(sellerDeps, app.ExportDeps{Jobs: exportfiles.NewMemoryJobs(), Blobs: files,
			Renderer: exportfiles.NewRenderer(), BasePath: "/api/bff", Background: func(fn func()) { fn() }})
		services.SellerExportFiles = files
	}
	services.Shopper = shopperServices(u, client, identity, services, clock, log)
	mux := http.NewServeMux()
	httpapi.New(services, store, httpapi.SecurityOptions{AllowedOrigins: []string{WebOrigin, SellerCenterOrigin, StaffConsoleOrigin}, CookieName: "at_session",
		CookieSecure: true, MaxBodyBytes: 64 << 10, AuthPerMinute: cfg.AuthPerMinute, LookupPerMinute: 1000}, log).Register(mux)
	return &BFF{Handler: mux, Upstreams: u, Store: store, Metrics: reg, Clock: clock}
}
