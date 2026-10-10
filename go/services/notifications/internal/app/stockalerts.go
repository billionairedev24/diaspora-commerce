package app

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// StockAlertTemplate is the back-in-stock template key.
const StockAlertTemplate = "stock.back_in_stock"

// stockAlertBatch bounds how many alerts one restock event sends; the rest go out on the next availability event.
const stockAlertBatch = 500

// StockAlerts manages shoppers' back-in-stock subscriptions and sends them when inventory restocks a variant.
type StockAlerts interface {
	// Subscribe arms an alert for the shopper and variant. created is false when an active alert already existed.
	Subscribe(ctx context.Context, a domain.StockAlert) (alert domain.StockAlert, created bool, err error)
	Unsubscribe(ctx context.Context, userID, atin string) error
	List(ctx context.Context, userID string) ([]domain.StockAlert, error)
	// HandleAvailability sends every active alert for the event's SKU (= variant ATIN) once it has units available.
	HandleAvailability(ctx context.Context, e events.Envelope) error
}

type stockAlertsImpl struct {
	store    port.StockAlertStore
	notifier Notifier
	resolver port.RecipientResolver
	links    Links
	log      *slog.Logger
}

var _ StockAlerts = (*stockAlertsImpl)(nil)

// NewStockAlerts returns the StockAlerts use case. resolver may be nil (then only the alert's own email is used).
func NewStockAlerts(store port.StockAlertStore, notifier Notifier, resolver port.RecipientResolver, links Links,
	log *slog.Logger) StockAlerts {
	return &stockAlertsImpl{store: store, notifier: notifier, resolver: resolver, links: links, log: log}
}

func (s *stockAlertsImpl) Subscribe(ctx context.Context, a domain.StockAlert) (domain.StockAlert, bool, error) {
	a, err := a.Validate()
	if err != nil {
		return a, false, fmt.Errorf("stock alert: %w", err)
	}
	out, created, err := s.store.Subscribe(ctx, a)
	if err != nil {
		return out, false, fmt.Errorf("subscribe: %w", err)
	}
	return out, created, nil
}

func (s *stockAlertsImpl) Unsubscribe(ctx context.Context, userID, atin string) error {
	norm, ok := domain.NormalizeAtin(atin)
	if !ok {
		return domain.ErrNotFound
	}
	if err := s.store.Unsubscribe(ctx, userID, norm); err != nil {
		return fmt.Errorf("unsubscribe: %w", err)
	}
	return nil
}

func (s *stockAlertsImpl) List(ctx context.Context, userID string) ([]domain.StockAlert, error) {
	out, err := s.store.ListByUser(ctx, userID)
	if err != nil {
		return nil, fmt.Errorf("list stock alerts: %w", err)
	}
	return out, nil
}

// availability is the part of inventory.availability.changed the alerts need.
type availability struct {
	Sku       string `json:"sku"`
	Available int    `json:"available"`
}

func (s *stockAlertsImpl) HandleAvailability(ctx context.Context, e events.Envelope) error {
	var av availability
	if err := json.Unmarshal(e.Payload, &av); err != nil || e.ID == "" {
		s.log.WarnContext(ctx, "undecodable availability event skipped", "eventId", e.ID, "error", err)
		return nil
	}
	atin, ok := domain.NormalizeAtin(av.Sku)
	if !ok || av.Available <= 0 {
		return nil
	}
	// Claiming is idempotent per event id, so a crash before the enqueue below is finished by the redelivery, and
	// the inbox in SubmitForEvent stops a second send.
	alerts, err := s.store.ClaimForRestock(ctx, atin, e.ID, stockAlertBatch)
	if err != nil {
		return fmt.Errorf("claim alerts for %s: %w", atin, err)
	}
	if len(alerts) == 0 {
		return nil
	}
	channels := []domain.Channel{domain.ChannelEmail, domain.ChannelPush}
	reqs := make([]domain.Request, 0, len(alerts))
	for _, a := range alerts {
		t, err := fill(ctx, s.resolver, domain.Recipient{ID: a.UserID, Email: a.Email, Locale: a.Locale}, channels)
		if err != nil {
			return err
		}
		title := a.ProductTitle
		if title == "" {
			title = "The item you were waiting for"
		}
		d := map[string]any{"atin": a.Atin, "productTitle": title, "productUrl": join(s.links.Storefront, "/p/"+a.Atin)}
		setDefault(d, "firstName", t.firstName)
		setDefault(d, "firstName", "there")
		reqs = append(reqs, domain.Request{TemplateKey: StockAlertTemplate, Channels: enabled(channels, t.disabled),
			Recipient: t.recipient, Market: a.Market, Locale: a.Locale, Data: d,
			// One idempotency base per alert so two shoppers sharing an address still both count as sent.
			IdempotencyKey: "stock:" + a.ID + ":" + e.ID})
	}
	return submitEvent(ctx, s.notifier, s.log, e, reqs)
}
