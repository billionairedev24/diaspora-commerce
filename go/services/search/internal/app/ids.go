package app

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"sync"
	"time"
)

// newID returns a random UUID (v4) for search ids.
func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return format(b)
}

// stableID derives a deterministic UUID (version 8, name-based SHA-256) from parts, so the same impression or click
// always gets the same event-id and consumers' inboxes drop redeliveries and replays.
func stableID(parts ...string) string {
	sum := sha256.Sum256([]byte(strings.Join(parts, "\x1f")))
	var b [16]byte
	copy(b[:], sum[:16])
	b[6] = (b[6] & 0x0f) | 0x80
	b[8] = (b[8] & 0x3f) | 0x80
	return format(b)
}

func format(b [16]byte) string {
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

// digest is a short stable hash used as a dedupe key.
func digest(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:12])
}

// dedupeSet remembers keys for at least ttl (two rotating generations; bounded by maxEntries per generation).
type dedupeSet struct {
	mu         sync.Mutex
	ttl        time.Duration
	maxEntries int
	current    map[string]struct{}
	previous   map[string]struct{}
	rotated    time.Time
}

func newDedupeSet(ttl time.Duration, maxEntries int) *dedupeSet {
	return &dedupeSet{ttl: ttl, maxEntries: maxEntries, current: map[string]struct{}{}, previous: map[string]struct{}{}}
}

// first records key and reports whether it was not seen within the window.
func (d *dedupeSet) first(key string, now time.Time) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.rotated.IsZero() {
		d.rotated = now
	}
	if now.Sub(d.rotated) >= d.ttl || len(d.current) >= d.maxEntries {
		d.previous, d.current, d.rotated = d.current, make(map[string]struct{}, len(d.current)), now
	}
	if _, ok := d.current[key]; ok {
		return false
	}
	if _, ok := d.previous[key]; ok {
		return false
	}
	d.current[key] = struct{}{}
	return true
}
