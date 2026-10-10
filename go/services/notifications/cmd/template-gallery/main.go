// Command template-gallery renders every seeded notification template (latest version per key, locale and channel)
// with realistic sample data and writes a self-contained static HTML gallery.
//
// It loads the templates by running the service's real migrations against a throwaway Postgres (Testcontainers, or
// --dsn), renders them with the service's real renderer, and takes sample data from app.NewTemplateSamples: the
// contract-shaped event fixtures run through the real ingest (each rule's Enrich), plus app.SampleData.
//
//	GOWORK=off go run ./cmd/template-gallery [--out build/template-gallery/index.html] [--dsn postgres://…] [--image postgres:17]
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/postgres"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
)

const defaultImage = "postgis/postgis:17-3.5"

type options struct {
	out, dsn, image, repo string
	links                 app.Links
}

func main() {
	var o options
	flag.StringVar(&o.out, "out", filepath.Join("build", "template-gallery", "index.html"), "where to write the gallery")
	flag.StringVar(&o.dsn, "dsn", "", "Postgres to migrate and read (default: a throwaway Testcontainers Postgres)")
	flag.StringVar(&o.image, "image", envOr("NOTIFICATIONS_TEST_PG_IMAGE", defaultImage), "Postgres image for the throwaway database")
	flag.StringVar(&o.repo, "repo", "", "monorepo root, for the AfroTransact tokens and Figtree font (default: found from the working directory)")
	flag.StringVar(&o.links.Storefront, "storefront-url", "https://afrotransact.com", "storefront base URL in sample links")
	flag.StringVar(&o.links.SellerCenter, "seller-center-url", "https://seller.afrotransact.com", "Seller Center base URL in sample links")
	flag.StringVar(&o.links.ProviderHub, "provider-hub-url", "https://provider.afrotransact.com", "Provider Hub base URL in sample links")
	flag.Parse()
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if err := runWithSignals(o, log); err != nil {
		log.Error("template gallery failed", "error", err)
		os.Exit(1)
	}
}

// runWithSignals runs the tool; Ctrl-C cancels it and still terminates the throwaway database.
func runWithSignals(o options, log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	return run(ctx, o, log)
}

func run(ctx context.Context, o options, log *slog.Logger) error {
	dsn, cleanup, err := database(ctx, o, log)
	if err != nil {
		return err
	}
	defer cleanup()
	pool, err := postgres.Open(ctx, dsn)
	if err != nil {
		return fmt.Errorf("open postgres: %w", err)
	}
	defer pool.Close()
	if err := postgres.Migrate(ctx, pool); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	entries, err := galleryEntries(ctx, pool, o.links)
	if err != nil {
		return err
	}
	page := newPageWriter(findAssets(o.repo, log))
	if err := os.MkdirAll(filepath.Dir(o.out), 0o750); err != nil {
		return fmt.Errorf("create output directory: %w", err)
	}
	f, err := os.Create(o.out) //nolint:gosec // G304: the developer chooses where the dev tool writes
	if err != nil {
		return fmt.Errorf("create %s: %w", o.out, err)
	}
	if err := page.Write(f, entries, time.Now().UTC()); err != nil {
		_ = f.Close()
		return fmt.Errorf("write gallery: %w", err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("close %s: %w", o.out, err)
	}
	abs, _ := filepath.Abs(o.out)
	failed := 0
	for _, e := range entries {
		if e.Err != "" {
			failed++
			log.Warn("template failed to render", "key", e.Template.Key, "locale", e.Template.Locale,
				"channel", e.Template.Channel, "version", e.Template.Version, "error", e.Err)
		}
	}
	log.Info("template gallery written", "path", abs, "templates", len(entries), "failed", failed)
	fmt.Println(abs)
	return nil
}

func galleryEntries(ctx context.Context, pool *pgxpool.Pool, links app.Links) ([]app.GalleryEntry, error) {
	samples, err := app.NewTemplateSamples(ctx, links)
	if err != nil {
		return nil, fmt.Errorf("sample data: %w", err)
	}
	entries, err := app.NewTemplateGallery(postgres.NewTemplateStore(pool), app.NewRenderer(), samples).Entries(ctx)
	if err != nil {
		return nil, fmt.Errorf("render templates: %w", err)
	}
	if len(entries) == 0 {
		return nil, errors.New("no templates found: did the migrations run?")
	}
	return entries, nil
}

// database returns the DSN to use: --dsn, or a throwaway Testcontainers Postgres that cleanup terminates.
func database(ctx context.Context, o options, log *slog.Logger) (string, func(), error) {
	if o.dsn != "" {
		return o.dsn, func() {}, nil
	}
	log.Info("starting a throwaway Postgres", "image", o.image)
	ctr, err := tcpostgres.Run(ctx, o.image, tcpostgres.WithDatabase("notifications"), tcpostgres.WithUsername("notifications"),
		tcpostgres.WithPassword("notifications"), tcpostgres.BasicWaitStrategies())
	if err != nil {
		return "", nil, fmt.Errorf("start postgres (Docker needed, or pass --dsn): %w", err)
	}
	cleanup := func() {
		if err := ctr.Terminate(context.WithoutCancel(ctx)); err != nil {
			log.Warn("terminate postgres", "error", err)
		}
	}
	dsn, err := ctr.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		cleanup()
		return "", nil, fmt.Errorf("postgres dsn: %w", err)
	}
	return dsn, cleanup, nil
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
