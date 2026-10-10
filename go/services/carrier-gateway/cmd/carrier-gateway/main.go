// Command carrier-gateway integrates USPS, UPS, FedEx and DHL Express directly (no aggregator): rate shopping,
// labels, voids, tracking ingestion (webhooks + USPS polling) with an outbox to Kafka, pickups, pickup
// locations and carrier-side address validation.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/accounts"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/dhl"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/fedex"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/ups"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/usps"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/objectstore/fsstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/objectstore/s3store"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/pgstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/quotecache"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

const serviceName = "carrier-gateway"

func main() {
	if err := run(); err != nil {
		slog.Error("carrier-gateway exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(carriergateway.Files)
	var cfg config.Config
	cfg.USPS.Paths, cfg.UPS.Paths, cfg.FedEx.Paths, cfg.DHL.Paths = usps.DefaultPaths(), ups.DefaultPaths(), fedex.DefaultPaths(), dhl.DefaultPaths()
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	cfg.Resolve()
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
	if err := pgstore.Migrate(ctx, pool, carriergateway.Files); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	store := pgstore.New(pool)

	ropts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return fmt.Errorf("redis url: %w", err)
	}
	rdb := redis.NewClient(ropts)
	defer func() { _ = rdb.Close() }()

	carrierHTTP := &http.Client{Timeout: cfg.CarrierHTTP.Timeout}
	carriers := buildCarriers(cfg, carrierHTTP, log)
	enabled := make([]domain.CarrierCode, 0, len(carriers))
	for code := range carriers {
		enabled = append(enabled, code)
	}
	resolver := accounts.New(cfg.Accounts, enabled)

	var objects port.ObjectStore
	if cfg.LabelStore.Kind == "s3" {
		s3c := cfg.LabelStore.S3
		objects = s3store.New(s3store.Options{Endpoint: s3c.Endpoint, Region: s3c.Region, Bucket: s3c.Bucket,
			AccessKey: s3c.AccessKey, SecretKey: s3c.SecretKey, PathStyle: s3c.PathStyle})
	} else {
		objects = fsstore.New(cfg.LabelStore.Dir)
	}

	pollCarriers := make([]domain.CarrierCode, 0, len(cfg.Polling.Carriers))
	for _, c := range cfg.Polling.Carriers {
		code, err := domain.ParseCarrier(c)
		if err != nil {
			return fmt.Errorf("polling carriers: %w", err)
		}
		pollCarriers = append(pollCarriers, code)
	}
	tracking := app.NewTrackingService(carriers, store, app.PollPolicy{Carriers: pollCarriers, Interval: cfg.Polling.Interval,
		MaxBackoff: cfg.Polling.MaxBackoff, Lease: cfg.Polling.Lease, Batch: cfg.Polling.Batch}, log, time.Now)
	rates := app.NewRateService(carriers, resolver, quotecache.New(rdb, "carrier-gateway:"), cfg.Rates.Budget, cfg.Rates.QuoteTTL, log)
	billing := cfg.LabelBilling()
	if err := billing.Validate(); err != nil {
		return fmt.Errorf("label billing: %w", err)
	}
	labels := app.NewLabelService(carriers, resolver, store, objects, billing, log, time.Now)
	services := httpapi.Services{
		Rates:     rates,
		Labels:    labels,
		Returns:   app.NewReturnLabelService(carriers, rates, labels),
		Tracking:  tracking,
		Pickups:   app.NewPickupService(carriers, resolver, store, store, time.Now),
		Addresses: app.NewAddressService(carriers),
	}

	if cfg.Kafka.Enabled {
		publisher, err := events.NewKafkaPublisher(cfg.Kafka.Brokers)
		if err != nil {
			return fmt.Errorf("kafka: %w", err)
		}
		defer publisher.Close()
		go app.NewDispatcher(store, publisher, cfg.Outbox.Batch, cfg.Outbox.Interval, log).Run(ctx)
	} else {
		log.Warn("kafka disabled: events stay in the outbox until a dispatcher runs")
	}
	if cfg.Polling.Enabled {
		go app.RunPoller(ctx, tracking, cfg.Polling.Tick, log)
	}

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, &http.Client{Timeout: 3 * time.Second})
	}
	openapi, err := carriergateway.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Carrier Gateway API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{
			check{name: "postgres", fn: store.Ping},
			check{name: "valkey", fn: func(ctx context.Context) error { return quotecache.Ping(ctx, rdb) }},
		},
		Logger: log,
	})
	httpapi.New(services).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

// buildCarriers wires every enabled carrier that has credentials; a carrier without credentials is skipped with
// a warning instead of failing every call at runtime.
func buildCarriers(cfg config.Config, client *http.Client, log *slog.Logger) app.Carriers {
	carriers := app.Carriers{}
	webhookBase := strings.TrimSuffix(cfg.PublicBaseURL, "/") + "/v1/webhooks/"
	retries := cfg.CarrierHTTP.MaxRetries
	add := func(code domain.CarrierCode, enabled bool, creds []string, build func() port.Carrier) {
		if !enabled {
			return
		}
		for _, c := range creds {
			if c == "" {
				log.Warn("carrier enabled but credentials missing; skipping", "carrier", code)
				return
			}
		}
		carriers[code] = build()
	}
	add(domain.USPS, cfg.USPS.Enabled, []string{cfg.USPS.ClientID, cfg.USPS.ClientSecret}, func() port.Carrier {
		return usps.New(usps.Options{BaseURL: cfg.USPS.BaseURL, ClientID: cfg.USPS.ClientID, ClientSecret: cfg.USPS.ClientSecret,
			PaymentToken: cfg.USPS.PaymentToken, Paths: cfg.USPS.Paths, MailClasses: cfg.USPS.MailClasses,
			PriceType: cfg.USPS.PriceType, ImageType: cfg.USPS.ImageType, HTTP: client, MaxRetries: retries})
	})
	add(domain.UPS, cfg.UPS.Enabled, []string{cfg.UPS.ClientID, cfg.UPS.ClientSecret}, func() port.Carrier {
		return ups.New(ups.Options{BaseURL: cfg.UPS.BaseURL, ClientID: cfg.UPS.ClientID, ClientSecret: cfg.UPS.ClientSecret,
			AccountNumber: cfg.UPS.Account, Paths: cfg.UPS.Paths, WebhookURL: webhookBase + "ups",
			WebhookCredential: cfg.UPS.WebhookCredential, HTTP: client, MaxRetries: retries})
	})
	add(domain.FedEx, cfg.FedEx.Enabled, []string{cfg.FedEx.ClientID, cfg.FedEx.ClientSecret}, func() port.Carrier {
		return fedex.New(fedex.Options{BaseURL: cfg.FedEx.BaseURL, ClientID: cfg.FedEx.ClientID, ClientSecret: cfg.FedEx.ClientSecret,
			AccountNumber: cfg.FedEx.Account, Paths: cfg.FedEx.Paths, ImageType: cfg.FedEx.ImageType,
			WebhookSecret: cfg.FedEx.WebhookSecret, SignatureHeader: cfg.FedEx.SignatureHeader, HTTP: client, MaxRetries: retries})
	})
	add(domain.DHL, cfg.DHL.Enabled, []string{cfg.DHL.Username, cfg.DHL.Password}, func() port.Carrier {
		return dhl.New(dhl.Options{BaseURL: cfg.DHL.BaseURL, Username: cfg.DHL.Username, Password: cfg.DHL.Password,
			AccountNumber: cfg.DHL.Account, Paths: cfg.DHL.Paths, LabelFormat: cfg.DHL.LabelFormat,
			WebhookSecret: cfg.DHL.WebhookSecret, SignatureHeader: cfg.DHL.SignatureHeader, HTTP: client, MaxRetries: retries})
	})
	return carriers
}

type check struct {
	name string
	fn   func(ctx context.Context) error
}

func (c check) Name() string { return c.name }

func (c check) Check(ctx context.Context) error {
	if err := c.fn(ctx); err != nil {
		return fmt.Errorf("%s: %w", c.name, err)
	}
	return nil
}
