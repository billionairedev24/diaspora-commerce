package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// StockAlertTemplate is the back-in-stock template key.
const StockAlertTemplate = "stock.back_in_stock"

// stockAlertBatch bounds how many alerts one restock event sends; the rest go out on the next availability event.
const stockAlertBatch = 500

// Defaults of the stock-alert abuse limits and retention (see StockAlertLimits).
const (
	DefaultMaxOpenAlerts     = 50
	DefaultOpenAlertLifetime = 180 * 24 * time.Hour
	DefaultSentAlertKeep     = 14 * 24 * time.Hour
)

// StockAlertLimits bound what the alerts table can hold. Zero fields take the defaults.
type StockAlertLimits struct {
	// MaxOpen is how many active alerts one account or one email address may hold.
	MaxOpen int
	// OpenLifetime is how long an alert that was never sent is kept before it is purged.
	OpenLifetime time.Duration
	// SentKeep is how long a sent alert is kept (a redelivered restock event finds it again; re-subscribing re-arms it).
	SentKeep time.Duration
}

func (l StockAlertLimits) withDefaults() StockAlertLimits {
	if l.MaxOpen <= 0 {
		l.MaxOpen = DefaultMaxOpenAlerts
	}
	if l.OpenLifetime <= 0 {
		l.OpenLifetime = DefaultOpenAlertLifetime
	}
	if l.SentKeep <= 0 {
		l.SentKeep = DefaultSentAlertKeep
	}
	return l
}

// StockAlerts manages back-in-stock subscriptions of signed-in shoppers and of guests, and sends them when
// inventory restocks a variant.
type StockAlerts interface {
	// Subscribe arms an alert for the signed-in shopper and variant. created is false when an active alert already
	// existed. domain.ErrAlertLimit is returned when the shopper already has the maximum number of open alerts.
	Subscribe(ctx context.Context, a domain.StockAlert) (alert domain.StockAlert, created bool, err error)
	// SubscribeGuest arms an alert for an email address (a.UserID is ignored). It sends nothing and says nothing
	// about the outcome: an address that already has the alert, or is at its cap, gets the same nil as a new one.
	// Only an invalid request is an error.
	SubscribeGuest(ctx context.Context, a domain.StockAlert) error
	Unsubscribe(ctx context.Context, userID, atin string) error
	// UnsubscribeByToken removes the alert a back-in-stock email's signed link names. An alert that is already gone
	// is a success; a tampered or expired token is app.ErrInvalidToken.
	UnsubscribeByToken(ctx context.Context, token string) error
	List(ctx context.Context, userID string) ([]domain.StockAlert, error)
	// HandleAvailability sends every active alert for the event's SKU (= variant ATIN) once it has units available.
	HandleAvailability(ctx context.Context, e events.Envelope) error
	// Purge deletes alerts past their retention and returns how many went.
	Purge(ctx context.Context) (int64, error)
}

type stockAlertsImpl struct {
	store    port.StockAlertStore
	notifier Notifier
	resolver port.RecipientResolver
	links    Links
	unsub    Unsubscriber
	limits   StockAlertLimits
	now      func() time.Time
	log      *slog.Logger
}

var _ StockAlerts = (*stockAlertsImpl)(nil)

// StockAlertOption configures the StockAlerts use case.
type StockAlertOption func(*stockAlertsImpl)

// WithStockAlertLimits sets the open-alert cap and retention.
func WithStockAlertLimits(l StockAlertLimits) StockAlertOption {
	return func(s *stockAlertsImpl) { s.limits = l.withDefaults() }
}

// WithStockAlertClock replaces the clock (tests).
func WithStockAlertClock(now func() time.Time) StockAlertOption {
	return func(s *stockAlertsImpl) { s.now = now }
}

// NewStockAlerts returns the StockAlerts use case. resolver may be nil (then only the alert's own email is used).
// unsub signs the unsubscribe link every back-in-stock email carries.
func NewStockAlerts(store port.StockAlertStore, notifier Notifier, resolver port.RecipientResolver, links Links,
	unsub Unsubscriber, log *slog.Logger, opts ...StockAlertOption) StockAlerts {
	s := &stockAlertsImpl{store: store, notifier: notifier, resolver: resolver, links: links, unsub: unsub,
		limits: StockAlertLimits{}.withDefaults(), now: time.Now, log: log}
	for _, o := range opts {
		o(s)
	}
	return s
}

func (s *stockAlertsImpl) Subscribe(ctx context.Context, a domain.StockAlert) (domain.StockAlert, bool, error) {
	if a.UserID == "" {
		return a, false, errors.Join(domain.ErrInvalidRequest, errors.New("a signed-in shopper is required"))
	}
	a, err := a.Validate()
	if err != nil {
		return a, false, fmt.Errorf("stock alert: %w", err)
	}
	out, created, err := s.store.Subscribe(ctx, a, s.limits.MaxOpen)
	if errors.Is(err, domain.ErrAlertLimit) {
		return out, false, err
	}
	if err != nil {
		return out, false, fmt.Errorf("subscribe: %w", err)
	}
	return out, created, nil
}

func (s *stockAlertsImpl) SubscribeGuest(ctx context.Context, a domain.StockAlert) error {
	a.UserID = ""
	a, err := a.Validate()
	if err != nil {
		return fmt.Errorf("stock alert: %w", err)
	}
	_, _, err = s.store.Subscribe(ctx, a, s.limits.MaxOpen)
	if errors.Is(err, domain.ErrAlertLimit) {
		// Not an error to the caller: it must not learn how many alerts an address holds.
		s.log.WarnContext(ctx, "guest stock alert dropped: address at its open-alert cap", "email", domain.MaskEmail(a.Email),
			"atin", a.Atin, "cap", s.limits.MaxOpen)
		return nil
	}
	if err != nil {
		return fmt.Errorf("subscribe guest: %w", err)
	}
	return nil
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

func (s *stockAlertsImpl) UnsubscribeByToken(ctx context.Context, token string) error {
	c, err := s.unsub.VerifyStockAlert(token)
	if err != nil {
		return fmt.Errorf("verify: %w", err)
	}
	if err := s.store.DeleteByID(ctx, c.AlertID); err != nil {
		return fmt.Errorf("unsubscribe by token: %w", err)
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

func (s *stockAlertsImpl) Purge(ctx context.Context) (int64, error) {
	now := s.now()
	n, err := s.store.Purge(ctx, now.Add(-s.limits.OpenLifetime), now.Add(-s.limits.SentKeep))
	if err != nil {
		return 0, fmt.Errorf("purge: %w", err)
	}
	return n, nil
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
	now := s.now()
	reqs := make([]domain.Request, 0, len(alerts))
	for _, a := range alerts {
		// A guest has no account: email only, to the address they gave. An account also gets a push.
		channels := []domain.Channel{domain.ChannelEmail, domain.ChannelPush}
		if a.Guest() {
			channels = []domain.Channel{domain.ChannelEmail}
		}
		t, err := fill(ctx, s.resolver, domain.Recipient{ID: a.UserID, Email: a.Email, Locale: a.Locale}, channels)
		if err != nil {
			return err
		}
		d := map[string]any{"atin": a.Atin, "productTitle": a.ProductTitle, "productUrl": join(s.links.Storefront, "/p/"+a.Atin),
			// Every back-in-stock email carries this one-click link; it removes this alert and nothing else.
			"alertUnsubscribeUrl": s.unsub.StockAlertURL(a.ID, now)}
		setDefault(d, "firstName", t.firstName)
		reqs = append(reqs, domain.Request{TemplateKey: StockAlertTemplate, Channels: enabled(channels, t.disabled),
			Recipient: t.recipient, Market: a.Market, Locale: a.Locale, Data: d,
			Headers: s.unsub.StockAlertHeaders(a.ID, now),
			// One idempotency base per alert so two shoppers sharing an address still both count as sent.
			IdempotencyKey: "stock:" + a.ID + ":" + e.ID})
	}
	return submitEvent(ctx, s.notifier, s.log, e, reqs)
}
