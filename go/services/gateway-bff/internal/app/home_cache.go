package app

import (
	"sync"
	"time"
)

// ttlCache is the gateway's cache for the shared data the dynamic home draws from (settings, registry, signals snapshot,
// candidate pools): fresh for `fresh`, and when a refresh fails the stale value is served for up to `stale` more. The
// clock is passed in, so tests move time without sleeping. One load per key runs at a time.
type ttlCache[T any] struct {
	mu           sync.Mutex
	m            map[string]*ttlSlot[T]
	fresh, stale time.Duration
	max          int
}

type ttlSlot[T any] struct {
	mu  sync.Mutex // held while loading
	val T
	at  time.Time
	ok  bool
}

func newTTLCache[T any](fresh, stale time.Duration) *ttlCache[T] {
	return &ttlCache[T]{m: map[string]*ttlSlot[T]{}, fresh: fresh, stale: stale, max: 256}
}

// get returns the value for key and when it was loaded.
func (c *ttlCache[T]) get(key string, now time.Time, load func() (T, error)) (T, time.Time, error) {
	c.mu.Lock()
	slot := c.m[key]
	if slot == nil {
		if len(c.m) >= c.max {
			for k, s := range c.m {
				if now.Sub(s.at) > c.fresh+c.stale {
					delete(c.m, k)
				}
			}
			if len(c.m) >= c.max {
				c.m = map[string]*ttlSlot[T]{}
			}
		}
		slot = &ttlSlot[T]{}
		c.m[key] = slot
	}
	c.mu.Unlock()

	slot.mu.Lock()
	defer slot.mu.Unlock()
	if slot.ok && now.Sub(slot.at) < c.fresh {
		return slot.val, slot.at, nil
	}
	v, err := load()
	if err != nil {
		if slot.ok && now.Sub(slot.at) < c.fresh+c.stale {
			return slot.val, slot.at, nil
		}
		var zero T
		return zero, time.Time{}, err
	}
	slot.val, slot.at, slot.ok = v, now, true
	return v, now, nil
}
