package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"golang.org/x/sync/errgroup"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// Reindexer rebuilds the product search index from the sources of truth (catalog, offers, inventory, seller) instead
// of from Kafka history. It reads the current state of every product and replays it, as events, through the same
// Projector the live indexer runs, so the document it writes is the one the event path would have written. Every write
// goes through the index adapter's version guard, so a live indexer running at the same time is never overwritten
// by older data, and a run can be repeated: it re-applies the same state.
type Reindexer interface {
	// Run reindexes the products selected by opts into every market of opts. It returns the summary even when it
	// fails; the error is set when the run could not read the catalog or was interrupted (ResumeAfter in the summary
	// is where to continue), while products that failed individually only count in the summary.
	Run(ctx context.Context, opts ReindexOptions) (ReindexSummary, error)
}

// ReindexOptions selects what a run does.
type ReindexOptions struct {
	// Markets are the markets whose index is written (each must have a handler).
	Markets []string
	// Batch is how many products one catalog page holds (0 = 100).
	Batch int
	// Concurrency is how many products are projected at once (0 = 2).
	Concurrency int
	// DryRun reads the catalog and counts, and writes nothing (not even the index settings).
	DryRun bool
	// Since limits the run to products changed at or after it (zero = every product).
	Since time.Time
	// After resumes after this catalog cursor (the ResumeAfter of an interrupted run).
	After string
	// Statuses are the catalog statuses to index (empty = LIVE).
	Statuses []string
}

// MarketSummary counts what a run did for one market.
type MarketSummary struct {
	Market string
	// Read is the products read from the catalog.
	Read int
	// Written is the products the index accepted. The version guard may still keep a newer live document.
	Written int
	// Planned is the products a dry run would write.
	Planned int
	// Skipped is the exported products that cannot be indexed (no ATIN).
	Skipped int
	// Failed is the products that could not be written, including all of a batch whose stock could not be read.
	Failed int
}

// ReindexSummary is the result of a run.
type ReindexSummary struct {
	DryRun  bool
	Markets []MarketSummary
	// Batches is the catalog pages processed; FailedBatches those whose stock sources failed.
	Batches       int
	FailedBatches int
	// ResumeAfter is the catalog cursor after the last page processed ("" once the end was reached).
	ResumeAfter string
}

// Failures is the number of products that failed in any market.
func (s ReindexSummary) Failures() int {
	n := 0
	for _, m := range s.Markets {
		n += m.Failed
	}
	return n
}

// ReindexDeps are the collaborators of a Reindexer.
type ReindexDeps struct {
	Products port.ProductSource
	BuyBoxes port.BuyBoxSource
	Stock    port.StockSource
	// EnsureIndex creates the market's index and applies its settings when missing.
	EnsureIndex func(ctx context.Context, market string) error
	// Handlers are the projectors, by market.
	Handlers map[string]events.Handler
	Log      *slog.Logger
	// Now is the clock (nil = time.Now).
	Now func() time.Time
	// SnapshotSkew is how far before the read the buy box and stock entries are dated (0 = 30s); see snapshotTime.
	SnapshotSkew time.Duration
	// RetryBackoff is the first wait between attempts to read a source (0 = 250ms, doubled each attempt).
	RetryBackoff time.Duration
}

const (
	defaultBatch       = 100
	defaultConcurrency = 2
	defaultSkew        = 30 * time.Second
	defaultBackoff     = 250 * time.Millisecond
	sourceAttempts     = 3
)

// snapshotType is the event type a reindex gives an exported product. The projector indexes the payload with the
// product's own status for it (it is what catalog publishes when a hidden listing comes back), which is exactly what
// an export is.
const snapshotType = domain.ProductRestored

type reindexerImpl struct {
	d ReindexDeps
}

var _ Reindexer = (*reindexerImpl)(nil)

// NewReindexer returns a Reindexer.
func NewReindexer(d ReindexDeps) Reindexer {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.SnapshotSkew <= 0 {
		d.SnapshotSkew = defaultSkew
	}
	if d.RetryBackoff <= 0 {
		d.RetryBackoff = defaultBackoff
	}
	return &reindexerImpl{d: d}
}

func (r *reindexerImpl) Run(ctx context.Context, opts ReindexOptions) (ReindexSummary, error) {
	if opts.Batch <= 0 {
		opts.Batch = defaultBatch
	}
	if opts.Concurrency <= 0 {
		opts.Concurrency = defaultConcurrency
	}
	sum := ReindexSummary{DryRun: opts.DryRun, ResumeAfter: opts.After}
	if len(opts.Markets) == 0 {
		return sum, errors.New("reindex: no market selected")
	}
	for _, m := range opts.Markets {
		if r.d.Handlers[m] == nil {
			return sum, fmt.Errorf("reindex: market %q is not configured", m)
		}
		sum.Markets = append(sum.Markets, MarketSummary{Market: m})
	}
	if !opts.DryRun {
		for _, m := range opts.Markets {
			if err := r.d.EnsureIndex(ctx, m); err != nil {
				return sum, fmt.Errorf("ensure index for market %s: %w", m, err)
			}
		}
	}

	after := opts.After
	for {
		page, err := r.page(ctx, domain.ProductExportQuery{After: after, Statuses: opts.Statuses, Since: opts.Since,
			Size: opts.Batch})
		if err != nil {
			return sum, fmt.Errorf("read catalog page after %q: %w", after, err)
		}
		if len(page.Items) > 0 {
			r.batch(ctx, opts, page.Items, &sum)
		}
		sum.Batches++
		if page.NextAfter != "" && page.NextAfter == after {
			return sum, fmt.Errorf("catalog export did not advance past cursor %q", after)
		}
		after = page.NextAfter
		sum.ResumeAfter = after
		r.progress(sum)
		if after == "" {
			return sum, nil
		}
		if err := ctx.Err(); err != nil {
			return sum, fmt.Errorf("interrupted, resume with --after %s: %w", after, err)
		}
	}
}

// page reads one catalog page, retrying transient failures.
func (r *reindexerImpl) page(ctx context.Context, q domain.ProductExportQuery) (domain.ProductExportPage, error) {
	var page domain.ProductExportPage
	err := r.retry(ctx, "catalog page", func() error {
		p, err := r.d.Products.Products(ctx, q)
		if err != nil {
			return fmt.Errorf("export page: %w", err)
		}
		page = p
		return nil
	})
	return page, err
}

// retry runs fn up to sourceAttempts times with doubling waits, stopping early when ctx ends.
func (r *reindexerImpl) retry(ctx context.Context, what string, fn func() error) error {
	wait := r.d.RetryBackoff
	var err error
	for attempt := 1; attempt <= sourceAttempts; attempt++ {
		if err = fn(); err == nil {
			return nil
		}
		if attempt == sourceAttempts || ctx.Err() != nil {
			break
		}
		r.d.Log.WarnContext(ctx, "reindex source read failed; retrying", "source", what, "attempt", attempt, "error", err)
		select {
		case <-ctx.Done():
			return fmt.Errorf("%s: %w", what, ctx.Err())
		case <-time.After(wait):
		}
		wait *= 2
	}
	return err
}

// stockSnapshot is the buy boxes and node quantities of one page, by variant ATIN.
type stockSnapshot struct {
	buyBoxes map[string]domain.VariantBuyBox
	nodes    map[string][]domain.NodeStock
}

func (r *reindexerImpl) batch(ctx context.Context, opts ReindexOptions, items []domain.ProductExportItem, sum *ReindexSummary) {
	valid := make([]domain.ProductExportItem, 0, len(items))
	for _, it := range items {
		if it.Product.ATIN != "" {
			valid = append(valid, it)
		}
	}
	for i := range sum.Markets {
		sum.Markets[i].Read += len(items)
		sum.Markets[i].Skipped += len(items) - len(valid)
	}
	if opts.DryRun {
		for i := range sum.Markets {
			sum.Markets[i].Planned += len(valid)
		}
		return
	}

	// Stock is dated before the read: see snapshotTime.
	at := r.snapshotTime()
	stock, err := r.loadStock(ctx, valid)
	if err != nil {
		r.d.Log.ErrorContext(ctx, "reindex batch skipped: stock could not be read; re-run to cover it", "products", len(valid),
			"error", err)
		sum.FailedBatches++
		for i := range sum.Markets {
			sum.Markets[i].Failed += len(valid)
		}
		return
	}

	var mu sync.Mutex
	var g errgroup.Group
	g.SetLimit(opts.Concurrency)
	for _, it := range valid {
		g.Go(func() error {
			for i := range sum.Markets {
				err := r.apply(ctx, sum.Markets[i].Market, it, stock, at)
				mu.Lock()
				if err != nil {
					sum.Markets[i].Failed++
					r.d.Log.ErrorContext(ctx, "reindex product failed", "market", sum.Markets[i].Market, "atin", it.Product.ATIN,
						"error", err)
				} else {
					sum.Markets[i].Written++
				}
				mu.Unlock()
			}
			return nil
		})
	}
	_ = g.Wait() // workers never return an error: failures are counted
}

// snapshotTime dates the buy box and stock entries of a page. They are read from services that give no change time,
// so the entry is dated slightly before the read: an event about a change that happened after the read carries a
// later time and wins, even when the clocks of the services differ by less than the skew or the change committed a
// moment after the read.
func (r *reindexerImpl) snapshotTime() time.Time { return r.d.Now().Add(-r.d.SnapshotSkew) }

func (r *reindexerImpl) loadStock(ctx context.Context, items []domain.ProductExportItem) (stockSnapshot, error) {
	seen := map[string]bool{}
	var atins []string
	for _, it := range items {
		for _, v := range it.Product.Variants {
			if v.ATIN != "" && !seen[v.ATIN] {
				seen[v.ATIN] = true
				atins = append(atins, v.ATIN)
			}
		}
	}
	snap := stockSnapshot{buyBoxes: map[string]domain.VariantBuyBox{}, nodes: map[string][]domain.NodeStock{}}
	for _, chunk := range chunks(atins, 50) {
		err := r.retry(ctx, "buy boxes", func() error {
			got, err := r.d.BuyBoxes.BuyBoxes(ctx, chunk)
			if err != nil {
				return fmt.Errorf("buy boxes: %w", err)
			}
			for _, bb := range got {
				snap.buyBoxes[bb.ATIN] = bb
			}
			return nil
		})
		if err != nil {
			return stockSnapshot{}, err
		}
	}
	// Inventory events name the SKU they count; the projector matches it against variant ATINs.
	for _, chunk := range chunks(atins, 100) {
		err := r.retry(ctx, "stock", func() error {
			got, err := r.d.Stock.Stock(ctx, chunk)
			if err != nil {
				return fmt.Errorf("stock: %w", err)
			}
			for _, n := range got {
				snap.nodes[n.SKU] = append(snap.nodes[n.SKU], n)
			}
			return nil
		})
		if err != nil {
			return stockSnapshot{}, err
		}
	}
	return snap, nil
}

// apply projects one exported product into one market: the product, its rating, then the buy box and stock of its
// variants (the quantities are applied to the document the product event just wrote).
func (r *reindexerImpl) apply(ctx context.Context, market string, it domain.ProductExportItem, stock stockSnapshot, at time.Time) error {
	h := r.d.Handlers[market]
	atin := it.Product.ATIN
	version := it.Version
	if version.IsZero() {
		version = at
	}
	if err := r.handle(ctx, h, snapshotType, atin, version, it.Product); err != nil {
		return fmt.Errorf("product %s: %w", atin, err)
	}
	if rt := it.Rating; rt != nil {
		when := rt.UpdatedAt
		if when.IsZero() {
			when = version
		}
		re := domain.RatingEvent{ProductID: it.Product.ProductID, ATIN: atin, OwnerOrgID: it.Product.OwnerOrgID,
			Average: rt.Average, Count: rt.Count}
		if err := r.handle(ctx, h, domain.RatingChanged, atin, when, re); err != nil {
			return fmt.Errorf("rating %s: %w", atin, err)
		}
	}
	for _, v := range it.Product.Variants {
		if bb, ok := stock.buyBoxes[v.ATIN]; ok {
			be := domain.BuyBoxEvent{ATIN: v.ATIN, DecidedAt: at}
			if bb.WinnerOfferID != "" {
				winner, qty := bb.WinnerOfferID, bb.AvailableQty
				be.WinnerOfferID, be.AvailableQty = &winner, &qty
			}
			if err := r.handle(ctx, h, domain.BuyBoxChanged, v.ATIN, at, be); err != nil {
				return fmt.Errorf("buy box %s: %w", v.ATIN, err)
			}
		}
		for _, n := range stock.nodes[v.ATIN] {
			ae := domain.AvailabilityEvent{SKU: n.SKU, Node: n.Node, Available: n.Available, Version: n.Version}
			if err := r.handle(ctx, h, domain.AvailabilityChanged, n.SKU, at, ae); err != nil {
				return fmt.Errorf("stock %s@%s: %w", n.SKU, n.Node, err)
			}
		}
	}
	return nil
}

// handle wraps a payload in an event envelope and hands it to the market's projector.
func (r *reindexerImpl) handle(ctx context.Context, h events.Handler, typ, aggregateID string, at time.Time, payload any) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("encode %s: %w", typ, err)
	}
	e := events.Envelope{ID: "reindex-" + typ + "-" + aggregateID, Type: typ, AggregateID: aggregateID, OccurredAt: at,
		Payload: raw}
	if err := h.Handle(ctx, e); err != nil {
		return fmt.Errorf("project %s: %w", typ, err)
	}
	return nil
}

func (r *reindexerImpl) progress(sum ReindexSummary) {
	for _, m := range sum.Markets {
		r.d.Log.Info("reindex progress", "market", m.Market, "read", m.Read, "written", m.Written, "planned", m.Planned,
			"skipped", m.Skipped, "failed", m.Failed, "batches", sum.Batches, "resume_after", sum.ResumeAfter)
	}
}

func chunks(in []string, size int) [][]string {
	var out [][]string
	for len(in) > size {
		out = append(out, in[:size])
		in = in[size:]
	}
	if len(in) > 0 {
		out = append(out, in)
	}
	return out
}
