package authzsync

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/adapter/openfga"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/port"
)

// Name is the module name (config prefix, metrics label module="authz-sync").
const Name = "authz-sync"

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

func build(host Deps, ctx context.Context, closers *module.Closers) (Module, error) {
	loader := host.Loader(Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return Module{}, fmt.Errorf("load config: %w", err)
	}
	log := host.Log(Name, loader.Env())

	fga := openfga.NewClient(cfg.OpenFGA.APIURL, cfg.OpenFGA.APIToken, cfg.OpenFGA.StoreID,
		&http.Client{Timeout: cfg.OpenFGA.Timeout})

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	processed := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "authz_sync_events_total", Help: "Events handled by type and outcome.",
	}, []string{"type", "outcome"})
	registry.MustRegister(processed)

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
	handler := app.NewRetryingHandler(counting{next: app.NewSyncer(fga, cfg.RetailOrgID, log), counter: processed},
		dlq, time.Duration(cfg.Retry.BackoffMs)*time.Millisecond, time.Duration(cfg.Retry.MaxBackoffMs)*time.Millisecond,
		log)

	openapi, err := Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return Module{}, fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Authz sync", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{store: fga}}, Logger: log, Registry: registry,
	})

	var bg module.Background
	bg.Go(func(ctx context.Context) error { return consumer.Run(ctx, handler) })
	closers.Add(bg.Wait)
	m := module.FromServer(Name, srv, log, loader.Env(), module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio}, openapi)
	m.Start = func(ctx context.Context) error {
		// Store and model are provisioned (retrying while OpenFGA starts) before the consumer runs.
		if err := provision(ctx, fga, cfg, log); err != nil {
			return err
		}
		if err := seedServiceClients(ctx, fga, cfg, log); err != nil {
			return err
		}
		return bg.Start(ctx)
	}
	m.Fatal = bg.Fatal()
	return m, nil
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
	model, err := Files.ReadFile("model/model.json")
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

// seedServiceClients writes the platform `service` tuples of the first-party service clients (S266).
func seedServiceClients(ctx context.Context, store port.TupleStore, cfg config.Config, log *slog.Logger) error {
	change := domain.ServiceClientChange(cfg.ServiceClients)
	if change.Empty() {
		return nil
	}
	if err := store.Write(ctx, change); err != nil {
		return fmt.Errorf("seed service clients: %w", err)
	}
	log.Info("service client tuples seeded", "clients", len(change.Writes))
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
