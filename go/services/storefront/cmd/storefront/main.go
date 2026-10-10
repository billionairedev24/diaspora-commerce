// Command storefront serves seller and provider page designs with server-side contrast vetting.
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
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/adapter/cdn"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/port"
)

const serviceName = "storefront"

func main() {
	if err := run(); err != nil {
		slog.Error("storefront exited", "error", err)
		os.Exit(1)
	}
}

type planLimits struct{ cfg config.Config }

func (p planLimits) For(code string) domain.Limits {
	plan, ok := p.cfg.Plans[code]
	if !ok {
		plan = p.cfg.Plans["starter"]
	}
	return domain.Limits{MaxSections: p.cfg.MaxSections, MaxGallery: plan.MaxGallery, MaxFeatured: p.cfg.MaxFeatured,
		MaxTaglineLength: p.cfg.MaxTagline}
}

var _ port.PlanLimits = planLimits{}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(storefront.Files)
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
	if err := pgrepo.Migrate(ctx, pool, storefront.Files); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	httpClient := &http.Client{Timeout: 5 * time.Second}
	purger := cdn.NewNoop()
	if cfg.Cloudflare.ZoneID != "" && cfg.Cloudflare.Token != "" {
		purger = cdn.NewCloudflare(cfg.Cloudflare.ZoneID, cfg.Cloudflare.Token, "", httpClient)
	}
	repo := pgrepo.New(pool)
	svc := app.NewStorefrontService(repo, purger, planLimits{cfg: cfg}, cfg.PublicBaseURL)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, httpClient)
	}
	openapi, err := storefront.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Storefront API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{repo: repo}}, Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, true), auth.Middleware(verifier, false))
	if err := srv.Run(ctx); err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

type readiness struct{ repo port.StorefrontRepository }

func (readiness) Name() string { return "postgres" }

func (r readiness) Check(ctx context.Context) error {
	if err := r.repo.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
