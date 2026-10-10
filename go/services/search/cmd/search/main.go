// Command search serves product search and typeahead over Elasticsearch, with sponsored products, click tracking and
// search analytics.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/elastic/go-elasticsearch/v9"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"golang.org/x/sync/errgroup"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/adtoken"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/content"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/embedder"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/eventbus"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

const serviceName = "search"

// localAdsTokenKey is a well-known development key; every other environment must set ADS_TOKEN_KEY.
const localAdsTokenKey = "local-dev-ads-token-key-change-me-0123456789" //nolint:gosec // documented dev-only key

func main() {
	if err := run(); err != nil {
		slog.Error("search exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(search.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	cfg.Resolve(loader.Env())
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)
	local := loader.Env() == pkgconfig.EnvLocal

	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	es, err := newElasticsearch(cfg)
	if err != nil {
		return err
	}
	synonyms, err := config.LoadSynonyms(search.Files, cfg.Synonyms.File)
	if err != nil {
		return fmt.Errorf("synonyms: %w", err)
	}
	admin := elastic.NewAdmin(es, synonyms.Name)
	bootstrap(ctx, cfg, admin, es, synonyms, local, log)

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
			return fmt.Errorf("kafka publisher: %w", err)
		}
		defer kafka.Close()
	} else {
		log.Warn("KAFKA_BOOTSTRAP not set: search and ads events are dropped")
	}
	bus := eventbus.NewAsync(kafka, max(cfg.Kafka.Buffer, 1000), eventsTotal, log)

	httpClient := &http.Client{Timeout: 5 * time.Second}
	index := elastic.NewIndex(es)
	opts := []app.SearcherOption{app.WithMarkets(cfg.Markets)}

	var recorder app.AnalyticsRecorder
	var reporter app.AnalyticsReporter
	if cfg.Analytics.Enabled {
		store := elastic.NewAnalyticsStore(es)
		recorder, reporter = app.NewAnalyticsRecorder(store, log), app.NewAnalyticsReporter(store)
		opts = append(opts, app.WithAnalytics(recorder, bus))
	}

	tokenKey := cfg.Ads.TokenKey
	if tokenKey == "" && local {
		tokenKey = localAdsTokenKey
	}
	tokens, err := adtoken.New(tokenKey)
	if err != nil && cfg.Ads.Enabled {
		return fmt.Errorf("ads: set ADS_TOKEN_KEY: %w", err)
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
	deps := httpapi.Deps{
		Searcher:  app.NewProductSearcher(index, emb, log, opts...),
		Suggester: app.NewSuggester(index),
		Analytics: reporter,
		Synonyms:  app.NewSynonymCurator(admin, log),
	}
	// Without a token key (ads off outside local) organic clicks are still recorded; ad tokens are rejected.
	deps.Clicks = app.NewClickRecorder(tokens, bus, recorder, max(cfg.Analytics.ClickDedupe, time.Minute), log)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}
	openapi, err := search.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name:     "Search API",
		Addr:     ":" + strconv.Itoa(cfg.Port),
		OpenAPI:  openapi,
		DocsOn:   cfg.DocsEnabled,
		Checks:   []httpserver.ReadinessCheck{readiness{index: index}},
		Logger:   log,
		Registry: registry,
	})
	httpapi.New(deps).Register(srv.Mux(), auth.Middleware(verifier, true))

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error { return srv.Run(gctx) })
	g.Go(func() error { return bus.Run(gctx) })
	if recorder != nil {
		every := cfg.Analytics.FlushInterval
		if every <= 0 {
			every = 10 * time.Second
		}
		retention := time.Duration(cfg.Analytics.RetentionDays) * 24 * time.Hour
		g.Go(func() error { return recorder.Run(gctx, every, retention, cfg.Markets) })
	}
	if err := g.Wait(); err != nil {
		return fmt.Errorf("search: %w", err)
	}
	return nil
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
// and ensures the analytics indices. Failures are logged: search keeps serving with whatever is in place.
func bootstrap(ctx context.Context, cfg config.Config, admin port.IndexAdmin, es *elasticsearch.Client,
	synonyms domain.SynonymSet, local bool, log *slog.Logger,
) {
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
		if cfg.Analytics.Enabled {
			if err := elastic.EnsureAnalyticsIndex(ctx, es, m); err != nil {
				log.Warn("could not ensure the analytics index", "market", m, "error", err)
			}
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

type readiness struct{ index port.Index }

func (readiness) Name() string { return "elasticsearch" }
func (r readiness) Check(ctx context.Context) error {
	if err := r.index.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
