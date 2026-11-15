package promise

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/nodes"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/valkeystore"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/ziptable"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

// Name is the module name (config prefix, metrics label module="promise").
const Name = "promise"

// Deps and Module are the shared module contract (go/pkg/module).
type (
	Deps   = module.Deps
	Module = module.Module
)

// New builds the service without touching process-wide state (see go/pkg/module).
func New(deps Deps) (Module, error) {
	ctx := context.Background()
	var closers module.Closers
	m, err := build(deps, ctx, &closers)
	if err != nil {
		_ = closers.Close(ctx)
		return Module{}, err
	}
	m.Close = closers.Close
	return m, nil
}

func build(deps Deps, ctx context.Context, closers *module.Closers) (Module, error) {
	loader := deps.Loader(Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return Module{}, fmt.Errorf("load config: %w", err)
	}
	if err := pkgconfig.RequireAuthOutsideLocal(loader.Env(), cfg.Auth.Enabled); err != nil {
		return Module{}, err
	}
	log := deps.Log(Name, loader.Env())

	table, err := ziptable.Load(Files, cfg.Data.Regions, cfg.Data.Transit)
	if err != nil {
		return Module{}, fmt.Errorf("zip data: %w", err)
	}
	directory, err := nodes.New(cfg.FulfilmentCentres)
	if err != nil {
		return Module{}, fmt.Errorf("fulfilment centres: %w", err)
	}
	settings, err := settingsFrom(cfg)
	if err != nil {
		return Module{}, err
	}
	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return Module{}, fmt.Errorf("redis url: %w", err)
	}
	rdb := redis.NewClient(ropts)
	closers.AddFunc(func() { _ = rdb.Close() })
	store := valkeystore.New(rdb, "promise:")

	svc := app.NewPromiseService(domain.NewPlanner(domain.NewUSCalendar()), table, table, directory, store, settings, log, time.Now)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, &http.Client{Timeout: 3 * time.Second})
	}
	if err := auth.RequireVerifier(loader.Env(), verifier); err != nil {
		return Module{}, fmt.Errorf("auth: %w", err)
	}
	openapi, err := Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return Module{}, fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Promise API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{ready{store: store}}, Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, true))
	return module.FromServer(Name, srv, log, loader.Env(), module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio}, openapi), nil
}

func settingsFrom(cfg config.Config) (app.Settings, error) {
	s := app.Settings{ServiceLevels: map[domain.ServiceLevel]domain.ServiceRule{}, SellerPickupReady: cfg.Seller.PickupReadyHours,
		SellerPickupHours: cfg.Seller.PickupHours, SnapshotTTL: cfg.SnapshotTTL, MaxOffers: cfg.MaxOffers, MaxHandlingDays: cfg.Seller.MaxHandlingDays}
	for name, l := range cfg.ServiceLevels {
		s.ServiceLevels[domain.ServiceLevel(name)] = domain.ServiceRule{MaxDays: l.MaxDays, SaturdayDelivery: l.SaturdayDelivery}
	}
	cutoff, err := domain.ParseClock(cfg.Seller.Cutoff)
	if err != nil {
		return app.Settings{}, fmt.Errorf("seller cutoff: %w", err)
	}
	s.SellerCutoff = cutoff
	return s, nil
}

type ready struct{ store port.PromiseStore }

func (ready) Name() string { return "valkey" }

func (r ready) Check(ctx context.Context) error {
	if err := r.store.Ping(ctx); err != nil {
		return fmt.Errorf("valkey: %w", err)
	}
	return nil
}
