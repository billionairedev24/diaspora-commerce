package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// ConsumerGroup is the Kafka consumer group and inbox consumer name.
const ConsumerGroup = "notifications"

// Topics consumed by the ingest.
var Topics = []string{"at.identity.events", "at.orders.events", "at.seller.events", "at.bookings.events"}

// EventRule maps an event type to a template and the channels to try.
type EventRule struct {
	Template string
	Channels []domain.Channel
}

// DefaultEventRules are the platform's event → notification mappings.
var DefaultEventRules = map[string]EventRule{
	"identity.verification.requested": {Template: "identity.verification", Channels: []domain.Channel{domain.ChannelEmail}},
	"order.placed":                    {Template: "order.confirmed", Channels: []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush}},
	"shipment.out_for_delivery":       {Template: "shipment.out_for_delivery", Channels: []domain.Channel{domain.ChannelSMS, domain.ChannelPush, domain.ChannelEmail}},
	"seller.payout.requirement_due":   {Template: "payout.requirement_due", Channels: []domain.Channel{domain.ChannelEmail, domain.ChannelSMS}},
	"booking.confirmed":               {Template: "booking.confirmed", Channels: []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush}},
}

// GenericEventType lets any producer request a templated notification explicitly.
const GenericEventType = "notification.requested"

// eventPayload is the shared payload convention: a recipient block, optional market/locale/channel overrides,
// and every other field becomes template data.
type eventPayload struct {
	Recipient   domain.Recipient `json:"recipient"`
	Market      string           `json:"market"`
	Locale      string           `json:"locale"`
	Channel     domain.Channel   `json:"channel"`     // single-channel override (e.g. sms for OTP)
	Channels    []domain.Channel `json:"channels"`    // generic events
	TemplateKey string           `json:"templateKey"` // generic events
	Data        map[string]any   `json:"data"`        // generic events
}

// Ingest maps platform events to notification requests and enqueues them, once per event id.
type Ingest interface {
	events.Handler
}

type ingestImpl struct {
	notifier Notifier
	rules    map[string]EventRule
	log      *slog.Logger
}

var _ Ingest = (*ingestImpl)(nil)

// NewIngest returns the Kafka event handler.
func NewIngest(notifier Notifier, rules map[string]EventRule, log *slog.Logger) Ingest {
	if rules == nil {
		rules = DefaultEventRules
	}
	return &ingestImpl{notifier: notifier, rules: rules, log: log}
}

// Handle returns an error only for infrastructure failures (so the event is redelivered); malformed or
// unmappable events are logged and skipped so one poison message cannot stall a partition.
func (h *ingestImpl) Handle(ctx context.Context, e events.Envelope) error {
	req, ok := h.toRequest(ctx, e)
	if !ok {
		return nil
	}
	first, err := h.notifier.SubmitForEvent(ctx, ConsumerGroup, e.ID, []domain.Request{req})
	if errors.Is(err, domain.ErrInvalidRequest) {
		h.log.WarnContext(ctx, "event not deliverable; skipped", "eventId", e.ID, "type", e.Type, "error", err)
		return nil
	}
	if err != nil {
		return fmt.Errorf("event %s: %w", e.ID, err)
	}
	if !first {
		h.log.DebugContext(ctx, "duplicate event ignored", "eventId", e.ID)
	}
	return nil
}

func (h *ingestImpl) toRequest(ctx context.Context, e events.Envelope) (domain.Request, bool) {
	if e.ID == "" {
		h.log.WarnContext(ctx, "event without id skipped", "type", e.Type)
		return domain.Request{}, false
	}
	var p eventPayload
	if err := json.Unmarshal(e.Payload, &p); err != nil {
		h.log.WarnContext(ctx, "undecodable event skipped", "eventId", e.ID, "type", e.Type, "error", err)
		return domain.Request{}, false
	}
	req := domain.Request{Recipient: p.Recipient, Market: p.Market, Locale: p.Locale, EventID: e.ID}
	if e.Type == GenericEventType {
		req.TemplateKey, req.Channels, req.Data = p.TemplateKey, p.Channels, p.Data
		return req, true
	}
	rule, ok := h.rules[e.Type]
	if !ok {
		return domain.Request{}, false
	}
	var data map[string]any
	if err := json.Unmarshal(e.Payload, &data); err != nil {
		return domain.Request{}, false
	}
	delete(data, "recipient")
	if req.Recipient.ID == "" {
		req.Recipient.ID = e.AggregateID
	}
	req.TemplateKey, req.Channels, req.Data = rule.Template, rule.Channels, data
	if p.Channel.Valid() {
		req.Channels = []domain.Channel{p.Channel}
	}
	return req, true
}
