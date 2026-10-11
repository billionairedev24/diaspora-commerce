package app

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Upstreams page their lists but search only a few fields (or none) and sort by a fixed order. To search every column
// a table shows and sort by any of them, the BFF reads the table (up to a cap) with the caller's token, builds the rows
// exactly as the table renders them, and filters, sorts and pages them itself. A short-lived per-caller cache keeps
// typing in the search box and flipping sort from re-reading the upstream on every keystroke; any write through the
// BFF drops the table's cached rows.

// walkResource calls visit for every row of every upstream page, from first, until max rows.
func walkResource(ctx context.Context, sc staffCtx, r *staffResource, q staffQuery, page staffList, limit int, visit func(o jobj) error) error {
	seen := 0
	for {
		for _, o := range page.items {
			if err := visit(o); err != nil {
				return err
			}
			seen++
			if seen >= limit {
				return nil
			}
		}
		switch {
		case page.next != nil:
			q.Cursor = *page.next
		case q.Cursor == "" && page.total != nil && int64((q.Page+1)*q.Size) < *page.total && len(page.items) > 0:
			q.Page++
		default:
			return nil
		}
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("table read cancelled: %w", err)
		}
		var err error
		if page, err = r.list(ctx, sc, q); err != nil {
			return err
		}
	}
}

// scanResource reads up to limit rows of a resource (unfiltered by search) and reports whether more rows exist.
func scanResource(ctx context.Context, sc staffCtx, r *staffResource, status string, limit int) ([]domain.Row, bool, error) {
	q := staffQuery{Status: status, Size: exportPageSize}
	first, err := r.list(ctx, sc, q)
	if err != nil {
		return nil, false, err
	}
	rows := []domain.Row{}
	if err := walkResource(ctx, sc, r, q, first, limit+1, func(o jobj) error {
		rows = append(rows, rowOf(r, o))
		return nil
	}); err != nil {
		return nil, false, err
	}
	if len(rows) > limit {
		return rows[:limit], true, nil
	}
	return rows, false, nil
}

// scanCacheTTL is how long a read table serves searches and sorts.
const scanCacheTTL = 20 * time.Second

// scanCacheMax bounds the cached tables.
const scanCacheMax = 64

type scanEntry[T any] struct {
	rows    []T
	partial bool
	at      time.Time
}

// scanCache keeps recently read tables per caller and table.
type scanCache[T any] struct {
	mu      sync.Mutex
	entries map[string]scanEntry[T]
	clock   Clock
}

func newScanCache[T any](clock Clock) *scanCache[T] {
	return &scanCache[T]{entries: map[string]scanEntry[T]{}, clock: clock}
}

func scanKey(owner, table, variant string) string { return owner + "\x00" + table + "\x00" + variant }

// load returns a copy of the cached rows or reads them with read.
func (c *scanCache[T]) load(owner, table, variant string, read func() ([]T, bool, error)) ([]T, bool, error) {
	key := scanKey(owner, table, variant)
	now := c.clock()
	c.mu.Lock()
	if e, ok := c.entries[key]; ok && now.Sub(e.at) < scanCacheTTL {
		c.mu.Unlock()
		return slices.Clone(e.rows), e.partial, nil
	}
	c.mu.Unlock()
	rows, partial, err := read()
	if err != nil {
		return nil, false, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for k, e := range c.entries {
		if now.Sub(e.at) >= scanCacheTTL || len(c.entries) >= scanCacheMax {
			delete(c.entries, k)
		}
	}
	c.entries[key] = scanEntry[T]{rows: rows, partial: partial, at: now}
	return slices.Clone(rows), partial, nil
}

// drop forgets every caller's rows of a table (after a write).
func (c *scanCache[T]) drop(table string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for k := range c.entries {
		if parts := strings.SplitN(k, "\x00", 3); len(parts) == 3 && parts[1] == table {
			delete(c.entries, k)
		}
	}
}
