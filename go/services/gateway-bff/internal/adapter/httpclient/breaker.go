package httpclient

import (
	"sync"
	"time"
)

// BreakerState is closed (calls flow), open (calls fail fast) or half-open (one probe allowed).
type BreakerState int

// Breaker states (also the value of the bff_upstream_breaker_state gauge).
const (
	StateClosed BreakerState = iota
	StateHalfOpen
	StateOpen
)

func (s BreakerState) String() string {
	switch s {
	case StateOpen:
		return "open"
	case StateHalfOpen:
		return "half-open"
	default:
		return "closed"
	}
}

// Breaker is a small consecutive-failure circuit breaker.
type Breaker interface {
	// Allow reports whether a call may proceed (in half-open, only one probe at a time).
	Allow() bool
	// Success records a successful call (closes the breaker).
	Success()
	// Failure records an availability failure (opens after the threshold, or immediately from half-open).
	Failure()
	State() BreakerState
}

type breakerImpl struct {
	threshold int
	cooldown  time.Duration
	now       func() time.Time
	onChange  func(BreakerState)

	mu       sync.Mutex
	state    BreakerState
	failures int
	openedAt time.Time
	probing  bool
}

var _ Breaker = (*breakerImpl)(nil)

// NewBreaker opens after threshold consecutive failures and probes again after cooldown.
func NewBreaker(threshold int, cooldown time.Duration, now func() time.Time, onChange func(BreakerState)) Breaker {
	if now == nil {
		now = time.Now
	}
	if onChange == nil {
		onChange = func(BreakerState) {}
	}
	return &breakerImpl{threshold: max(1, threshold), cooldown: cooldown, now: now, onChange: onChange}
}

func (b *breakerImpl) setState(s BreakerState) {
	if b.state != s {
		b.state = s
		b.onChange(s)
	}
}

func (b *breakerImpl) Allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch b.state {
	case StateOpen:
		if b.now().Sub(b.openedAt) < b.cooldown {
			return false
		}
		b.setState(StateHalfOpen)
		b.probing = true
		return true
	case StateHalfOpen:
		if b.probing {
			return false
		}
		b.probing = true
		return true
	default:
		return true
	}
}

func (b *breakerImpl) Success() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.failures = 0
	b.probing = false
	b.setState(StateClosed)
}

func (b *breakerImpl) Failure() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.probing = false
	b.failures++
	if b.state == StateHalfOpen || b.failures >= b.threshold {
		b.openedAt = b.now()
		b.setState(StateOpen)
	}
}

func (b *breakerImpl) State() BreakerState {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state
}
