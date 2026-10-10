// Package routecache is an in-memory, TTL-bound port.RouteCache keyed by rounded origin / destination.
package routecache

import (
	"math"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

const metresPerDegreeLat = 111_320.0

type entry struct {
	route   domain.CachedRoute
	expires time.Time
}

type cacheImpl struct {
	mu      sync.Mutex
	entries map[key]entry
	ttl     time.Duration
	step    float64 // degrees: the rounding grid
	maxSize int
	now     func() time.Time
}

type key struct {
	fromLat, fromLng, toLat, toLng int64
}

var _ port.RouteCache = (*cacheImpl)(nil)

// New returns a cache rounding points to a grid of about precisionMetres (default 50 m), keeping entries for ttl
// (≤ 0 disables caching) and at most maxSize entries (expired ones are dropped first, then the whole map when full).
// now may be nil (time.Now).
func New(ttl time.Duration, precisionMetres float64, maxSize int, now func() time.Time) port.RouteCache {
	if precisionMetres <= 0 {
		precisionMetres = 50
	}
	if maxSize <= 0 {
		maxSize = 50_000
	}
	if now == nil {
		now = time.Now
	}
	return &cacheImpl{entries: map[key]entry{}, ttl: ttl, step: precisionMetres / metresPerDegreeLat,
		maxSize: maxSize, now: now}
}

func (c *cacheImpl) k(from, to domain.Point) key {
	r := func(v float64) int64 { return int64(math.Round(v / c.step)) }
	return key{r(from.Lat), r(from.Lng), r(to.Lat), r(to.Lng)}
}

func (c *cacheImpl) Get(from, to domain.Point) (domain.CachedRoute, bool) {
	if c.ttl <= 0 {
		return domain.CachedRoute{}, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[c.k(from, to)]
	if !ok || !c.now().Before(e.expires) {
		return domain.CachedRoute{}, false
	}
	return e.route, true
}

func (c *cacheImpl) Put(from, to domain.Point, r domain.CachedRoute) {
	if c.ttl <= 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if len(c.entries) >= c.maxSize {
		for k, e := range c.entries {
			if !now.Before(e.expires) {
				delete(c.entries, k)
			}
		}
		if len(c.entries) >= c.maxSize {
			c.entries = map[key]entry{}
		}
	}
	c.entries[c.k(from, to)] = entry{route: r, expires: now.Add(c.ttl)}
}
