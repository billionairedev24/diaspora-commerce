package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
)

// deadLetterAggregate makes poison events land on topic at.authz-sync.dlq.events.
const deadLetterAggregate = "authz-sync.dlq"

// ErrPoison marks an event that can never be applied (malformed payload, tuple rejected by the model). Only these
// are dead-lettered; everything else is retried until it succeeds.
var ErrPoison = errors.New("poison event")

type retryingHandler struct {
	next       events.Handler
	dlq        events.Publisher
	backoff    time.Duration
	maxBackoff time.Duration
	log        *slog.Logger
}

var _ events.Handler = (*retryingHandler)(nil)

// NewRetryingHandler retries transient failures (OpenFGA unavailable) with capped exponential backoff for as long as
// the context lives, so the partition waits instead of losing a grant or — worse — a revocation. Poison events
// (ErrPoison) are parked on at.authz-sync.dlq.events at once so one bad event never blocks a partition.
func NewRetryingHandler(next events.Handler, dlq events.Publisher, backoff, maxBackoff time.Duration,
	log *slog.Logger,
) events.Handler {
	if backoff <= 0 {
		backoff = 250 * time.Millisecond
	}
	if maxBackoff < backoff {
		maxBackoff = backoff
	}
	return &retryingHandler{next: next, dlq: dlq, backoff: backoff, maxBackoff: maxBackoff, log: log}
}

func (r *retryingHandler) Handle(ctx context.Context, e events.Envelope) error {
	wait := r.backoff
	for attempt := 1; ; attempt++ {
		err := r.next.Handle(ctx, e)
		if err == nil {
			return nil
		}
		if errors.Is(err, ErrPoison) {
			return r.deadLetter(ctx, e, err)
		}
		r.log.WarnContext(ctx, "tuple sync failed, retrying", "event_id", e.ID, "type", e.Type, "attempt", attempt,
			"retry_in", wait, "error", err)
		select {
		case <-ctx.Done():
			return fmt.Errorf("cancelled: %w", ctx.Err())
		case <-time.After(wait):
		}
		wait = min(wait*2, r.maxBackoff)
	}
}

func (r *retryingHandler) deadLetter(ctx context.Context, e events.Envelope, cause error) error {
	if err := r.dlq.Publish(ctx, events.Envelope{
		ID: e.ID, Type: e.Type, Aggregate: deadLetterAggregate,
		AggregateID: e.AggregateID, OccurredAt: e.OccurredAt, Payload: e.Payload,
	}); err != nil {
		return fmt.Errorf("dead-letter %s: %w (after %w)", e.ID, err, cause)
	}
	r.log.ErrorContext(ctx, "event dead-lettered", "event_id", e.ID, "type", e.Type, "error", cause)
	return nil
}
