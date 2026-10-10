// Command authz-sync keeps OpenFGA relationship tuples in sync with identity memberships and seller organisations.
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
	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/adapter/openfga"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/port"
)

const serviceName = "authz-sync"

func main() {
	if err := run(); err != nil {
		slog.Error("authz-sync exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(authzsync.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)

	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint,
		cfg.Telemetry.SampleRatio)
	if err != nil {
		return fmt.Errorf("tracing: %w", err)
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	fga := openfga.NewClient(cfg.OpenFGA.APIURL, cfg.OpenFGA.APIToken, cfg.OpenFGA.StoreID,
		&http.Client{Timeout: cfg.OpenFGA.Timeout})
	if err := provision(ctx, fga, cfg, log); err != nil {
		return err
	}

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	processed := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "authz_sync_events_total", Help: "Events handled by type and outcome.",
	}, []string{"type", "outcome"})
	registry.MustRegister(processed)

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
	handler := app.NewRetryingHandler(counting{next: app.NewSyncer(fga, cfg.RetailOrgID, log), counter: processed},
		dlq, time.Duration(cfg.Retry.BackoffMs)*time.Millisecond, time.Duration(cfg.Retry.MaxBackoffMs)*time.Millisecond,
		log)

	openapi, err := authzsync.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Authz sync", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{store: fga}}, Logger: log, Registry: registry,
	})

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error { return srv.Run(gctx) })
	g.Go(func() error { return consumer.Run(gctx, handler) })
	if err := g.Wait(); err != nil {
		return fmt.Errorf("authz-sync: %w", err)
	}
	return nil
}

// provision selects (or creates) the store and deploys the embedded model when it changed, retrying while OpenFGA
// starts. Java services look the store up by the same name and check against its latest model.
func provision(ctx context.Context, fga openfga.Client, cfg config.Config, log *slog.Logger) error {
	backoff := 500 * time.Millisecond
	for {
		err := provisionOnce(ctx, fga, cfg, log)
		if err == nil {
			return nil
		}
		log.Warn("openfga not ready", "error", err, "retry_in", backoff)
		select {
		case <-ctx.Done():
			return fmt.Errorf("provision openfga: %w", err)
		case <-time.After(backoff):
			backoff = min(backoff*2, 15*time.Second)
		}
	}
}

func provisionOnce(ctx context.Context, fga openfga.Client, cfg config.Config, log *slog.Logger) error {
	if cfg.OpenFGA.StoreID == "" {
		id, err := fga.EnsureStore(ctx, cfg.OpenFGA.StoreName)
		if err != nil {
			return fmt.Errorf("store %s: %w", cfg.OpenFGA.StoreName, err)
		}
		fga.UseStore(id)
		log.Info("openfga store selected", "store", cfg.OpenFGA.StoreName, "id", id)
	}
	if !cfg.OpenFGA.ManageModel {
		return nil
	}
	model, err := authzsync.Files.ReadFile("model/model.json")
	if err != nil {
		return fmt.Errorf("read model: %w", err)
	}
	id, written, err := fga.EnsureModel(ctx, model)
	if err != nil {
		return fmt.Errorf("model: %w", err)
	}
	log.Info("openfga model", "id", id, "written", written)
	return nil
}

type counting struct {
	next    events.Handler
	counter *prometheus.CounterVec
}

func (c counting) Handle(ctx context.Context, e events.Envelope) error {
	if err := c.next.Handle(ctx, e); err != nil {
		c.counter.WithLabelValues(e.Type, "error").Inc()
		return fmt.Errorf("sync %s: %w", e.Type, err)
	}
	c.counter.WithLabelValues(e.Type, "ok").Inc()
	return nil
}

type readiness struct{ store port.TupleStore }

func (readiness) Name() string { return "openfga" }

func (r readiness) Check(ctx context.Context) error {
	if err := r.store.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
