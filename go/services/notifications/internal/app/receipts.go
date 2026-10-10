package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Receipts applies provider delivery webhooks: status updates, plus suppression on bounces and complaints.
type Receipts interface {
	Apply(ctx context.Context, evs []domain.DeliveryEvent) (int, error)
}

type receiptsImpl struct {
	store   port.NotificationStore
	consent port.ConsentStore
	log     *slog.Logger
}

var _ Receipts = (*receiptsImpl)(nil)

// NewReceipts returns the Receipts use case.
func NewReceipts(store port.NotificationStore, consent port.ConsentStore, log *slog.Logger) Receipts {
	return &receiptsImpl{store: store, consent: consent, log: log}
}

func (r *receiptsImpl) Apply(ctx context.Context, evs []domain.DeliveryEvent) (int, error) {
	updated := 0
	var errs []error
	for _, e := range evs {
		if e.OccurredAt.IsZero() {
			e.OccurredAt = time.Now().UTC()
		}
		ok, err := r.store.ApplyDeliveryEvent(ctx, e)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s %s: %w", e.Provider, e.ProviderMessageID, err))
			continue
		}
		if ok {
			updated++
		} else {
			r.log.DebugContext(ctx, "receipt did not change status", "provider", e.Provider, "id", e.ProviderMessageID, "status", e.Status)
		}
		if e.Suppresses() && e.Address != "" {
			if err := r.consent.Suppress(ctx, domain.NormalizeEmail(e.Address), string(e.Status), e.OccurredAt); err != nil {
				errs = append(errs, fmt.Errorf("suppress: %w", err))
			}
		}
	}
	return updated, errors.Join(errs...)
}
