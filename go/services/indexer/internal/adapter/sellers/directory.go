// Package sellers implements port.SellerDirectory: seller display names warmed from seller-service's organisation
// directory on start, refreshed periodically, learned from seller.org.* events and looked up on a miss.
package sellers

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// Directory is the seller directory with its warm-up from seller-service.
type Directory interface {
	port.SellerDirectory
	// Warm reads every page of seller-service's organisation directory and returns how many names it knows after.
	Warm(ctx context.Context) (int, error)
	// RunRefresh re-warms every interval until ctx is done (catches renames whose events were missed).
	RunRefresh(ctx context.Context, every time.Duration) error
}

// Options tune the directory.
type Options struct {
	// PageSize of directory reads (seller caps it at 500).
	PageSize int
	// MissTTL is how long an organisation unknown to seller-service is not looked up again.
	MissTTL time.Duration
	Log     *slog.Logger
}

type name struct {
	value string
	// learned is when the name was learned; a directory page fetched before it never overwrites it, so an event
	// processed while a refresh is in flight wins over the older snapshot.
	learned time.Time
}

type directoryImpl struct {
	source   port.SellerSource
	fallback string
	opts     Options
	now      func() time.Time

	mu     sync.RWMutex
	names  map[string]name
	misses map[string]time.Time
}

var (
	_ port.SellerDirectory = (*directoryImpl)(nil)
	_ Directory            = (*directoryImpl)(nil)
)

// NewMemoryDirectory resolves names from configuration plus names learned from seller.org.approved /
// seller.org.updated, without seller-service (tests, tools).
func NewMemoryDirectory(names map[string]string, fallback string) port.SellerDirectory {
	return newDirectory(names, fallback, nil, Options{})
}

// NewDirectory resolves names from seller-service: Warm loads the whole directory before events are consumed, so a
// restart never changes indexed seller names; names from seller.org.* events update it; an organisation still
// unknown is looked up once (GET /v1/orgs/{id}/public) before falling back. seed holds static names (AfroTransact
// Retail) that the directory may override.
func NewDirectory(seed map[string]string, fallback string, source port.SellerSource, opts Options) Directory {
	return newDirectory(seed, fallback, source, opts)
}

func newDirectory(seed map[string]string, fallback string, source port.SellerSource, opts Options) *directoryImpl {
	if opts.PageSize <= 0 {
		opts.PageSize = 500
	}
	if opts.MissTTL <= 0 {
		opts.MissTTL = 5 * time.Minute
	}
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	m := make(map[string]name, len(seed))
	for k, v := range seed {
		if v != "" {
			m[k] = name{value: v}
		}
	}
	return &directoryImpl{source: source, fallback: fallback, opts: opts, now: time.Now, names: m,
		misses: map[string]time.Time{}}
}

func (d *directoryImpl) DisplayName(ctx context.Context, orgID string) (string, bool, error) {
	d.mu.RLock()
	n, ok := d.names[orgID]
	missed, wasMissed := d.misses[orgID]
	d.mu.RUnlock()
	if ok {
		return n.value, true, nil
	}
	if d.source == nil || orgID == "" || (wasMissed && d.now().Sub(missed) < d.opts.MissTTL) {
		return d.fallback, false, nil
	}
	org, found, err := d.source.Org(ctx, orgID)
	if err != nil {
		// Seller-service is down: the projector keeps the already indexed name (PreserveSellerName) instead of
		// failing the event into the dead-letter topic.
		d.opts.Log.WarnContext(ctx, "seller lookup failed; keeping the indexed name", "org_id", orgID, "error", err)
		return d.fallback, false, nil
	}
	if !found || org.DisplayName == "" {
		d.mu.Lock()
		d.misses[orgID] = d.now()
		d.mu.Unlock()
		return d.fallback, false, nil
	}
	d.learn(orgID, org.DisplayName, d.now())
	return org.DisplayName, true, nil
}

func (d *directoryImpl) Remember(_ context.Context, orgID, displayName string) {
	if orgID == "" || displayName == "" {
		return
	}
	d.learn(orgID, displayName, d.now())
}

func (d *directoryImpl) learn(orgID, displayName string, at time.Time) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.names[orgID] = name{value: displayName, learned: at}
	delete(d.misses, orgID)
}

func (d *directoryImpl) Warm(ctx context.Context) (int, error) {
	if d.source == nil {
		return d.size(), nil
	}
	for page := 0; ; page++ {
		fetched := d.now()
		p, err := d.source.Directory(ctx, page, d.opts.PageSize)
		if err != nil {
			return d.size(), fmt.Errorf("seller directory page %d: %w", page, err)
		}
		d.mu.Lock()
		for _, org := range p.Items {
			if org.ID == "" || org.DisplayName == "" {
				continue
			}
			if cur, ok := d.names[org.ID]; ok && cur.learned.After(fetched) {
				continue
			}
			d.names[org.ID] = name{value: org.DisplayName, learned: fetched}
			delete(d.misses, org.ID)
		}
		d.mu.Unlock()
		if len(p.Items) < d.opts.PageSize || int64((page+1)*d.opts.PageSize) >= p.Total {
			return d.size(), nil
		}
	}
}

func (d *directoryImpl) RunRefresh(ctx context.Context, every time.Duration) error {
	if d.source == nil || every <= 0 {
		<-ctx.Done()
		return nil
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
			if n, err := d.Warm(ctx); err != nil {
				d.opts.Log.WarnContext(ctx, "seller directory refresh failed", "error", err)
			} else {
				d.opts.Log.InfoContext(ctx, "seller directory refreshed", "names", n)
			}
		}
	}
}

func (d *directoryImpl) size() int {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return len(d.names)
}
