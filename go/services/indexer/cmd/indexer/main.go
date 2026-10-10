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

	"github.com/elastic/go-elasticsearch/v9"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"golang.org/x/sync/errgroup"

	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/catalog"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/elastic"
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
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)

	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	esOpts := []elasticsearch.Option{elasticsearch.WithAddresses(cfg.Elasticsearch.URLs...), elasticsearch.WithRetry(3)}
	switch {
	case cfg.Elasticsearch.APIKey != "":
		esOpts = append(esOpts, elasticsearch.WithAPIKey(cfg.Elasticsearch.APIKey))
	case cfg.Elasticsearch.Username != "":
		esOpts = append(esOpts, elasticsearch.WithBasicAuth(cfg.Elasticsearch.Username, cfg.Elasticsearch.Password))
	}
	es, err := elasticsearch.New(esOpts...)
	if err != nil {
		return fmt.Errorf("elasticsearch client: %w", err)
	}
	if err := searchindex.NewAdmin(es).EnsureIndex(ctx, cfg.Market); err != nil {
		return fmt.Errorf("ensure index: %w", err)
	}

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	processed := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "indexer_events_total", Help: "Events handled by type and outcome.",
	}, []string{"type", "outcome"})
	registry.MustRegister(processed)

	index := elastic.NewIndex(es, cfg.Elasticsearch.Refresh)
	httpClient := &http.Client{Timeout: 5 * time.Second}
	projector := app.NewProjector(index, catalog.NewClient(cfg.CatalogURL, httpClient),
		sellers.NewStaticDirectory(cfg.SellerNames, "Marketplace seller"), cfg.Market, log)

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
		Checks: []httpserver.ReadinessCheck{readiness{index: index}}, Logger: log, Registry: registry,
	})

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error { return srv.Run(gctx) })
	g.Go(func() error { return consumer.Run(gctx, handler) })
	if err := g.Wait(); err != nil {
		return fmt.Errorf("indexer: %w", err)
	}
	return nil
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

type readiness struct{ index port.ProductIndex }

func (readiness) Name() string { return "elasticsearch" }

func (r readiness) Check(ctx context.Context) error {
	if err := r.index.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
