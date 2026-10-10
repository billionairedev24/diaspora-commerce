// Package contactcache caches RecipientResolver lookups in memory with a TTL, so a burst of events for the same
// customer or organisation costs one identity call.
package contactcache

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Options bound the cache. NotFoundTTL caches unknown users/orgs briefly (identity may just be catching up).
type Options struct {
	TTL         time.Duration
	NotFoundTTL time.Duration
	MaxEntries  int
}

type entry[T any] struct {
	value    T
	notFound bool
	expires  time.Time
}

type cacheImpl struct {
	inner    port.RecipientResolver
	opts     Options
	now      func() time.Time
	mu       sync.Mutex
	contacts map[string]entry[port.Contact]
	owners   map[string]entry[[]string]
}

var (
	_ port.RecipientResolver  = (*cacheImpl)(nil)
	_ port.ContactInvalidator = (*cacheImpl)(nil)
)

// Cache is a RecipientResolver whose contact entries can be dropped when identity reports a change.
type Cache interface {
	port.RecipientResolver
	port.ContactInvalidator
}

// New wraps inner with a TTL cache. Transient errors are never cached.
func New(inner port.RecipientResolver, opts Options) Cache {
	return NewWithClock(inner, opts, time.Now)
}

// NewWithClock is New with an injectable clock (tests).
func NewWithClock(inner port.RecipientResolver, opts Options, now func() time.Time) Cache {
	if opts.TTL <= 0 {
		opts.TTL = 5 * time.Minute
	}
	if opts.NotFoundTTL <= 0 {
		opts.NotFoundTTL = 30 * time.Second
	}
	if opts.MaxEntries <= 0 {
		opts.MaxEntries = 10_000
	}
	return &cacheImpl{inner: inner, opts: opts, now: now, contacts: map[string]entry[port.Contact]{},
		owners: map[string]entry[[]string]{}}
}

func (c *cacheImpl) Contact(ctx context.Context, userID string) (port.Contact, error) {
	return cached(c, c.contacts, userID, func() (port.Contact, error) { return c.inner.Contact(ctx, userID) })
}

// Forget drops userID's contact entry (identity.user.phone-verified, identity.notification-preferences.updated).
func (c *cacheImpl) Forget(userID string) {
	c.mu.Lock()
	delete(c.contacts, userID)
	c.mu.Unlock()
}

func (c *cacheImpl) OrgOwners(ctx context.Context, orgID string) ([]string, error) {
	return cached(c, c.owners, orgID, func() ([]string, error) { return c.inner.OrgOwners(ctx, orgID) })
}

func cached[T any](c *cacheImpl, m map[string]entry[T], key string, load func() (T, error)) (T, error) {
	now := c.now()
	c.mu.Lock()
	e, ok := m[key]
	c.mu.Unlock()
	if ok && now.Before(e.expires) {
		if e.notFound {
			var zero T
			return zero, domain.ErrRecipientNotFound
		}
		return e.value, nil
	}
	v, err := load()
	notFound := errors.Is(err, domain.ErrRecipientNotFound)
	if err != nil && !notFound {
		return v, err //nolint:wrapcheck // the inner resolver's error, unchanged
	}
	ttl := c.opts.TTL
	if notFound {
		ttl = c.opts.NotFoundTTL
	}
	c.mu.Lock()
	if len(m) >= c.opts.MaxEntries {
		evict(m, now, c.opts.MaxEntries)
	}
	m[key] = entry[T]{value: v, notFound: notFound, expires: now.Add(ttl)}
	c.mu.Unlock()
	return v, err //nolint:wrapcheck // domain.ErrRecipientNotFound or nil
}

// evict drops expired entries, then (if still full) half of the rest.
func evict[T any](m map[string]entry[T], now time.Time, maxEntries int) {
	for k, e := range m {
		if !now.Before(e.expires) {
			delete(m, k)
		}
	}
	if len(m) < maxEntries {
		return
	}
	limit := len(m) / 2
	for k := range m {
		if len(m) <= limit {
			return
		}
		delete(m, k)
	}
}
