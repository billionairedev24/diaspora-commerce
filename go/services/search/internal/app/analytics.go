package app

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// AnalyticsRecorder aggregates searches and clicks in memory per (market, day, query) and flushes the increments to
// the analytics store, so the request path never waits on a write.
type AnalyticsRecorder interface {
	// Searched counts a tracked search and whether it returned nothing.
	Searched(market, query string, zeroResults bool, at time.Time)
	// Clicked counts a result click for the query.
	Clicked(market, query string, at time.Time)
	// Flush writes pending increments.
	Flush(ctx context.Context) error
	// Run flushes every interval and purges aggregates older than the retention daily, until ctx is done (then flushes
	// once more).
	Run(ctx context.Context, every time.Duration, retention time.Duration, markets []string) error
}

// AnalyticsReporter serves the staff console's search analytics.
type AnalyticsReporter interface {
	TopQueries(ctx context.Context, r domain.AnalyticsRange) (domain.QueryReport, error)
	ZeroResultQueries(ctx context.Context, r domain.AnalyticsRange) (domain.QueryReport, error)
	ClickThrough(ctx context.Context, r domain.AnalyticsRange) (domain.CTRReport, error)
}

type analyticsKey struct{ market, day, query string }

type analyticsRecorderImpl struct {
	store port.AnalyticsStore
	log   *slog.Logger
	now   func() time.Time

	mu      sync.Mutex
	pending map[analyticsKey]*domain.AnalyticsDelta
}

type analyticsReporterImpl struct {
	store port.AnalyticsStore
	now   func() time.Time
}

var (
	_ AnalyticsRecorder = (*analyticsRecorderImpl)(nil)
	_ AnalyticsReporter = (*analyticsReporterImpl)(nil)
)

// maxPendingKeys bounds memory between flushes; beyond it new queries are dropped (and logged) until the next flush.
const maxPendingKeys = 50_000

// NewAnalyticsRecorder returns the buffering recorder.
func NewAnalyticsRecorder(store port.AnalyticsStore, log *slog.Logger) AnalyticsRecorder {
	return &analyticsRecorderImpl{store: store, log: log, now: time.Now, pending: map[analyticsKey]*domain.AnalyticsDelta{}}
}

// NewAnalyticsReporter returns the reporting use case.
func NewAnalyticsReporter(store port.AnalyticsStore) AnalyticsReporter {
	return &analyticsReporterImpl{store: store, now: time.Now}
}

func (a *analyticsRecorderImpl) Searched(market, query string, zeroResults bool, at time.Time) {
	a.add(market, query, at, func(d *domain.AnalyticsDelta) {
		d.Searches++
		if zeroResults {
			d.ZeroResults++
		}
	})
}

func (a *analyticsRecorderImpl) Clicked(market, query string, at time.Time) {
	a.add(market, query, at, func(d *domain.AnalyticsDelta) { d.Clicks++ })
}

func (a *analyticsRecorderImpl) add(market, query string, at time.Time, apply func(*domain.AnalyticsDelta)) {
	q := domain.AnalyticsKey(query)
	if q == "" {
		return
	}
	k := analyticsKey{market: market, day: domain.Day(at), query: q}
	a.mu.Lock()
	defer a.mu.Unlock()
	d, ok := a.pending[k]
	if !ok {
		if len(a.pending) >= maxPendingKeys {
			a.log.Warn("analytics buffer full; dropping increment", "query", q)
			return
		}
		d = &domain.AnalyticsDelta{Market: k.market, Day: k.day, Query: k.query}
		a.pending[k] = d
	}
	apply(d)
}

func (a *analyticsRecorderImpl) Flush(ctx context.Context) error {
	a.mu.Lock()
	batch := make([]domain.AnalyticsDelta, 0, len(a.pending))
	for _, d := range a.pending {
		batch = append(batch, *d)
	}
	a.pending = map[analyticsKey]*domain.AnalyticsDelta{}
	a.mu.Unlock()
	if len(batch) == 0 {
		return nil
	}
	if err := a.store.Add(ctx, batch); err != nil {
		a.requeue(batch)
		return fmt.Errorf("flush %d analytics increments: %w", len(batch), err)
	}
	return nil
}

// requeue puts a failed batch back so the next flush retries it.
func (a *analyticsRecorderImpl) requeue(batch []domain.AnalyticsDelta) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, d := range batch {
		k := analyticsKey{market: d.Market, day: d.Day, query: d.Query}
		cur, ok := a.pending[k]
		if !ok {
			if len(a.pending) >= maxPendingKeys {
				continue
			}
			c := d
			a.pending[k] = &c
			continue
		}
		cur.Searches += d.Searches
		cur.ZeroResults += d.ZeroResults
		cur.Clicks += d.Clicks
	}
}

func (a *analyticsRecorderImpl) Run(ctx context.Context, every, retention time.Duration, markets []string) error {
	tick := time.NewTicker(every)
	defer tick.Stop()
	purge := time.NewTicker(24 * time.Hour)
	defer purge.Stop()
	a.purge(ctx, retention, markets)
	for {
		select {
		case <-ctx.Done():
			fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
			defer cancel()
			if err := a.Flush(fctx); err != nil {
				a.log.Warn("final analytics flush failed", "error", err)
			}
			return nil
		case <-tick.C:
			if err := a.Flush(ctx); err != nil {
				a.log.Warn("analytics flush failed; will retry", "error", err)
			}
		case <-purge.C:
			a.purge(ctx, retention, markets)
		}
	}
}

func (a *analyticsRecorderImpl) purge(ctx context.Context, retention time.Duration, markets []string) {
	if retention <= 0 {
		return
	}
	before := a.now().UTC().Add(-retention)
	for _, m := range markets {
		if err := a.store.Purge(ctx, m, before); err != nil {
			a.log.Warn("analytics purge failed", "market", m, "error", err)
		}
	}
}

func (r *analyticsReporterImpl) TopQueries(ctx context.Context, rng domain.AnalyticsRange) (domain.QueryReport, error) {
	rng, err := rng.Normalize(r.now())
	if err != nil {
		return domain.QueryReport{}, fmt.Errorf("range: %w", err)
	}
	items, err := r.store.TopQueries(ctx, rng)
	if err != nil {
		return domain.QueryReport{}, fmt.Errorf("top queries: %w", err)
	}
	return report(rng, items), nil
}

func (r *analyticsReporterImpl) ZeroResultQueries(ctx context.Context, rng domain.AnalyticsRange) (domain.QueryReport, error) {
	rng, err := rng.Normalize(r.now())
	if err != nil {
		return domain.QueryReport{}, fmt.Errorf("range: %w", err)
	}
	items, err := r.store.ZeroResultQueries(ctx, rng)
	if err != nil {
		return domain.QueryReport{}, fmt.Errorf("zero-result queries: %w", err)
	}
	return report(rng, items), nil
}

func (r *analyticsReporterImpl) ClickThrough(ctx context.Context, rng domain.AnalyticsRange) (domain.CTRReport, error) {
	rng, err := rng.Normalize(r.now())
	if err != nil {
		return domain.CTRReport{}, fmt.Errorf("range: %w", err)
	}
	days, err := r.store.DailyCTR(ctx, rng)
	if err != nil {
		return domain.CTRReport{}, fmt.Errorf("ctr: %w", err)
	}
	out := domain.CTRReport{Market: rng.Market, From: domain.Day(rng.From), To: domain.Day(rng.To), Days: days}
	for i := range days {
		d := &out.Days[i]
		d.CTR, d.ZeroResultRate = domain.Ratio(d.Clicks, d.Searches), domain.Ratio(d.ZeroResults, d.Searches)
		out.Total.Searches += d.Searches
		out.Total.Clicks += d.Clicks
		out.Total.ZeroResults += d.ZeroResults
	}
	out.Total.Day = "total"
	out.Total.CTR = domain.Ratio(out.Total.Clicks, out.Total.Searches)
	out.Total.ZeroResultRate = domain.Ratio(out.Total.ZeroResults, out.Total.Searches)
	return out, nil
}

func report(rng domain.AnalyticsRange, items []domain.QueryStat) domain.QueryReport {
	for i := range items {
		items[i].CTR = domain.Ratio(items[i].Clicks, items[i].Searches)
	}
	return domain.QueryReport{Market: rng.Market, From: domain.Day(rng.From), To: domain.Day(rng.To), Items: items}
}

// envelope builds a platform event.
func envelope(id, typ, aggregate, aggregateID string, at time.Time, payload any) (events.Envelope, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return events.Envelope{}, fmt.Errorf("encode %s: %w", typ, err)
	}
	return events.Envelope{ID: id, Type: typ, Aggregate: aggregate, AggregateID: aggregateID, OccurredAt: at,
		Payload: body}, nil
}
