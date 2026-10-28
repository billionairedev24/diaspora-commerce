// Command indexer projects catalog and inventory events into the Elasticsearch product read model.
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

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"golang.org/x/sync/errgroup"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/catalog"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/embedder"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sellers"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

const serviceName = "indexer"

func main() {
	if err := run(); err != nil {
		slog.Error("indexer exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(indexer.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	cfg.Resolve(loader.Env())
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)

	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	be, err := newBackend(cfg)
	if err != nil {
		return err
	}
	log.Info("search engine", "provider", be.name)
	if err := be.ensureIndex(ctx, cfg.Market); err != nil {
		return fmt.Errorf("ensure index: %w", err)
	}

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	processed := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "indexer_events_total", Help: "Events handled by type and outcome.",
	}, []string{"type", "outcome"})
	registry.MustRegister(processed)

	index := be.index
	httpClient := &http.Client{Timeout: 5 * time.Second}
	directory := newSellerDirectory(cfg, httpClient, log)
	// S213: warm seller names before consuming, so a restart never changes indexed seller names.
	warmDirectory(ctx, directory, cfg.SellerDirectory.WarmTimeout, log)
	embeddings := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "indexer_embeddings_total", Help: "Product embeddings requested from ai-gateway by outcome.",
	}, []string{"outcome"})
	registry.MustRegister(embeddings)
	var projectorOpts []app.ProjectorOption
	var backfill app.EmbeddingBackfill
	if e := newEmbedder(cfg, log); e != nil {
		counted := countingEmbedder{next: e, counter: embeddings}
		projectorOpts = append(projectorOpts, app.WithEmbedder(counted, cfg.Embeddings.Timeout))
		backfill = app.NewEmbeddingBackfill(index, counted, cfg.Market, cfg.Embeddings.BackfillBatch,
			cfg.Embeddings.Timeout, log)
	}
	projector := app.NewProjector(index, catalog.NewClient(cfg.CatalogURL, httpClient), directory, cfg.Market, log,
		projectorOpts...)

	dlq, err := events.NewKafkaPublisher(cfg.Kafka.Brokers)
	if err != nil {
		return fmt.Errorf("dlq publisher: %w", err)
	}
	defer dlq.Close()
	consumer, err := events.NewKafkaConsumer(cfg.Kafka.Brokers, cfg.Kafka.Group, cfg.Kafka.Topics...)
	if err != nil {
		return fmt.Errorf("consumer: %w", err)
	}
	defer consumer.Close()
	handler := app.NewRetryingHandler(counting{next: projector, counter: processed}, dlq, cfg.Retry.Attempts,
		time.Duration(cfg.Retry.BackoffMs)*time.Millisecond, log)

	openapi, err := indexer.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Indexer", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{index: index, engine: be.name}}, Logger: log, Registry: registry,
	})

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error { return srv.Run(gctx) })
	g.Go(func() error { return consumer.Run(gctx, handler) })
	g.Go(func() error { return directory.RunRefresh(gctx, cfg.SellerDirectory.RefreshInterval) })
	if backfill != nil {
		g.Go(func() error { return backfill.Run(gctx, cfg.Embeddings.BackfillInterval) })
	}
	if err := g.Wait(); err != nil {
		return fmt.Errorf("indexer: %w", err)
	}
	return nil
}

const fallbackSellerName = "Marketplace seller"

func newSellerDirectory(cfg config.Config, httpClient *http.Client, log *slog.Logger) sellers.Directory {
	sd := cfg.SellerDirectory
	if !sd.Enabled || sd.BaseURL == "" {
		log.Warn("seller directory disabled: names come from configuration and seller.org.* events only")
		return sellers.NewDirectory(cfg.SellerNames, fallbackSellerName, nil, sellers.Options{Log: log})
	}
	if sd.TokenURL != "" && sd.ClientSecret == "" {
		log.Warn("INDEXER_CLIENT_SECRET not set: seller directory calls are unauthenticated")
	}
	source := sellers.NewClient(sellers.ClientConfig{BaseURL: sd.BaseURL, TokenURL: sd.TokenURL, ClientID: sd.ClientID,
		ClientSecret: sd.ClientSecret, Scopes: sd.Scopes}, httpClient)
	return sellers.NewDirectory(cfg.SellerNames, fallbackSellerName, source,
		sellers.Options{PageSize: sd.PageSize, Log: log})
}

// warmDirectory retries the warm with backoff until it succeeds or timeout passes; the consumer then starts either
// way (unknown sellers keep the name already indexed, and are looked up on their next event).
func warmDirectory(ctx context.Context, d sellers.Directory, timeout time.Duration, log *slog.Logger) {
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	wctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	backoff := 500 * time.Millisecond
	for {
		n, err := d.Warm(wctx)
		if err == nil {
			log.Info("seller directory warmed", "names", n)
			return
		}
		select {
		case <-wctx.Done():
			log.Error("seller directory not warmed; starting with known names only", "names", n, "error", err)
			return
		case <-time.After(backoff):
			backoff = min(backoff*2, 8*time.Second)
		}
	}
}

// newEmbedder returns the ai-gateway embedder (indexer-service client, scope ai:use), or nil when embeddings are off.
func newEmbedder(cfg config.Config, log *slog.Logger) port.Embedder {
	e := cfg.Embeddings
	if !e.Enabled || e.BaseURL == "" {
		log.Info("embeddings off: products are indexed for lexical search only")
		return nil
	}
	sd := cfg.SellerDirectory
	if sd.TokenURL != "" && sd.ClientSecret == "" {
		log.Warn("INDEXER_CLIENT_SECRET not set: embedding calls are unauthenticated")
	}
	timeout := e.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	return embedder.New(embedder.Config{BaseURL: e.BaseURL, TokenURL: sd.TokenURL, ClientID: sd.ClientID,
		ClientSecret: sd.ClientSecret, Scopes: e.Scopes, CacheSize: e.CacheSize}, &http.Client{Timeout: timeout})
}

type countingEmbedder struct {
	next    port.Embedder
	counter *prometheus.CounterVec
}

func (c countingEmbedder) Embed(ctx context.Context, text string) ([]float32, error) {
	v, err := c.next.Embed(ctx, text)
	if err != nil {
		c.counter.WithLabelValues("error").Inc()
		return nil, fmt.Errorf("embed: %w", err)
	}
	c.counter.WithLabelValues("ok").Inc()
	return v, nil
}

type counting struct {
	next    events.Handler
	counter *prometheus.CounterVec
}

func (c counting) Handle(ctx context.Context, e events.Envelope) error {
	if err := c.next.Handle(ctx, e); err != nil {
		c.counter.WithLabelValues(e.Type, "error").Inc()
		return fmt.Errorf("project %s: %w", e.Type, err)
	}
	c.counter.WithLabelValues(e.Type, "ok").Inc()
	return nil
}

type readiness struct {
	index  port.ProductIndex
	engine string
}

func (r readiness) Name() string { return r.engine }

func (r readiness) Check(ctx context.Context) error {
	if err := r.index.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
