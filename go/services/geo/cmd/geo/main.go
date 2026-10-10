// Command geo serves address autocomplete, validation, ZIP lookup and delivery-zone queries.
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

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/fixture"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/nominatim"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/postgis"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/smarty"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

const serviceName = "geo"

func main() {
	if err := run(); err != nil {
		slog.Error("geo exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(geo.Files)
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
	if err := postgis.Migrate(ctx, pool, geo.Files); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}

	httpClient := &http.Client{Timeout: 3 * time.Second}
	providers := make([]port.Autocompleter, 0, len(cfg.Providers))
	for _, name := range cfg.Providers {
		switch name {
		case "google-places":
			if cfg.Google.APIKey != "" {
				providers = append(providers, places.New(cfg.Google.APIKey, "", httpClient))
			}
		case "osm-nominatim":
			providers = append(providers, nominatim.New(cfg.Nominatim.URL, cfg.Nominatim.UserAgent, httpClient))
		case "fixture":
			providers = append(providers, fixture.New())
		default:
			return fmt.Errorf("unknown address provider %q", name)
		}
	}
	var validator port.Validator
	if cfg.Smarty.AuthID != "" {
		validator = smarty.New(cfg.Smarty.AuthID, cfg.Smarty.AuthToken, "", httpClient)
	}
	addresses := app.NewAddressService(providers, validator, smarty.NewRules(), log)
	store := postgis.New(pool)
	zones := app.NewZoneService(store)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}
	openapi, err := geo.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Geo API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{store: store}}, Logger: log,
	})
	httpapi.New(addresses, zones).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

type readiness struct{ store port.ZoneStore }

func (readiness) Name() string { return "postgres" }

func (r readiness) Check(ctx context.Context) error {
	if err := r.store.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
