package cart

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/catalog"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/deliveryruns"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/inventory"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/offers"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/redisrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

// Name is the module name (config prefix CART_, metrics label module="cart").
const Name = "cart"

// maxPoolConns is this service's share of the database server's connections. pgx would otherwise default to
// max(4, NumCPU). A lower pool_max_conns in DB_URL is respected.
const maxPoolConns = 3

// Deps and Module are the shared module contract (go/pkg/module).
type (
	Deps   = module.Deps
	Module = module.Module
)

// New builds the cart service: guest carts in Redis/Valkey, customer carts in PostgreSQL.
func New(deps Deps) (Module, error) {
	ctx := context.Background()
	loader := deps.Loader(Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return Module{}, fmt.Errorf("load config: %w", err)
	}
	if err := pkgconfig.RequireAuthOutsideLocal(loader.Env(), cfg.Auth.Enabled); err != nil {
		return Module{}, err
	}
	log := deps.Log(Name, loader.Env())
	var closers module.Closers
	fail := func(err error) (Module, error) {
		_ = closers.Close(ctx)
		return Module{}, err
	}

	poolCfg, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return fail(fmt.Errorf("postgres config: %w", err))
	}
	if poolCfg.MaxConns > maxPoolConns {
		poolCfg.MaxConns = maxPoolConns
	}
	poolCfg.MinConns = 0
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return fail(fmt.Errorf("postgres: %w", err))
	}
	closers.AddFunc(pool.Close)
	if err := pgrepo.Migrate(ctx, pool, Files); err != nil {
		return fail(fmt.Errorf("migrate: %w", err))
	}
	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return fail(fmt.Errorf("redis url: %w", err))
	}
	rdb := redis.NewClient(ropts)
	closers.AddFunc(func() { _ = rdb.Close() })

	httpClient := &http.Client{Timeout: 3 * time.Second}
	guests := redisrepo.New(rdb, time.Duration(cfg.GuestTTLDays)*24*time.Hour)
	users := pgrepo.New(pool)
	var opts []app.Option
	if cfg.DeliveryRuns.URL != "" {
		opts = append(opts, app.WithPreorders(deliveryruns.New(deliveryruns.Options{BaseURL: cfg.DeliveryRuns.URL,
			TokenURL: cfg.DeliveryRuns.TokenURL, ClientID: cfg.DeliveryRuns.ClientID,
			ClientSecret: cfg.DeliveryRuns.ClientSecret}, httpClient)),
			app.WithRuns(deliveryruns.NewRuns(cfg.DeliveryRuns.URL, httpClient)))
	}
	svc := app.NewCartService(guests, users,
		catalog.New(cfg.CatalogURL, httpClient, 30*time.Second, cfg.RetailOrgID, cfg.SellerNames),
		inventory.New(cfg.InventoryURL, httpClient), offers.New(cfg.OffersURL, httpClient, 15*time.Second), opts...)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}
	if err := auth.RequireVerifier(loader.Env(), verifier); err != nil {
		return fail(fmt.Errorf("auth: %w", err))
	}
	openapi, err := Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fail(fmt.Errorf("read openapi: %w", err))
	}
	checks := []httpserver.ReadinessCheck{ready{name: "postgres", repo: users}, ready{name: "redis", repo: guests}}
	srv := httpserver.New(httpserver.Options{
		Name: "Cart API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: checks, Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	return Module{
		Name: Name, Handler: httpserver.HandlerOf(srv), Router: srv.Mux(), Specs: [][]byte{openapi},
		Gatherer: httpserver.RegistryOf(srv), Checks: checks, Close: closers.Close, Server: srv, Logger: log, Env: loader.Env(),
		Telemetry: module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio},
	}, nil
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
