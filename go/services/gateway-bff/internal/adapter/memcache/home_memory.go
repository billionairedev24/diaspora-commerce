package memcache

import (
	"context"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type homeMemoryImpl struct {
	mu sync.Mutex
	m  map[string]homeMemEntry
	// now is replaceable in tests.
	now func() time.Time
}

type homeMemEntry struct {
	st      port.HomeVisitState
	expires time.Time
}

var _ port.HomeMemory = (*homeMemoryImpl)(nil)

const homeMemoryMax = 20000

// NewHomeMemory returns an in-process home memory (tests and single-replica local runs; production uses Valkey so every
// replica sees the same history).
func NewHomeMemory() port.HomeMemory {
	return &homeMemoryImpl{m: map[string]homeMemEntry{}, now: time.Now}
}

func (h *homeMemoryImpl) Load(_ context.Context, key string) (port.HomeVisitState, bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	e, ok := h.m[key]
	if !ok || !h.now().Before(e.expires) {
		return port.HomeVisitState{}, false, nil
	}
	return e.st, true, nil
}

func (h *homeMemoryImpl) Save(_ context.Context, key string, st port.HomeVisitState, ttl time.Duration) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.m) >= homeMemoryMax {
		for k, e := range h.m {
			if !h.now().Before(e.expires) {
				delete(h.m, k)
			}
		}
		if len(h.m) >= homeMemoryMax { // all live: drop arbitrary entries, a forgotten visit only repeats an item
			n := 0
			for k := range h.m {
				delete(h.m, k)
				if n++; n >= homeMemoryMax/2 {
					break
				}
			}
		}
	}
	h.m[key] = homeMemEntry{st: st, expires: h.now().Add(ttl)}
	return nil
}
