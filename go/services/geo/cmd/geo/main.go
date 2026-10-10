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
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/roadrouter"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/routecache"
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
	osm := nominatim.New(cfg.Nominatim.URL, cfg.Nominatim.UserAgent, httpClient) // one rate limit + cache for both chains
	fixtures := fixture.New()
	providers := make([]port.Autocompleter, 0, len(cfg.Providers))
	for _, name := range cfg.Providers {
		switch name {
		case "google-places":
			if cfg.Google.APIKey != "" {
				providers = append(providers, places.New(cfg.Google.APIKey, "", httpClient))
			}
		case "osm-nominatim":
			providers = append(providers, osm)
		case "fixture":
			providers = append(providers, fixtures)
		default:
			return fmt.Errorf("unknown address provider %q", name)
		}
	}
	reversers := make([]port.ReverseGeocoder, 0, len(cfg.ReverseProviders))
	for _, name := range cfg.ReverseProviders {
		switch name {
		case "google-geocoding":
			if cfg.Google.APIKey != "" {
				reversers = append(reversers, places.NewGeocoder(cfg.Google.APIKey, "", httpClient))
			}
		case "osm-nominatim":
			reversers = append(reversers, osm)
		case "fixture":
			reversers = append(reversers, fixtures)
		default:
			return fmt.Errorf("unknown reverse geocoding provider %q", name)
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
	reverse := app.NewReverseService(reversers, log)
	routers, err := routingProviders(cfg, httpClient, log)
	if err != nil {
		return err
	}
	distances := app.NewDistanceService(addresses, postgis.NewDistanceCalculator(pool), app.RoutingOptions{
		Routers: routers, Timeout: cfg.Routing.Timeout, DetourFactor: cfg.Routing.DetourFactor,
		FallbackSpeedMph: cfg.Routing.FallbackSpeedMph,
		Cache: routecache.New(cfg.Routing.CacheTTL, cfg.Routing.CachePrecisionMeters, cfg.Routing.CacheMaxEntries,
			nil),
	}, log)
	httpapi.New(addresses, reverse, zones, distances).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

// routingProviders builds the configured routing providers in priority order; a provider without its key or URL is
// skipped (logged), so environments enable routing by setting secrets only.
func routingProviders(cfg config.Config, client *http.Client, log *slog.Logger) ([]port.RoadRouter, error) {
	out := make([]port.RoadRouter, 0, len(cfg.Routing.Providers))
	for _, name := range cfg.Routing.Providers {
		switch name {
		case "google":
			key := cfg.Routing.GoogleAPIKey
			if key == "" {
				key = cfg.Google.APIKey
			}
			if key == "" {
				log.Info("routing provider google skipped: no GOOGLE_MAPS_API_KEY")
				continue
			}
			out = append(out, roadrouter.NewGoogle(key, "", client))
		case "osrm":
			if cfg.Routing.OSRMURL == "" {
				log.Info("routing provider osrm skipped: no OSRM_URL")
				continue
			}
			out = append(out, roadrouter.NewOSRM(cfg.Routing.OSRMURL, client))
		default:
			return nil, fmt.Errorf("unknown routing provider %q", name)
		}
	}
	return out, nil
}

type readiness struct{ store port.ZoneStore }

func (readiness) Name() string { return "postgres" }

func (r readiness) Check(ctx context.Context) error {
	if err := r.store.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
