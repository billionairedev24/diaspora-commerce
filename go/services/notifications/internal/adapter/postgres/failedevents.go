package postgres

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type failedEventStoreImpl struct{ pool *pgxpool.Pool }

var _ port.FailedEventStore = (*failedEventStoreImpl)(nil)

// NewFailedEventStore returns the dead-letter store for consumed events (table failed_event).
func NewFailedEventStore(pool *pgxpool.Pool) port.FailedEventStore {
	return &failedEventStoreImpl{pool: pool}
}

// RecordFailedEvent upserts per (consumer, event id): a redelivered poison event updates its row instead of failing.
func (s *failedEventStoreImpl) RecordFailedEvent(ctx context.Context, f domain.FailedEvent) error {
	_, err := s.pool.Exec(ctx, `INSERT INTO failed_event
		(consumer, event_id, event_type, aggregate_id, reason, error_class, upstream_status, upstream_code, attempts, payload, failed_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		ON CONFLICT (consumer, event_id) DO UPDATE SET reason = EXCLUDED.reason, error_class = EXCLUDED.error_class,
			upstream_status = EXCLUDED.upstream_status, upstream_code = EXCLUDED.upstream_code,
			attempts = EXCLUDED.attempts, failed_at = EXCLUDED.failed_at, replayed_at = NULL`,
		f.Consumer, f.EventID, f.EventType, f.AggregateID, f.Reason, f.ErrorClass, f.Status, f.Code, f.Attempts,
		f.Payload, f.FailedAt)
	if err != nil {
		return fmt.Errorf("record failed event: %w", err)
	}
	return nil
}
