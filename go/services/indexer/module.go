package indexer

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/catalog"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/wiring"
)

// Name is the module name (config prefix INDEXER_, metrics label module="indexer").
const Name = "indexer"

// Deps and Module are the shared module contract (go/pkg/module).
type (
	Deps   = module.Deps
	Module = module.Module
)

// LoadConfig reads config/<APP_ENV>.yaml with the environment overrides (shared by the consumer and `indexer reindex`).
func LoadConfig(host Deps) (config.Config, string, error) {
	loader := host.Loader(Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return config.Config{}, "", fmt.Errorf("load config: %w", err)
	}
	cfg.Resolve(loader.Env())
	return cfg, loader.Env(), nil
}

// New builds the indexer: a Kafka consumer that projects catalog, offers, inventory and seller-org events into the search
// index. Nothing runs until Start.
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

func build(host Deps, ctx context.Context, closers *module.Closers) (Module, error) {
	cfg, env, err := LoadConfig(host)
	if err != nil {
		return Module{}, err
	}
	log := host.Log(Name, env)

	be, err := wiring.NewBackend(cfg)
	if err != nil {
		return Module{}, err
	}
	log.Info("search engine", "provider", be.Name)

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	processed := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "indexer_events_total", Help: "Events handled by type and outcome.",
	}, []string{"type", "outcome"})
	registry.MustRegister(processed)

	index := be.Index
	httpClient := &http.Client{Timeout: 5 * time.Second}
	directory := wiring.NewSellerDirectory(cfg, httpClient, log)
	embeddings := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "indexer_embeddings_total", Help: "Product embeddings requested from ai-gateway by outcome.",
	}, []string{"outcome"})
	registry.MustRegister(embeddings)
	var projectorOpts []app.ProjectorOption
	var backfill app.EmbeddingBackfill
	if e := wiring.NewEmbedder(cfg, log); e != nil {
		counted := wiring.CountingEmbedder{Next: e, Counter: embeddings}
		projectorOpts = append(projectorOpts, app.WithEmbedder(counted, cfg.Embeddings.Timeout))
		backfill = app.NewEmbeddingBackfill(index, counted, cfg.Market, cfg.Embeddings.BackfillBatch,
			cfg.Embeddings.Timeout, log)
	}
	projector := app.NewProjector(index, catalog.NewClient(cfg.CatalogURL, httpClient), directory, cfg.Market, log,
		projectorOpts...)

	dlq, err := events.NewKafkaPublisher(cfg.Kafka.Brokers)
	if err != nil {
		return Module{}, fmt.Errorf("dlq publisher: %w", err)
	}
	closers.AddFunc(dlq.Close)
	consumer, err := events.NewKafkaConsumer(cfg.Kafka.Brokers, cfg.Kafka.Group, cfg.Kafka.Topics...)
	if err != nil {
		return Module{}, fmt.Errorf("consumer: %w", err)
	}
	closers.AddFunc(consumer.Close)
	handler := app.NewRetryingHandler(wiring.Counting{Next: projector, Counter: processed}, dlq, cfg.Retry.Attempts,
		time.Duration(cfg.Retry.BackoffMs)*time.Millisecond, log)

	openapi, err := Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return Module{}, fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Indexer", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{wiring.Readiness{Index: index, Engine: be.Name}}, Logger: log, Registry: registry,
	})

	var bg module.Background
	bg.Go(func(ctx context.Context) error { return consumer.Run(ctx, handler) })
	bg.Go(func(ctx context.Context) error { return directory.RunRefresh(ctx, cfg.SellerDirectory.RefreshInterval) })
	if backfill != nil {
		bg.Go(func(ctx context.Context) error { return backfill.Run(ctx, cfg.Embeddings.BackfillInterval) })
	}
	mod := module.FromServer(Name, srv, log, env, module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio}, openapi)
	mod.Start = func(ctx context.Context) error {
		if err := be.EnsureIndex(ctx, cfg.Market); err != nil {
			return fmt.Errorf("ensure index: %w", err)
		}
		// S213: warm seller names before consuming, so a restart never changes indexed seller names.
		wiring.WarmDirectory(ctx, directory, cfg.SellerDirectory.WarmTimeout, log)
		return bg.Start(ctx)
	}
	mod.Fatal = bg.Fatal()
	closers.Add(bg.Wait)
	return mod, nil
}
