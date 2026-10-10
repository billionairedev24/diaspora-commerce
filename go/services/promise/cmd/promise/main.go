// Command promise computes delivery promises ("Tomorrow, Oct 10") per destination ZIP from FC cutoffs, carrier
// transit, seller handling time, AfroTransact Delivery zones and pickup ready times, on the US business calendar.
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

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/nodes"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/valkeystore"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/ziptable"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

const serviceName = "promise"

func main() {
	if err := run(); err != nil {
		slog.Error("promise exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(promise.Files)
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

	table, err := ziptable.Load(promise.Files, cfg.Data.Regions, cfg.Data.Transit)
	if err != nil {
		return fmt.Errorf("zip data: %w", err)
	}
	directory, err := nodes.New(cfg.FulfilmentCentres)
	if err != nil {
		return fmt.Errorf("fulfilment centres: %w", err)
	}
	settings, err := settingsFrom(cfg)
	if err != nil {
		return err
	}
	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return fmt.Errorf("redis url: %w", err)
	}
	rdb := redis.NewClient(ropts)
	defer func() { _ = rdb.Close() }()
	store := valkeystore.New(rdb, "promise:")

	svc := app.NewPromiseService(domain.NewPlanner(domain.NewUSCalendar()), table, table, directory, store, settings, log, time.Now)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, &http.Client{Timeout: 3 * time.Second})
	}
	openapi, err := promise.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Promise API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{ready{store: store}}, Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, true))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
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
