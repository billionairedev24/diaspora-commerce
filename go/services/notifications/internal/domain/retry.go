package domain

import (
	"math/rand/v2"
	"time"
)

// RetryPolicy is exponential backoff with jitter for re-queued notifications.
type RetryPolicy struct {
	Base        time.Duration
	Max         time.Duration
	MaxAttempts int
}

// DefaultRetryPolicy is 30s, 1m, 2m, 4m … capped at 1h, dead-lettered after 8 attempts.
var DefaultRetryPolicy = RetryPolicy{Base: 30 * time.Second, Max: time.Hour, MaxAttempts: 8}

// Delay returns the wait before attempt n+1 after n failed attempts (n >= 1), with ±20% jitter.
func (p RetryPolicy) Delay(attempts int) time.Duration {
	if attempts < 1 {
		attempts = 1
	}
	d := p.Base
	for i := 1; i < attempts && d < p.Max; i++ {
		d *= 2
	}
	if d > p.Max {
		d = p.Max
	}
	jitter := time.Duration(float64(d) * 0.2 * (rand.Float64()*2 - 1)) //nolint:gosec // jitter, not security
	return d + jitter
}

// Exhausted reports whether attempts reached the cap.
func (p RetryPolicy) Exhausted(attempts int) bool { return attempts >= p.MaxAttempts }
