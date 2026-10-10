// Command media issues direct uploads (UploadThing, S3-compatible, Azure Blob) and inspects uploaded files.
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
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/authn"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/azblobstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/s3store"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/uploadthing"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

const serviceName = "media"

func main() {
	if err := run(); err != nil {
		slog.Error("media exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(media.Files)
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
	if err := pgrepo.Migrate(ctx, pool, media.Files); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}

	httpClient := &http.Client{Timeout: 15 * time.Second}
	named := map[string]port.ObjectStore{}
	for name, sc := range cfg.Stores {
		st, err := buildStore(sc, cfg, httpClient)
		if err != nil {
			return fmt.Errorf("store %s: %w", name, err)
		}
		named[name] = st
	}
	stores := map[domain.Purpose]port.ObjectStore{}
	for purpose, name := range cfg.Purposes {
		st, ok := named[name]
		if !ok {
			return fmt.Errorf("purpose %s refers to unknown store %s", purpose, name)
		}
		stores[domain.Purpose(purpose)] = st
	}
	repo := pgrepo.New(pool)
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000),
		domain.LinkPolicy{Default: cfg.Links.DefaultTTL, MaxLong: cfg.Links.MaxLongTTL})

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		// Tokens for media, plus the own client-credentials tokens of the service clients the purposes name.
		verifier = authn.NewVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, domain.ServiceClients(), httpClient)
	}
	openapi, err := media.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Media API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{repo: repo}}, Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, false))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

func buildStore(sc config.Store, cfg config.Config, client *http.Client) (port.ObjectStore, error) {
	switch sc.Kind {
	case "uploadthing":
		return uploadthing.New(uploadthing.Options{APIKey: cfg.UploadThing.APIKey, AppID: cfg.UploadThing.AppID}, client), nil
	case "s3":
		return s3store.New(s3store.Options{Name: sc.Name, Endpoint: sc.Endpoint, Region: sc.Region, Bucket: sc.Bucket,
			AccessKey: os.Getenv(sc.AccessKeyEnv), SecretKey: os.Getenv(sc.SecretKeyEnv),
			PublicBaseURL: sc.PublicBaseURL, PathStyle: sc.PathStyle}), nil
	case "azure-blob":
		st, err := azblobstore.New(azblobstore.Options{Account: sc.Account, AccountKey: os.Getenv(sc.SecretKeyEnv),
			Container: sc.Bucket, PublicBaseURL: sc.PublicBaseURL})
		if err != nil {
			return nil, fmt.Errorf("azure: %w", err)
		}
		return st, nil
	default:
		return nil, fmt.Errorf("unknown store kind %q", sc.Kind)
	}
}

type readiness struct{ repo port.AssetRepository }

func (readiness) Name() string { return "postgres" }

func (r readiness) Check(ctx context.Context) error {
	if err := r.repo.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
