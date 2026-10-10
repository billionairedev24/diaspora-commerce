// Package memcache implements port.Cache in process memory: short TTLs for public, non-personal upstream reads
// (site config, placements per ZIP, catalog products, category tree, ZIP lookups). Never used for per-user data.
package memcache

import (
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type entry struct {
	value   []byte
	expires time.Time
}

type cacheImpl struct {
	max int
	now func() time.Time

	mu      sync.Mutex
	entries map[string]entry
}

var _ port.Cache = (*cacheImpl)(nil)

// New returns a cache holding at most maxEntries values (expired and then arbitrary entries are evicted first).
func New(maxEntries int, now func() time.Time) port.Cache {
	if now == nil {
		now = time.Now
	}
	return &cacheImpl{max: max(16, maxEntries), now: now, entries: map[string]entry{}}
}

func (c *cacheImpl) Get(key string) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[key]
	if !ok {
		return nil, false
	}
	if c.now().After(e.expires) {
		delete(c.entries, key)
		return nil, false
	}
	return e.value, true
}

func (c *cacheImpl) Set(key string, value []byte, ttl time.Duration) {
	if ttl <= 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= c.max {
		c.evict()
	}
	c.entries[key] = entry{value: value, expires: c.now().Add(ttl)}
}

func (c *cacheImpl) evict() {
	now := c.now()
	for k, e := range c.entries {
		if now.After(e.expires) {
			delete(c.entries, k)
		}
	}
	for k := range c.entries {
		if len(c.entries) < c.max {
			return
		}
		delete(c.entries, k)
	}
}
