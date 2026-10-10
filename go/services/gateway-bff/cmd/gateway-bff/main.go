// Command gateway-bff serves the screen-shaped API for the storefront and customer app: it composes catalog,
// inventory, search, cart, geo, identity, content, payments, promise and orders, and mediates OIDC tokens for
// browsers (HttpOnly session cookie; tokens stay in Valkey).
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	gatewaybff "github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/memcache"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/valkey"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

const serviceName = "gateway-bff"

func main() {
	if err := run(); err != nil {
		slog.Error("gateway-bff exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(gatewaybff.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	cfg.ApplyUpstreamEnv(nil)
	if err := cfg.Validate(loader.Env()); err != nil {
		return fmt.Errorf("config: %w", err)
	}
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)
	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	// State: Valkey (sessions, OIDC requests, selection, rate limits) and an in-process cache for public reads.
	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return fmt.Errorf("redis url: %w", err)
	}
	rdb := redis.NewClient(ropts)
	defer func() { _ = rdb.Close() }()
	store, err := valkey.New(rdb, cfg.Session.SigningKey)
	if err != nil {
		return fmt.Errorf("session store: %w", err)
	}
	cache := memcache.New(cfg.Cache.MaxEntries, nil)

	// Upstreams: one resilient client each (timeout, GET retries, breaker, metrics, OTel propagation).
	reg := prometheus.NewRegistry()
	reg.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	metrics := httpclient.NewMetrics(reg)
	client := func(name string, u config.Upstream) httpclient.Client {
		return httpclient.New(httpclient.Options{Name: name, BaseURL: u.URL, Timeout: u.Timeout, Retries: u.Retries,
			BreakerFailures: u.Breaker.Failures, BreakerCooldown: u.Breaker.Cooldown, Metrics: metrics})
	}
	up := cfg.Upstreams
	search := upstreams.NewSearch(client("search", up.Search))
	catalog := upstreams.NewCatalog(client("catalog", up.Catalog), cache, cfg.Cache.CatalogTTL, cfg.Cache.ReferenceTTL)
	inventory := upstreams.NewInventory(client("inventory", up.Inventory))
	cart := upstreams.NewCart(client("cart", up.Cart))
	geo := upstreams.NewGeo(client("geo", up.Geo), cache, cfg.Cache.ReferenceTTL)
	identity := upstreams.NewIdentity(client("identity", up.Identity), cache, upstreams.IdentityOptions{
		ClientID: cfg.OIDC.ClientID, ClientSecret: cfg.OIDC.ClientSecret, ProvidersTTL: cfg.Cache.DefaultTTL})
	content := upstreams.NewContent(client("content", up.Content), cache, cfg.Cache.DefaultTTL)
	payments := upstreams.NewPayments(client("payments", up.Payments))
	promise := upstreams.NewPromise(client("promise", up.Promise))
	orders := upstreams.NewOrders(client("orders", up.Orders))
	notifications := upstreams.NewNotifications(client("notifications", up.Notifications))
	verifier := upstreams.NewTokenVerifier(cfg.OIDC.Issuer, cfg.OIDC.Audience, cfg.OIDC.ClientID,
		&http.Client{Timeout: up.Identity.Timeout, Transport: otelhttp.NewTransport(http.DefaultTransport)})

	// Use cases.
	tz, err := time.LoadLocation(cfg.Commerce.TimeZone)
	if err != nil {
		tz = time.UTC
	}
	estimator := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: cfg.Commerce.SellerShippingMinor,
		FreeShippingThresholdMinor: cfg.Commerce.FreeShippingThresholdMinor, Location: tz})
	sellers := app.SellerDirectory{RetailOrgID: cfg.Commerce.RetailOrgID, Names: cfg.Commerce.SellerNames}
	sessions := app.NewSessionService(store, identity, verifier, app.SessionOptions{SigningKey: cfg.Session.SigningKey,
		IdleTTL: cfg.Session.IdleTTL, VerifyBearer: cfg.OIDC.VerifyBearer, Log: log})
	location := app.NewLocationService(geo, content, sessions, app.LocationOptions{DefaultZIP: cfg.Commerce.DefaultZip,
		DefaultCity: cfg.Commerce.DefaultCity, DefaultState: cfg.Commerce.DefaultState, Log: log})
	carts := app.NewCartService(app.CartDeps{Cart: cart, Catalog: catalog, Inventory: inventory, Selection: store,
		Sessions: sessions, Estimator: estimator, Log: log})
	services := httpapi.Services{
		Sessions: sessions,
		Auth: app.NewAuthService(sessions, store, store, identity, verifier, cart, payments, app.AuthOptions{
			IdentityPublicURL: cfg.OIDC.PublicURL, ClientID: cfg.OIDC.ClientID, RedirectURI: cfg.OIDC.RedirectURI,
			Scopes: cfg.OIDC.Scopes, WebOrigin: cfg.Web.PublicOrigin, BFFPath: cfg.Web.BFFPath, Log: log}),
		Site: app.NewSiteService(app.SiteDeps{Content: content, Identity: identity, Search: search, Catalog: catalog, Inventory: inventory,
			Location: location, Estimator: estimator, Sellers: sellers, Market: cfg.Market, Log: log}),
		Location: location,
		Search:   app.NewSearchService(search, location, estimator, cfg.Market, nil),
		Products: app.NewProductService(app.ProductDeps{Catalog: catalog, Inventory: inventory, Promise: promise,
			Notifications: notifications, Location: location, Estimator: estimator, Sellers: sellers, Log: log}),
		Cart:     carts,
		Account:  app.NewAccountService(identity, geo, payments, orders),
		Checkout: app.NewCheckoutService(carts, identity, payments, orders),
	}

	openapi, err := gatewaybff.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Gateway BFF API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{valkeyCheck{store: store}}, Logger: log, Registry: reg,
	})
	httpapi.New(services, store, httpapi.SecurityOptions{
		AllowedOrigins: cfg.Web.Origins, CookieName: cfg.Session.CookieName, CookieSecure: cfg.Session.CookieSecure,
		CookieDomain: cfg.Session.CookieDomain, TrustForwarded: cfg.RateLimit.TrustForwarded, MaxBodyBytes: int64(cfg.RateLimit.MaxBodyBytes),
		AuthPerMinute: cfg.RateLimit.AuthPerMinute, LookupPerMinute: cfg.RateLimit.LookupPerMinute,
	}, log).Register(srv.Mux())
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

// valkeyCheck is the readiness probe: sessions live in Valkey, so the BFF is not ready without it. Upstreams are
// deliberately not checked — each degrades on its own behind its circuit breaker.
type valkeyCheck struct{ store valkey.Store }

func (valkeyCheck) Name() string { return "valkey" }

func (v valkeyCheck) Check(ctx context.Context) error {
	if err := v.store.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
