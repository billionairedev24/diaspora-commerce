// Command media issues direct uploads (UploadThing, S3-compatible, Azure Blob) and inspects uploaded files.
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

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/aimoderation"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/authn"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/azblobstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/imageproc"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/linktoken"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/s3store"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/uploadthing"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/urlfetch"
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
	cfg.Resolve(loader.Env())
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)
	if err := resolveUploadThing(&cfg); err != nil {
		return err
	}
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
	// S308: catalog-service imports the images of bulk product uploads from their URLs (public addresses only).
	opts := []app.Option{app.WithLogger(log), moderation(cfg, log), app.WithFetcher(urlfetch.New(urlfetch.Options{}))}
	if o, err := links(cfg, log); err != nil {
		return err
	} else if o != nil {
		opts = append(opts, o)
	}
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000),
		domain.LinkPolicy{Default: cfg.Links.DefaultTTL, MaxLong: cfg.Links.MaxLongTTL}, opts...)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		// Tokens for media, the own client-credentials tokens of the service clients the purposes name and of the
		// reader clients (delivery-runs-service, media:read), and user tokens forwarded by the BFF (S250).
		verifier = authn.NewVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, domain.AcceptedServiceClients(),
			cfg.Auth.ForwardedAudiences, httpClient)
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

// resolveUploadThing fills the API key and app id from UPLOADTHING_TOKEN when they are not set separately.
func resolveUploadThing(cfg *config.Config) error {
	ut := &cfg.UploadThing
	if ut.Token == "" || ut.APIKey != "" {
		return nil
	}
	tok, err := uploadthing.ParseToken(ut.Token)
	if err != nil {
		return fmt.Errorf("UPLOADTHING_TOKEN: %w", err)
	}
	ut.APIKey = tok.APIKey
	if ut.AppID == "" {
		ut.AppID = tok.AppID
	}
	return nil
}

// moderation wires the S030 gate for public images: metadata stripping, perceptual hashes and, when enabled, the
// AI hook on ai-gateway.
func moderation(cfg config.Config, log *slog.Logger) app.Option {
	m := cfg.Moderation
	maxPixels := m.MaxPixels
	if maxPixels <= 0 {
		maxPixels = 40_000_000
	}
	var moderator port.ImageModerator
	if m.AI.Enabled && m.AI.BaseURL != "" {
		if m.AI.TokenURL != "" && m.AI.ClientSecret == "" {
			log.Warn("MEDIA_CLIENT_SECRET not set: AI moderation calls are unauthenticated")
		}
		timeout := m.AI.Timeout
		if timeout <= 0 {
			timeout = 10 * time.Second
		}
		moderator = aimoderation.New(aimoderation.Config{BaseURL: m.AI.BaseURL, TokenURL: m.AI.TokenURL,
			ClientID: m.AI.ClientID, ClientSecret: m.AI.ClientSecret, Scopes: m.AI.Scopes}, &http.Client{Timeout: timeout})
	} else {
		log.Info("AI image moderation off: public images are checked by rules and duplicates only")
	}
	return app.WithModeration(imageproc.New(maxPixels), moderator,
		domain.ModerationPolicy{AIFailOpen: cfg.FailOpen(), DuplicateDistance: m.DuplicateDistance}, m.AI.Timeout)
}

// links enables media-hosted long-lived links when a signing key is configured.
func links(cfg config.Config, log *slog.Logger) (app.Option, error) {
	if cfg.Links.SigningKey == "" {
		log.Warn("LINK_SIGNING_KEY not set: long document links are storage-signed URLs (7 days at most on S3 and UploadThing)")
		return nil, nil
	}
	if cfg.PublicBaseURL == "" {
		return nil, errors.New("links: set PUBLIC_BASE_URL or API_BASE_URL for media-hosted links")
	}
	signer, err := linktoken.New(append([]string{cfg.Links.SigningKey}, cfg.Links.PreviousKeys...)...)
	if err != nil {
		return nil, fmt.Errorf("LINK_SIGNING_KEY: %w", err)
	}
	return app.WithLinks(signer, cfg.PublicBaseURL, cfg.Links.RedirectTTL), nil
}

func buildStore(sc config.Store, cfg config.Config, client *http.Client) (port.ObjectStore, error) {
	switch sc.Kind {
	case "uploadthing":
		return uploadthing.New(uploadthing.Options{APIKey: cfg.UploadThing.APIKey, AppID: cfg.UploadThing.AppID}, client), nil
	case "s3":
		return s3store.New(s3store.Options{Name: sc.Name, Endpoint: sc.ResolvedEndpoint(os.Getenv), Region: sc.Region, Bucket: sc.Bucket,
			AccessKey: os.Getenv(sc.AccessKeyEnv), SecretKey: os.Getenv(sc.SecretKeyEnv),
			PublicBaseURL: sc.ResolvedPublicBaseURL(os.Getenv), PathStyle: sc.PathStyle}), nil
	case "azure-blob":
		st, err := azblobstore.New(azblobstore.Options{Account: sc.Account, AccountKey: os.Getenv(sc.SecretKeyEnv),
			Container: sc.Bucket, PublicBaseURL: sc.ResolvedPublicBaseURL(os.Getenv)})
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
