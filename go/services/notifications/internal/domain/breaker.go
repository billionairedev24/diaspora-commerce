package domain

import (
	"sync"
	"time"
)

// BreakerState is a circuit breaker state.
type BreakerState string

// Breaker states.
const (
	BreakerClosed   BreakerState = "closed"
	BreakerOpen     BreakerState = "open"
	BreakerHalfOpen BreakerState = "half-open"
)

// BreakerSettings configure every provider breaker.
type BreakerSettings struct {
	// FailureThreshold consecutive failures open the breaker.
	FailureThreshold int
	// CoolDown is how long an open breaker rejects calls before allowing a half-open probe.
	CoolDown time.Duration
	// HalfOpenProbes is how many concurrent trial calls a half-open breaker admits.
	HalfOpenProbes int
}

// Breakers holds one circuit breaker per provider name.
type Breakers interface {
	// Allow reports whether a call to provider may proceed (and reserves a half-open probe slot).
	Allow(provider string) bool
	// Success records a successful call, closing a half-open breaker.
	Success(provider string)
	// Failure records a failed call; enough consecutive failures, or any half-open failure, open the breaker.
	Failure(provider string)
	// State returns a provider's current state.
	State(provider string) BreakerState
}

type breaker struct {
	state    BreakerState
	failures int
	openedAt time.Time
	probes   int
}

type breakersImpl struct {
	settings BreakerSettings
	now      func() time.Time
	mu       sync.Mutex
	byName   map[string]*breaker
}

var _ Breakers = (*breakersImpl)(nil)

// NewBreakers returns breakers using the wall clock.
func NewBreakers(s BreakerSettings) Breakers { return NewBreakersWithClock(s, time.Now) }

// NewBreakersWithClock returns breakers with an injectable clock (tests).
func NewBreakersWithClock(s BreakerSettings, now func() time.Time) Breakers {
	if s.FailureThreshold <= 0 {
		s.FailureThreshold = 5
	}
	if s.CoolDown <= 0 {
		s.CoolDown = 30 * time.Second
	}
	if s.HalfOpenProbes <= 0 {
		s.HalfOpenProbes = 1
	}
	return &breakersImpl{settings: s, now: now, byName: map[string]*breaker{}}
}

func (b *breakersImpl) get(name string) *breaker {
	br, ok := b.byName[name]
	if !ok {
		br = &breaker{state: BreakerClosed}
		b.byName[name] = br
	}
	return br
}

// advance moves an open breaker to half-open once the cool-down has elapsed.
func (b *breakersImpl) advance(br *breaker) {
	if br.state == BreakerOpen && b.now().Sub(br.openedAt) >= b.settings.CoolDown {
		br.state, br.probes = BreakerHalfOpen, 0
	}
}

func (b *breakersImpl) Allow(name string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	br := b.get(name)
	b.advance(br)
	switch br.state {
	case BreakerOpen:
		return false
	case BreakerHalfOpen:
		if br.probes >= b.settings.HalfOpenProbes {
			return false
		}
		br.probes++
		return true
	default:
		return true
	}
}

func (b *breakersImpl) Success(name string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	br := b.get(name)
	br.state, br.failures, br.probes = BreakerClosed, 0, 0
}

func (b *breakersImpl) Failure(name string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	br := b.get(name)
	br.failures++
	if br.state == BreakerHalfOpen || br.failures >= b.settings.FailureThreshold {
		br.state, br.openedAt, br.probes = BreakerOpen, b.now(), 0
	}
}

func (b *breakersImpl) State(name string) BreakerState {
	b.mu.Lock()
	defer b.mu.Unlock()
	br := b.get(name)
	b.advance(br)
	return br.state
}
