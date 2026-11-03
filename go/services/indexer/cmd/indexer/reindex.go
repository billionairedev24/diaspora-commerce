package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/catalog"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sources"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
)

// reindexCommand is the subcommand: `indexer reindex [flags]`.
const reindexCommand = "reindex"

// reindexArgs are the parsed flags of `indexer reindex`.
type reindexArgs struct {
	Markets            []string
	Batch              int
	Concurrency        int
	DryRun             bool
	Since              time.Time
	After              string
	Statuses           []string
	BackfillEmbeddings bool
}

const reindexUsage = `Usage: indexer reindex [flags]

Rebuilds the product search index from the sources of truth (catalog products and ratings, offers' buy boxes,
inventory stock, seller names), not from Kafka. Safe to re-run: every write is version guarded, so a live indexer's
newer data is never overwritten. Uses the indexer's configuration and environment (SEARCH_PROVIDER, MEILISEARCH_URL,
MEILISEARCH_API_KEY, ES_*, CATALOG_URL, OFFERS_URL, INVENTORY_URL, SELLER_URL, AUTH_TOKEN_URL, INDEXER_CLIENT_SECRET).
Exits non-zero when anything failed.

Flags:
`

// parseReindexArgs parses the flags; markets default to the configured market.
func parseReindexArgs(args []string, cfg config.Config, now time.Time, out io.Writer) (reindexArgs, error) {
	fs := flag.NewFlagSet(reindexCommand, flag.ContinueOnError)
	fs.SetOutput(out)
	fs.Usage = func() {
		_, _ = fmt.Fprint(out, reindexUsage)
		fs.PrintDefaults()
	}
	var a reindexArgs
	var markets, since, statuses string
	fs.StringVar(&markets, "market", "", "market(s) to rebuild, comma separated (default: the configured MARKET, "+cfg.Market+")")
	fs.IntVar(&a.Batch, "batch", cfg.Reindex.Batch, "products per catalog page (1-200)")
	fs.IntVar(&a.Concurrency, "concurrency", cfg.Reindex.Concurrency, "products projected at once (1-16)")
	fs.BoolVar(&a.DryRun, "dry-run", false, "read the catalog and count; write nothing (not even the index settings)")
	fs.StringVar(&since, "since", "", "only products changed since this time: RFC 3339, a date (2006-01-02) or a duration such as 36h")
	fs.StringVar(&a.After, "after", "", "resume after this catalog cursor (printed when a run is interrupted)")
	fs.StringVar(&statuses, "status", "LIVE", "catalog statuses to index, comma separated (LIVE; add PAUSED,TAKEN_DOWN to also refresh documents that left LIVE)")
	fs.BoolVar(&a.BackfillEmbeddings, "backfill-embeddings", false, "afterwards embed the documents without a vector (needs EMBEDDINGS_ENABLED and AI_GATEWAY_URL); the running indexer does this on its own every EMBEDDINGS_BACKFILL_INTERVAL")
	if err := fs.Parse(args); err != nil {
		return reindexArgs{}, fmt.Errorf("reindex flags: %w", err)
	}
	if fs.NArg() > 0 {
		return reindexArgs{}, fmt.Errorf("reindex: unexpected argument %q", fs.Arg(0))
	}
	a.Markets = splitList(markets, strings.ToLower)
	if len(a.Markets) == 0 {
		a.Markets = splitList(cfg.Market, strings.ToLower)
	}
	if len(a.Markets) == 0 {
		return reindexArgs{}, errors.New("reindex: no market: pass --market or set MARKET")
	}
	a.Statuses = splitList(statuses, strings.ToUpper)
	if a.Batch < 1 || a.Batch > 200 {
		return reindexArgs{}, fmt.Errorf("reindex: --batch %d out of range 1-200", a.Batch)
	}
	if a.Concurrency < 1 || a.Concurrency > 16 {
		return reindexArgs{}, fmt.Errorf("reindex: --concurrency %d out of range 1-16", a.Concurrency)
	}
	t, err := parseSince(since, now)
	if err != nil {
		return reindexArgs{}, err
	}
	a.Since = t
	return a, nil
}

// splitList splits a comma separated list, trims, normalises and de-duplicates it.
func splitList(csv string, norm func(string) string) []string {
	var out []string
	seen := map[string]bool{}
	for _, part := range strings.Split(csv, ",") {
		v := norm(strings.TrimSpace(part))
		if v != "" && !seen[v] {
			seen[v] = true
			out = append(out, v)
		}
	}
	return out
}

// parseSince reads --since: empty (every product), a duration before now, an RFC 3339 time or a date.
func parseSince(s string, now time.Time) (time.Time, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, nil
	}
	if d, err := time.ParseDuration(s); err == nil {
		if d <= 0 {
			return time.Time{}, fmt.Errorf("reindex: --since %q must be positive", s)
		}
		return now.Add(-d), nil
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("reindex: --since %q is not a duration (36h), an RFC 3339 time or a date (2006-01-02)", s)
}

// runReindex is `indexer reindex`: it builds the same projectors the consumer uses, over the same backend, and feeds
// them the current state of the sources instead of events.
func runReindex(args []string) error {
	cfg, env, err := loadConfig()
	if err != nil {
		return err
	}
	a, err := parseReindexArgs(args, cfg, time.Now(), os.Stderr)
	if errors.Is(err, flag.ErrHelp) {
		return nil
	}
	if err != nil {
		return err
	}
	log := logging.New(serviceName, env)
	slog.SetDefault(log)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	be, err := newBackend(cfg)
	if err != nil {
		return err
	}
	log.Info("reindex", "provider", be.name, "markets", a.Markets, "dry_run", a.DryRun, "batch", a.Batch,
		"concurrency", a.Concurrency, "statuses", a.Statuses, "since", a.Since, "after", a.After)

	httpClient := &http.Client{Timeout: 30 * time.Second}
	directory := newSellerDirectory(cfg, httpClient, log)
	// The same seller names the live indexer holds: warmed from seller-service before any product is projected.
	if !a.DryRun {
		warmDirectory(ctx, directory, cfg.SellerDirectory.WarmTimeout, log)
	}
	categories := catalog.NewClient(cfg.CatalogURL, httpClient)
	handlers := map[string]events.Handler{}
	for _, m := range a.Markets {
		handlers[m] = app.NewProjector(be.index, categories, directory, m, log)
	}
	sd := cfg.SellerDirectory
	if sd.TokenURL != "" && sd.ClientSecret == "" {
		log.Warn("INDEXER_CLIENT_SECRET not set: catalog export calls are unauthenticated")
	}
	reindexer := app.NewReindexer(app.ReindexDeps{
		Products: sources.NewCatalog(sources.CatalogConfig{BaseURL: cfg.CatalogURL, TokenURL: sd.TokenURL, ClientID: sd.ClientID,
			ClientSecret: sd.ClientSecret, Scopes: cfg.Reindex.Scopes}, httpClient),
		BuyBoxes:    sources.NewOffers(cfg.Reindex.OffersURL, httpClient),
		Stock:       sources.NewInventory(cfg.Reindex.InventoryURL, httpClient),
		EnsureIndex: be.ensureIndex,
		Handlers:    handlers,
		Log:         log,
	})

	sum, runErr := reindexer.Run(ctx, app.ReindexOptions{Markets: a.Markets, Batch: a.Batch, Concurrency: a.Concurrency,
		DryRun: a.DryRun, Since: a.Since, After: a.After, Statuses: a.Statuses})
	printReindexSummary(os.Stdout, be.name, sum)
	if runErr != nil {
		return fmt.Errorf("reindex stopped (resume with --after %q): %w", sum.ResumeAfter, runErr)
	}
	if !a.DryRun && a.BackfillEmbeddings {
		if err := backfillEmbeddings(ctx, cfg, be, a.Markets, log); err != nil {
			return err
		}
	}
	if n := sum.Failures(); n > 0 {
		return fmt.Errorf("reindex finished with %d failed products (%d failed batches); the run is idempotent, run it again", n, sum.FailedBatches)
	}
	if !a.DryRun && !a.BackfillEmbeddings {
		log.Info("reindex done; vectors are filled by the indexer's embedding backfill (every EMBEDDINGS_BACKFILL_INTERVAL, or restart the indexer, or re-run with --backfill-embeddings)")
	}
	return nil
}

// backfillEmbeddings runs one pass of the indexer's own embedding backfill for each market.
func backfillEmbeddings(ctx context.Context, cfg config.Config, be backend, markets []string, log *slog.Logger) error {
	embedder := newEmbedder(cfg, log)
	if embedder == nil {
		log.Warn("--backfill-embeddings ignored: embeddings are off (EMBEDDINGS_ENABLED, AI_GATEWAY_URL)")
		return nil
	}
	for _, m := range markets {
		backfill := app.NewEmbeddingBackfill(be.index, embedder, m, cfg.Embeddings.BackfillBatch, cfg.Embeddings.Timeout, log)
		n, err := backfill.RunOnce(ctx)
		log.Info("embedding backfill", "market", m, "stored", n)
		if err != nil {
			return fmt.Errorf("embedding backfill for market %s: %w", m, err)
		}
	}
	return nil
}

// printReindexSummary writes the final per-market table.
func printReindexSummary(w io.Writer, provider string, sum app.ReindexSummary) {
	mode := ""
	if sum.DryRun {
		mode = ", dry run: nothing was written"
	}
	_, _ = fmt.Fprintf(w, "\nReindex summary (%s%s)\n", provider, mode)
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	_, _ = fmt.Fprintln(tw, "MARKET\tREAD\tWRITTEN\tPLANNED\tSKIPPED\tFAILED")
	for _, m := range sum.Markets {
		_, _ = fmt.Fprintf(tw, "%s\t%d\t%d\t%d\t%d\t%d\n", m.Market, m.Read, m.Written, m.Planned, m.Skipped, m.Failed)
	}
	_ = tw.Flush()
	_, _ = fmt.Fprintf(w, "batches: %d, failed batches: %d\n", sum.Batches, sum.FailedBatches)
	if sum.ResumeAfter != "" {
		_, _ = fmt.Fprintf(w, "stopped before the end: resume with --after %s\n", sum.ResumeAfter)
	}
}
