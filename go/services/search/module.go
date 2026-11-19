package search

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/elastic/go-elasticsearch/v9"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/adtoken"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/content"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/embedder"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/eventbus"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// Name is the module name (config prefix, metrics label module="search").
const Name = "search"

// Deps and Module are the shared module contract (go/pkg/module).
type (
	Deps   = module.Deps
	Module = module.Module
)

// New builds the service without touching process-wide state (see go/pkg/module).
func New(host Deps) (Module, error) {
	ctx := context.Background()
	var closers module.Closers
	m, err := build(host, ctx, &closers)
	if err != nil {
		_ = closers.Close(ctx)
		return Module{}, err
	}
	m.Close = closers.Close
	return m, nil
}

// localAdsTokenKey is a well-known development key; every other environment must set ADS_TOKEN_KEY.
const localAdsTokenKey = "local-dev-ads-token-key-change-me-0123456789" //nolint:gosec // documented dev-only key

func build(host Deps, ctx context.Context, closers *module.Closers) (Module, error) {
	loader := host.Loader(Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return Module{}, fmt.Errorf("load config: %w", err)
	}
	if err := pkgconfig.RequireAuthOutsideLocal(loader.Env(), cfg.Auth.Enabled); err != nil {
		return Module{}, err
	}
	cfg.Resolve(loader.Env())
	log := host.Log(Name, loader.Env())
	local := loader.Env() == pkgconfig.EnvLocal

	synonyms, err := config.LoadSynonyms(Files, cfg.Synonyms.File)
	if err != nil {
		return Module{}, fmt.Errorf("synonyms: %w", err)
	}
	be, err := newBackend(cfg, synonyms.Name)
	if err != nil {
		return Module{}, err
	}
	log.Info("search engine", "provider", be.name)
	admin := be.admin

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	eventsTotal := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "search_events_total", Help: "search.* and ads.* events by type and outcome (published, dropped, failed).",
	}, []string{"type", "outcome"})
	registry.MustRegister(eventsTotal)

	var kafka events.Publisher
	if len(cfg.Kafka.Brokers) > 0 {
		kafka, err = events.NewKafkaPublisher(cfg.Kafka.Brokers)
		if err != nil {
			return Module{}, fmt.Errorf("kafka publisher: %w", err)
		}
		closers.AddFunc(kafka.Close)
	} else {
		log.Warn("KAFKA_BOOTSTRAP not set: search and ads events are dropped")
	}
	bus := eventbus.NewAsync(kafka, max(cfg.Kafka.Buffer, 1000), eventsTotal, log)

	httpClient := &http.Client{Timeout: 5 * time.Second}
	index := be.index
	opts := []app.SearcherOption{app.WithMarkets(cfg.Markets)}

	opts = append(opts, app.WithEvents(bus))

	tokenKey := cfg.Ads.TokenKey
	if tokenKey == "" && local {
		tokenKey = localAdsTokenKey
	}
	tokens, err := adtoken.New(tokenKey)
	if err != nil && cfg.Ads.Enabled {
		return Module{}, fmt.Errorf("ads: set ADS_TOKEN_KEY: %w", err)
	}
	if cfg.Ads.Enabled {
		campaigns := content.New(content.Config{BaseURL: cfg.Ads.Content.BaseURL, TokenURL: cfg.Ads.Content.TokenURL,
			ClientID: cfg.Ads.Content.ClientID, ClientSecret: cfg.Ads.Content.ClientSecret, Scopes: cfg.Ads.Content.Scopes,
			CacheTTL: cfg.Ads.CacheTTL}, httpClient)
		ads := app.NewAdServer(campaigns, index, tokens, bus, app.AdSettings{Slots: cfg.Ads.Slots, TokenTTL: cfg.Ads.TokenTTL,
			Rules: domain.AuctionRules{MinQuality: cfg.Ads.MinQuality, ReserveMinor: cfg.Ads.ReserveMinor, IncrementMinor: 1}}, log)
		opts = append(opts, app.WithAds(ads))
	}

	var emb port.Embedder
	if cfg.AIGatewayURL != "" {
		c := cfg.Ads.Content // the search-service client
		if c.TokenURL != "" && c.ClientSecret == "" {
			log.Warn("SEARCH_CLIENT_SECRET not set: embedding calls are unauthenticated and will fall back to lexical")
		}
		emb = embedder.New(embedder.Config{BaseURL: cfg.AIGatewayURL, TokenURL: c.TokenURL, ClientID: c.ClientID,
			ClientSecret: c.ClientSecret, Scopes: cfg.Semantic.Scopes}, httpClient)
	} else {
		log.Info("AI_GATEWAY_URL not set: search is lexical only")
	}
	opts = append(opts, app.WithEmbedTimeout(cfg.Semantic.Timeout))
	if cfg.Semantic.MinWords > 0 {
		opts = append(opts, app.WithMinSemanticWords(cfg.Semantic.MinWords))
	}
	opts = append(opts, app.WithPictureRules(pictureRules(cfg, loader.Env())))
	deps := httpapi.Deps{
		Searcher:  app.NewProductSearcher(index, emb, log, opts...),
		Suggester: app.NewSuggester(index),
		Synonyms:  app.NewSynonymCurator(admin, log),
	}
	// Without a token key (ads off outside local) organic clicks are still recorded; ad tokens are rejected.
	deps.Clicks = app.NewClickRecorder(tokens, bus, max(cfg.Analytics.ClickDedupe, time.Minute), log)

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
		Name:     "Search API",
		Addr:     ":" + strconv.Itoa(cfg.Port),
		OpenAPI:  openapi,
		DocsOn:   cfg.DocsEnabled,
		Checks:   []httpserver.ReadinessCheck{readiness{index: index, engine: be.name}},
		Logger:   log,
		Registry: registry,
	})
	httpapi.New(deps).Register(srv.Mux(), auth.Middleware(verifier, true))

	var bg module.Background
	bg.Go(bus.Run)
	m := module.FromServer(Name, srv, log, loader.Env(), module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio}, openapi)
	m.Start = func(ctx context.Context) error {
		bootstrap(ctx, cfg, be, synonyms, local, log) // failures are logged: search keeps serving with whatever is in place
		return bg.Start(ctx)
	}
	m.Fatal = bg.Fatal()
	closers.Add(bg.Wait)
	return m, nil
}

func newElasticsearch(cfg config.Config) (*elasticsearch.Client, error) {
	esOpts := []elasticsearch.Option{elasticsearch.WithAddresses(cfg.Elasticsearch.URLs...), elasticsearch.WithRetry(3)}
	switch {
	case cfg.Elasticsearch.APIKey != "":
		esOpts = append(esOpts, elasticsearch.WithAPIKey(cfg.Elasticsearch.APIKey))
	case cfg.Elasticsearch.Username != "":
		esOpts = append(esOpts, elasticsearch.WithBasicAuth(cfg.Elasticsearch.Username, cfg.Elasticsearch.Password))
	}
	es, err := elasticsearch.New(esOpts...)
	if err != nil {
		return nil, fmt.Errorf("elasticsearch client: %w", err)
	}
	return es, nil
}

// bootstrap seeds the synonyms set, creates indices in local, switches inline-synonym indices to the set when allowed
// Failures are logged: search keeps serving with whatever is in place.
func bootstrap(ctx context.Context, cfg config.Config, be backend,
	synonyms domain.SynonymSet, local bool, log *slog.Logger,
) {
	admin := be.admin
	wrote, err := admin.SeedSynonyms(ctx, synonyms.Rules, cfg.Synonyms.Overwrite)
	if err != nil {
		log.Warn("could not seed the synonyms set (is Elasticsearch up?)", "set", synonyms.Name, "error", err)
	} else if wrote {
		log.Info("synonyms set written", "set", synonyms.Name, "rules", len(synonyms.Rules))
	}
	for _, m := range cfg.Markets {
		if local {
			if err := admin.EnsureIndex(ctx, m); err != nil {
				log.Warn("could not ensure index (is Elasticsearch up?)", "market", m, "error", err)
			}
		}
		if err := migrateSynonyms(ctx, admin, m, cfg.Synonyms.MigrateIndex, log); err != nil {
			log.Warn("synonyms set not applied to the index", "market", m, "error", err)
		}
	}
}

func migrateSynonyms(ctx context.Context, admin port.IndexAdmin, market string, allowed bool, log *slog.Logger) error {
	uses, err := admin.UsesSynonymsSet(ctx, market)
	if err != nil {
		return fmt.Errorf("check index settings: %w", err)
	}
	if uses {
		return nil
	}
	if !allowed {
		return errors.New("the index still has inline synonyms; set SYNONYMS_MIGRATE_INDEX=true once in a maintenance window")
	}
	if err := admin.MigrateToSynonymsSet(ctx, market); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	log.Info("index switched to the synonyms set", "market", market)
	return nil
}

type readiness struct {
	index  port.Index
	engine string
}

func (r readiness) Name() string { return r.engine }
func (r readiness) Check(ctx context.Context) error {
	if err := r.index.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}

// pictureRules reads the platform picture rules from catalog (cached 30 s); without a catalog URL the environment's default
// profile applies as it is.
func pictureRules(cfg config.Config, env string) picturerules.Source {
	fallback := picturerules.FromEnv(env, cfg.PictureRules.Profile)
	if cfg.PictureRules.CatalogURL == "" {
		return picturerules.Static(fallback)
	}
	return picturerules.NewClient(cfg.PictureRules.CatalogURL, &http.Client{Timeout: 3 * time.Second}, picturerules.DefaultTTL, fallback)
}
