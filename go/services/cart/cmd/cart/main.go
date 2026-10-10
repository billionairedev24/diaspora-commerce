// Command cart serves shopping carts: guest carts in Redis/Valkey, customer carts in PostgreSQL.
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

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/catalog"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/inventory"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/offers"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/redisrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

const serviceName = "cart"

func main() {
	if err := run(); err != nil {
		slog.Error("cart exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(cart.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)
	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("postgres: %w", err)
	}
	defer pool.Close()
	if err := pgrepo.Migrate(ctx, pool, cart.Files); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return fmt.Errorf("redis url: %w", err)
	}
	rdb := redis.NewClient(ropts)
	defer func() { _ = rdb.Close() }()

	httpClient := &http.Client{Timeout: 3 * time.Second}
	guests := redisrepo.New(rdb, time.Duration(cfg.GuestTTLDays)*24*time.Hour)
	users := pgrepo.New(pool)
	svc := app.NewCartService(guests, users,
		catalog.New(cfg.CatalogURL, httpClient, 30*time.Second, cfg.RetailOrgID, cfg.SellerNames),
		inventory.New(cfg.InventoryURL, httpClient), offers.New(cfg.OffersURL, httpClient, 15*time.Second))

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}
	openapi, err := cart.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Cart API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{ready{name: "postgres", repo: users}, ready{name: "redis", repo: guests}},
		Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

type ready struct {
	name string
	repo port.CartRepository
}

func (r ready) Name() string { return r.name }

func (r ready) Check(ctx context.Context) error {
	if err := r.repo.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
