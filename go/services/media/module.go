package media

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/aimoderation"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/authn"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/azblobstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/imageproc"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/linktoken"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/malwarescan"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/s3store"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/uploadthing"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/urlfetch"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// Name is the module name (config prefix, metrics label module="media").
const Name = "media"

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

// maxPoolConns is this service's share of the database server's connections (see the pool setup in run).
const maxPoolConns = 3

func build(host Deps, ctx context.Context, closers *module.Closers) (Module, error) {
	loader := host.Loader(Files)
	cfg := config.Default()
	if err := loader.Load(&cfg); err != nil {
		return Module{}, fmt.Errorf("load config: %w", err)
	}
	if err := pkgconfig.RequireAuthOutsideLocal(loader.Env(), cfg.Auth.Enabled); err != nil {
		return Module{}, err
	}
	cfg.Resolve(loader.Env())
	if err := cfg.Validate(loader.Env(), host.Getenv); err != nil {
		return Module{}, fmt.Errorf("invalid %s configuration: %w", loader.Env(), err)
	}
	log := host.Log(Name, loader.Env())
	if err := resolveUploadThing(&cfg); err != nil {
		return Module{}, err
	}

	// Explicit, small connection budget: ~15 services share one managed Postgres server (docs/runbooks/ovh-production.md,
	// connection budget). pgx would otherwise default to max(4, NumCPU). A lower pool_max_conns in DB_URL is respected.
	poolCfg, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return Module{}, fmt.Errorf("postgres config: %w", err)
	}
	if poolCfg.MaxConns > maxPoolConns {
		poolCfg.MaxConns = maxPoolConns
	}
	poolCfg.MinConns = 0
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return Module{}, fmt.Errorf("postgres: %w", err)
	}
	closers.AddFunc(pool.Close)
	if err := pgrepo.Migrate(ctx, pool, Files); err != nil {
		return Module{}, fmt.Errorf("migrate: %w", err)
	}

	httpClient := &http.Client{Timeout: 15 * time.Second}
	named := map[string]port.ObjectStore{}
	for name, sc := range cfg.Stores {
		st, err := buildStore(sc, cfg, httpClient, host.Getenv)
		if err != nil {
			return Module{}, fmt.Errorf("store %s: %w", name, err)
		}
		named[name] = st
	}
	stores := map[domain.Purpose]port.ObjectStore{}
	for purpose, name := range cfg.Purposes {
		st, ok := named[name]
		if !ok {
			return Module{}, fmt.Errorf("purpose %s refers to unknown store %s", purpose, name)
		}
		stores[domain.Purpose(purpose)] = st
	}
	repo := pgrepo.New(pool)
	// S308: catalog-service imports the images of bulk product uploads from their URLs (public addresses only).
	opts := []app.Option{app.WithLogger(log), app.WithFetcher(urlfetch.New(urlfetch.Options{})), app.WithLibrary(repo)}
	opts = append(opts, moderation(cfg, log, repo)...)
	if cfg.QuarantineStore != "" {
		qs, ok := named[cfg.QuarantineStore]
		if !ok {
			return Module{}, fmt.Errorf("quarantineStore refers to unknown store %s", cfg.QuarantineStore)
		}
		opts = append(opts, app.WithQuarantine(qs))
	}
	if o, err := scanner(cfg, log); err != nil {
		return Module{}, err
	} else if o != nil {
		opts = append(opts, o)
	}
	if o, err := links(cfg, log); err != nil {
		return Module{}, err
	} else if o != nil {
		opts = append(opts, o)
	}
	opts = append(opts, pictureRules(cfg, loader.Env(), httpClient, log))
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000),
		domain.LinkPolicy{Default: cfg.Links.DefaultTTL, MaxLong: cfg.Links.MaxLongTTL}, opts...)

	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		// Tokens for media, the own client-credentials tokens of the service clients the purposes name and of the
		// reader clients (delivery-runs-service, media:read), and user tokens forwarded by the BFF (S250).
		verifier = authn.NewVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, domain.AcceptedServiceClients(),
			cfg.Auth.ForwardedAudiences, httpClient)
	}
	if err := auth.RequireVerifier(loader.Env(), verifier); err != nil {
		return Module{}, fmt.Errorf("auth: %w", err)
	}
	openapi, err := Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return Module{}, fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name: "Media API", Addr: ":" + strconv.Itoa(cfg.Port), OpenAPI: openapi, DocsOn: cfg.DocsEnabled,
		Checks: []httpserver.ReadinessCheck{readiness{repo: repo}}, Logger: log,
	})
	httpapi.New(svc).Register(srv.Mux(), auth.Middleware(verifier, false))
	return module.FromServer(Name, srv, log, loader.Env(), module.Telemetry{OTLPEndpoint: cfg.Telemetry.OTLPEndpoint, SampleRatio: cfg.Telemetry.SampleRatio}, openapi), nil
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

// moderation wires the S030 gate for public images (metadata stripping, perceptual hashes and, when enabled, the AI
// hook on ai-gateway) and the S388 image checks (thresholds from config, the staff override from the database).
// scanner wires the malware scanner (F10); nil when the provider is none (local only: Validate refuses it in prod).
func scanner(cfg config.Config, log *slog.Logger) (app.Option, error) {
	var sc port.Scanner
	switch cfg.Scan.Provider {
	case "clamd":
		sc = malwarescan.NewClamd(cfg.Scan.Address, cfg.Scan.Timeout)
	case "http":
		sc = malwarescan.NewHTTP(cfg.Scan.URL, cfg.Scan.Token, cfg.Scan.Timeout)
	case "", "none":
		log.Warn("malware scanning is OFF (scan.provider none): uploads are not scanned")
		return nil, nil
	default:
		return nil, fmt.Errorf("scan.provider %q is not clamd, http or none", cfg.Scan.Provider)
	}
	log.Info("malware scanning on", "provider", cfg.Scan.Provider, "documents", cfg.Scan.Documents, "images", cfg.Scan.Images)
	return app.WithScanner(sc, app.ScanPolicy{Documents: app.ScanMode(cfg.Scan.Documents), Images: app.ScanMode(cfg.Scan.Images)}), nil
}

func moderation(cfg config.Config, log *slog.Logger, repo port.ImageRulesStore) []app.Option {
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
	return []app.Option{
		app.WithModeration(imageproc.New(maxPixels), moderator,
			domain.ModerationPolicy{AIFailOpen: cfg.FailOpen(), DuplicateDistance: m.DuplicateDistance}, m.AI.Timeout),
		app.WithImageRules(cfg.Images, imageproc.NewAnalyzer(maxPixels), repo),
	}
}

// pictureRules reads the platform picture rules from catalog (cached 30 s); without a catalog URL the environment's default
// profile is used as it is.
func pictureRules(cfg config.Config, env string, httpClient *http.Client, log *slog.Logger) app.Option {
	fallback := picturerules.FromEnv(env, cfg.PictureRules.Profile)
	log.Info("picture rules", "fallbackProfile", string(fallback.Profile), "catalogUrl", cfg.PictureRules.CatalogURL)
	if cfg.PictureRules.CatalogURL == "" {
		return app.WithPictureRules(picturerules.Static(fallback))
	}
	return app.WithPictureRules(picturerules.NewClient(cfg.PictureRules.CatalogURL, httpClient, picturerules.DefaultTTL, fallback))
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

func buildStore(sc config.Store, cfg config.Config, client *http.Client, getenv func(string) string) (port.ObjectStore, error) {
	switch sc.Kind {
	case "uploadthing":
		return uploadthing.New(uploadthing.Options{APIKey: cfg.UploadThing.APIKey, AppID: cfg.UploadThing.AppID}, client), nil
	case "s3":
		return s3store.New(s3store.Options{Name: sc.Name, Endpoint: sc.ResolvedEndpoint(getenv), Region: sc.Region, Bucket: sc.ResolvedBucket(getenv),
			AccessKey: getenv(sc.AccessKeyEnv), SecretKey: getenv(sc.SecretKeyEnv),
			PublicBaseURL: sc.ResolvedPublicBaseURL(getenv), PathStyle: sc.PathStyle}), nil
	case "azure-blob":
		st, err := azblobstore.New(azblobstore.Options{Account: sc.Account, AccountKey: getenv(sc.SecretKeyEnv),
			Container: sc.ResolvedBucket(getenv), PublicBaseURL: sc.ResolvedPublicBaseURL(getenv)})
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
