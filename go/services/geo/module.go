package geo

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/fixture"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/gazetteer"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/nominatim"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/places"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/postgis"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/roadrouter"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/routecache"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/zipindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// Name is the module name (config prefix, metrics label module="geo").
const Name = "geo"

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

// maxPoolConns is this service's share of the database server's connections (see the pool setup in run).
const maxPoolConns = 3

func build(deps Deps, ctx context.Context, closers *module.Closers) (Module, error) {
	loader := deps.Loader(Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return Module{}, fmt.Errorf("load config: %w", err)
	}
	if err := pkgconfig.RequireAuthOutsideLocal(loader.Env(), cfg.Auth.Enabled); err != nil {
		return Module{}, err
	}
	if err := cfg.Validate(loader.Env()); err != nil {
		return Module{}, fmt.Errorf("config: %w", err)
	}
	log := deps.Log(Name, loader.Env())

	// Explicit, small connection budget: ~15 services share one managed Postgres server (docs/runbooks/ovh-production.md,
	// connection budget). pgx would otherwise default to max(4, NumCPU). A lower pool_max_conns in DB_URL is respected.
	poolCfg, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return Module{}, fmt.Errorf("postgres config: %w", err)
	}
	if poolCfg.MaxConns > maxPoolConns {
		poolCfg.MaxConns = maxPoolConns
	}
	poolCfg.MinConns = 0
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return Module{}, fmt.Errorf("postgres: %w", err)
	}
	closers.AddFunc(pool.Close)
	if err := postgis.Migrate(ctx, pool, Files); err != nil {
		return Module{}, fmt.Errorf("migrate: %w", err)
	}

	httpClient := &http.Client{Timeout: 3 * time.Second}
	osm := nominatim.New(cfg.Nominatim.URL, cfg.Nominatim.UserAgent, httpClient) // one rate limit + cache for both chains
	fixtures := fixture.New()
	googleKey := cfg.GoogleKey()
	providers := make([]port.Autocompleter, 0, len(cfg.Providers))
	for _, name := range cfg.AddressProviders() {
		switch name {
		case config.ProviderGooglePlaces:
			providers = append(providers, places.New(googleKey, "", httpClient))
		case config.ProviderNominatim:
			providers = append(providers, osm)
		case config.ProviderFixture:
			providers = append(providers, fixtures)
		default:
			return Module{}, fmt.Errorf("unknown address provider %q", name)
		}
	}
	log.Info("address autocomplete providers", "providers", cfg.AddressProviders())
	reversers := make([]port.ReverseGeocoder, 0, len(cfg.ReverseProviders)+1)
	for _, name := range cfg.ReverseGeocoders() {
		switch name {
		case config.ProviderGoogleGeocoding:
			reversers = append(reversers, places.NewGeocoder(googleKey, "", httpClient))
		case config.ProviderNominatim:
			reversers = append(reversers, osm)
		case config.ProviderFixture:
			reversers = append(reversers, fixtures)
		default:
			return Module{}, fmt.Errorf("unknown reverse geocoding provider %q", name)
		}
	}
	// Google is the only validator. Without a key (local and offline development only; config.Validate refuses that in
	// uat and prod) every address is accepted.
	var validator port.Validator
	if googleKey != "" {
		validator = places.NewValidator(googleKey, "", httpClient)
	}
	zipIndex, err := zipindex.New()
	if err != nil {
		return Module{}, fmt.Errorf("zip index: %w", err)
	}
	addresses := app.WithZipIndex(app.NewAddressService(providers, validator, log), zipIndex)
	store := postgis.New(pool)
	zones := app.NewZoneService(store)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}
	if err := auth.RequireVerifier(loader.Env(), verifier); err != nil {
		return Module{}, fmt.Errorf("auth: %w", err)
	}
	openapi, err := Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return Module{}, fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Geo API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{store: store}}, Logger: log,
	})
	reverse := app.NewReverseService(reversers, log)
	routers, err := routingProviders(cfg, httpClient, log)
	if err != nil {
		return Module{}, err
	}
	distances := app.NewDistanceService(addresses, postgis.NewDistanceCalculator(pool), app.RoutingOptions{
		Routers: routers, Timeout: cfg.Routing.Timeout, DetourFactor: cfg.Routing.DetourFactor,
		FallbackSpeedMph: cfg.Routing.FallbackSpeedMph,
		Cache: routecache.New(cfg.Routing.CacheTTL, cfg.Routing.CachePrecisionMeters, cfg.Routing.CacheMaxEntries,
			nil),
	}, log)
	httpapi.New(addresses, reverse, zones, distances).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	httpapi.NewZips(zipIndex).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	world, err := gazetteer.New()
	if err != nil {
		return Module{}, fmt.Errorf("gazetteer: %w", err)
	}
	httpapi.NewPlaces(app.NewPlaceService(world)).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	return module.FromServer(Name, srv, log, loader.Env(), module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio}, openapi), nil
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
				key = cfg.GoogleKey()
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
