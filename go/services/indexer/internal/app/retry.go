package app

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
)

// deadLetterAggregate makes poison events land on topic at.indexer.dlq.events.
const deadLetterAggregate = "indexer.dlq"

type retryingHandler struct {
	next     events.Handler
	dlq      events.Publisher
	attempts int
	backoff  time.Duration
	log      *slog.Logger
}

var _ events.Handler = (*retryingHandler)(nil)

// NewRetryingHandler retries transient failures with exponential backoff and parks poison events on the
// dead-letter topic (at.indexer.dlq.events) so one bad event never blocks the partition.
func NewRetryingHandler(next events.Handler, dlq events.Publisher, attempts int, backoff time.Duration,
	log *slog.Logger,
) events.Handler {
	return &retryingHandler{next: next, dlq: dlq, attempts: attempts, backoff: backoff, log: log}
}

func (r *retryingHandler) Handle(ctx context.Context, e events.Envelope) error {
	var err error
	wait := r.backoff
	for attempt := 1; attempt <= r.attempts; attempt++ {
		if err = r.next.Handle(ctx, e); err == nil {
			return nil
		}
		r.log.WarnContext(ctx, "projection failed", "event_id", e.ID, "type", e.Type, "attempt", attempt, "error", err)
		select {
		case <-ctx.Done():
			return fmt.Errorf("cancelled: %w", ctx.Err())
		case <-time.After(wait):
		}
		wait *= 2
	}
	if pubErr := r.dlq.Publish(ctx, events.Envelope{ID: e.ID, Type: e.Type, Aggregate: deadLetterAggregate,
		AggregateID: e.AggregateID, OccurredAt: e.OccurredAt, Payload: e.Payload}); pubErr != nil {
		return fmt.Errorf("dead-letter %s: %w (after %w)", e.ID, pubErr, err)
	}
	r.log.ErrorContext(ctx, "event dead-lettered", "event_id", e.ID, "type", e.Type, "error", err)
	return nil
}
