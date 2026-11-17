package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Failure reasons (the notifications_events_failed_total label and failed_event.reason).
const (
	// ReasonUpstreamClientError: a service answered 4xx other than 408/429 (403 without a scope, 400, 401 ...): the same
	// call fails the same way every time.
	ReasonUpstreamClientError = "upstream_client_error"
	// ReasonRejected: the event can never be processed for another reason (an error marked domain.ErrInvalidRequest).
	ReasonRejected = "rejected"
	// ReasonRetriesExhausted: a transient failure (5xx, timeout, connection error) that outlasted every retry round.
	ReasonRetriesExhausted = "retries_exhausted"
)

// RetryPolicy bounds how long one event may hold the consumer back. Zero values take the defaults.
type RetryPolicy struct {
	// Attempts is how many times one delivery tries the event, with exponential backoff between tries (default 4).
	Attempts int
	// Backoff is the wait after the first failed try, doubling up to MaxBackoff (defaults 500ms and 8s).
	Backoff    time.Duration
	MaxBackoff time.Duration
	// MaxRounds is how many deliveries (each of Attempts tries; the consumer is restarted between them, which keeps
	// the event in Kafka) a transient failure may last before the event is dead-lettered (default 6, about two minutes).
	MaxRounds int
}

func (p RetryPolicy) withDefaults() RetryPolicy {
	if p.Attempts <= 0 {
		p.Attempts = 4
	}
	if p.Backoff <= 0 {
		p.Backoff = 500 * time.Millisecond
	}
	if p.MaxBackoff <= 0 {
		p.MaxBackoff = 8 * time.Second
	}
	if p.MaxRounds <= 0 {
		p.MaxRounds = 6
	}
	return p
}

// Classify reports whether err is permanent (retrying cannot help) and why. Permanent: another service's 4xx other than
// 408/429, and requests the notifier rejected. Everything else is treated as transient: 5xx, timeouts, connection and
// database errors.
func Classify(err error) (permanent bool, reason string) {
	var up *domain.UpstreamError
	switch {
	case errors.As(err, &up):
		if up.Transient() {
			return false, ReasonRetriesExhausted
		}
		return true, ReasonUpstreamClientError
	case errors.Is(err, domain.ErrInvalidRequest):
		return true, ReasonRejected
	}
	return false, ReasonRetriesExhausted
}

type resilientHandler struct {
	next    events.Handler
	store   port.FailedEventStore
	counter port.FailureCounter
	policy  RetryPolicy
	log     *slog.Logger
	sleep   func(ctx context.Context, d time.Duration) error
	// failing and rounds count the deliveries that ended in a transient failure for one event id. The consumer is
	// single-threaded per group member and redelivers the same event first, so one slot is enough.
	failing string
	rounds  int
}

var _ events.Handler = (*resilientHandler)(nil)

// NewResilientHandler wraps the event handler so that one failing event cannot wedge the consumer:
//   - transient errors (5xx, timeouts, connection errors) are retried with exponential backoff, Attempts times per
//     delivery. When they persist the error is returned (the consumer restarts and the event is redelivered) up to
//     MaxRounds times, then the event is dead-lettered as retries_exhausted;
//   - permanent errors (another service's 4xx: a 403 because the service token lacks a scope, a 400 ...) are dead-lettered
//     at once.
//
// Dead-lettering writes a failed_event row (event id, type, error class, status, payload for replay), counts
// notifications_events_failed_total{reason}, logs a WARN without recipient data and returns nil, so the offset is
// committed and later events flow. If the row cannot be written the error is returned and nothing is committed.
// A recipient the identity service does not know (404) never reaches this handler: it is skipped in the ingest.
func NewResilientHandler(next events.Handler, store port.FailedEventStore, counter port.FailureCounter, policy RetryPolicy,
	log *slog.Logger) events.Handler {
	return &resilientHandler{next: next, store: store, counter: counter, policy: policy.withDefaults(), log: log,
		sleep: sleepCtx}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return fmt.Errorf("wait: %w", ctx.Err())
	case <-t.C:
		return nil
	}
}

func (h *resilientHandler) Handle(ctx context.Context, e events.Envelope) error {
	wait := h.policy.Backoff
	var err error
	for attempt := 1; attempt <= h.policy.Attempts; attempt++ {
		if err = h.next.Handle(ctx, e); err == nil {
			h.failing, h.rounds = "", 0
			return nil
		}
		if ctx.Err() != nil {
			return err
		}
		if permanent, reason := Classify(err); permanent {
			return h.deadLetter(ctx, e, err, reason, "permanent", attempt)
		}
		h.log.WarnContext(ctx, "event handling failed; retrying", "eventId", e.ID, "type", e.Type, "attempt", attempt,
			"of", h.policy.Attempts, "transient", true, "error", err)
		if attempt == h.policy.Attempts {
			break
		}
		if serr := h.sleep(ctx, wait); serr != nil {
			return err
		}
		wait = min(wait*2, h.policy.MaxBackoff)
	}
	if h.failing != e.ID {
		h.failing, h.rounds = e.ID, 0
	}
	h.rounds++
	if h.rounds >= h.policy.MaxRounds {
		return h.deadLetter(ctx, e, err, ReasonRetriesExhausted, "transient", h.policy.Attempts*h.rounds)
	}
	return fmt.Errorf("event %s: transient failure, delivery %d of %d: %w", e.ID, h.rounds, h.policy.MaxRounds, err)
}

func (h *resilientHandler) deadLetter(ctx context.Context, e events.Envelope, cause error, reason, class string,
	attempts int) error {
	f := domain.FailedEvent{Consumer: ConsumerGroup, EventID: e.ID, EventType: e.Type, AggregateID: e.AggregateID,
		Reason: reason, ErrorClass: class, Attempts: attempts, Payload: e.Payload, FailedAt: time.Now().UTC()}
	var up *domain.UpstreamError
	if errors.As(cause, &up) {
		f.Status, f.Code = up.Status, up.Code
	}
	if err := h.store.RecordFailedEvent(ctx, f); err != nil {
		return fmt.Errorf("record failed event %s (after %w): %w", e.ID, cause, err)
	}
	if h.counter != nil {
		h.counter.EventFailed(reason)
	}
	h.failing, h.rounds = "", 0
	// No error text: it can carry upstream response bodies. Class, status and problem code are enough to triage.
	h.log.WarnContext(ctx, "event dead-lettered; offset committed, consumer continues", "eventId", e.ID, "type", e.Type,
		"reason", reason, "errorClass", class, "upstreamStatus", f.Status, "upstreamCode", f.Code, "attempts", attempts)
	return nil
}
