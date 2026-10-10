package app

import (
	"context"
	"fmt"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// NotificationLog is the staff console's view of every message sent and the dead-letter queue.
type NotificationLog interface {
	List(ctx context.Context, f domain.ListFilter) ([]domain.Notification, error)
	Get(ctx context.Context, id string) (domain.Notification, error)
	DeadLetters(ctx context.Context, limit int) ([]domain.DeadLetter, error)
	Replay(ctx context.Context, deadLetterID string) (domain.Notification, error)
}

type notificationLogImpl struct{ store port.NotificationStore }

var _ NotificationLog = (*notificationLogImpl)(nil)

// NewNotificationLog returns the NotificationLog use case.
func NewNotificationLog(store port.NotificationStore) NotificationLog {
	return &notificationLogImpl{store: store}
}

func (l *notificationLogImpl) List(ctx context.Context, f domain.ListFilter) ([]domain.Notification, error) {
	if f.Limit <= 0 || f.Limit > 200 {
		f.Limit = 50
	}
	out, err := l.store.List(ctx, f)
	if err != nil {
		return nil, fmt.Errorf("list: %w", err)
	}
	return out, nil
}

func (l *notificationLogImpl) Get(ctx context.Context, id string) (domain.Notification, error) {
	n, err := l.store.Get(ctx, id)
	if err != nil {
		return domain.Notification{}, fmt.Errorf("get: %w", err)
	}
	return n, nil
}

func (l *notificationLogImpl) DeadLetters(ctx context.Context, limit int) ([]domain.DeadLetter, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	out, err := l.store.DeadLetters(ctx, limit)
	if err != nil {
		return nil, fmt.Errorf("dead letters: %w", err)
	}
	return out, nil
}

func (l *notificationLogImpl) Replay(ctx context.Context, id string) (domain.Notification, error) {
	n, err := l.store.Replay(ctx, id)
	if err != nil {
		return domain.Notification{}, fmt.Errorf("replay: %w", err)
	}
	return n, nil
}
