package app

import (
	"sync"
	"time"
)

// feedCacheTTL is how long a dashboard feed is served without recomputing (S195).
const feedCacheTTL = 60 * time.Second

// feedCacheMax bounds the cached feeds; expired ones go first.
const feedCacheMax = 512

// feedCache keeps dashboard feeds for feedCacheTTL, one per key. Concurrent requests for the same key share one
// computation. Keys carry the caller (never only the organisation) because upstream answers differ by permission.
type feedCache[T any] struct {
	mu      sync.Mutex
	entries map[string]*feedEntry[T]
}

type feedEntry[T any] struct {
	done chan struct{}
	val  T
	at   time.Time
}

func newFeedCache[T any]() *feedCache[T] { return &feedCache[T]{entries: map[string]*feedEntry[T]{}} }

// do returns the cached feed for key, or computes it (once, however many callers wait).
func (c *feedCache[T]) do(key string, now time.Time, compute func() T) T {
	c.mu.Lock()
	if e, ok := c.entries[key]; ok {
		select {
		case <-e.done:
			if now.Sub(e.at) < feedCacheTTL {
				c.mu.Unlock()
				return e.val
			}
		default: // another request is computing it: wait for that one
			c.mu.Unlock()
			<-e.done
			return e.val
		}
	}
	if len(c.entries) >= feedCacheMax {
		c.evict(now)
	}
	e := &feedEntry[T]{done: make(chan struct{}), at: now}
	c.entries[key] = e
	c.mu.Unlock()
	defer close(e.done)
	e.val = compute()
	return e.val
}

func (c *feedCache[T]) evict(now time.Time) {
	for k, e := range c.entries {
		select {
		case <-e.done:
			if now.Sub(e.at) >= feedCacheTTL {
				delete(c.entries, k)
			}
		default:
		}
	}
	if len(c.entries) >= feedCacheMax { // everything is fresh: drop the oldest half
		n := 0
		for k := range c.entries {
			delete(c.entries, k)
			if n++; n >= feedCacheMax/2 {
				break
			}
		}
	}
}
