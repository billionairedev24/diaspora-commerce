package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"slices"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// eventPayload is the notifications payload convention: an optional recipient block, optional market/locale/channel
// overrides, and every other top-level field becomes template data. Producers that only carry user or org ids are
// resolved through identity (see EventRule.Recipient).
type eventPayload struct {
	Recipient   domain.Recipient `json:"recipient"`
	Market      string           `json:"market"`
	Locale      string           `json:"locale"`
	Channel     domain.Channel   `json:"channel"`     // single-channel override (e.g. sms for OTP)
	Channels    []string         `json:"channels"`    // generic events; seller compliance (email, sms, in_app)
	TemplateKey string           `json:"templateKey"` // generic events
	Data        map[string]any   `json:"data"`        // generic events
}

// Ingest maps platform events to notification requests and enqueues them, once per event id.
type Ingest interface {
	events.Handler
}

// IngestConfig wires the ingest. Zero values are safe: nil Rules uses DefaultEventRules, a nil Resolver sends only
// to addresses in payloads, a nil StockAlerts ignores inventory events.
type IngestConfig struct {
	Rules       map[string][]EventRule
	Resolver    port.RecipientResolver
	Links       Links
	StockAlerts StockAlerts
}

type ingestImpl struct {
	notifier Notifier
	cfg      IngestConfig
	log      *slog.Logger
}

var _ Ingest = (*ingestImpl)(nil)

// NewIngest returns the Kafka event handler.
func NewIngest(notifier Notifier, cfg IngestConfig, log *slog.Logger) Ingest {
	if cfg.Rules == nil {
		cfg.Rules = DefaultEventRules
	}
	return &ingestImpl{notifier: notifier, cfg: cfg, log: log}
}

// Handle returns an error only for infrastructure failures (so the event is redelivered); malformed, unmappable or
// undeliverable events are logged and skipped so one poison message cannot stall a partition.
func (h *ingestImpl) Handle(ctx context.Context, e events.Envelope) error {
	if e.Type == AvailabilityChangedType {
		if h.cfg.StockAlerts == nil {
			return nil
		}
		if err := h.cfg.StockAlerts.HandleAvailability(ctx, e); err != nil {
			return fmt.Errorf("stock alerts: %w", err)
		}
		return nil
	}
	rules, mapped := h.cfg.Rules[e.Type]
	if e.Type != GenericEventType && !mapped {
		return nil
	}
	if e.ID == "" {
		h.log.WarnContext(ctx, "event without id skipped", "type", e.Type)
		return nil
	}
	var p eventPayload
	data, err := decodePayload(e.Payload, &p)
	if err != nil {
		h.log.WarnContext(ctx, "undecodable event skipped", "eventId", e.ID, "type", e.Type, "error", err)
		return nil
	}
	var reqs []domain.Request
	if e.Type == GenericEventType {
		req, err := h.generic(ctx, p)
		if err != nil {
			return fmt.Errorf("event %s: %w", e.ID, err)
		}
		reqs = append(reqs, req)
	} else {
		for _, rule := range rules {
			if rule.When != nil && !rule.When(data) {
				continue
			}
			rs, err := h.forRule(ctx, rule, data, p)
			if err != nil {
				return fmt.Errorf("event %s: %w", e.ID, err)
			}
			reqs = append(reqs, rs...)
		}
	}
	if len(reqs) == 0 {
		h.log.DebugContext(ctx, "event needs no notification", "eventId", e.ID, "type", e.Type)
		return nil
	}
	return submitEvent(ctx, h.notifier, h.log, e, reqs)
}

// submitEvent enqueues an event's requests once per event id. Undeliverable requests are logged, not retried.
func submitEvent(ctx context.Context, n Notifier, log *slog.Logger, e events.Envelope, reqs []domain.Request) error {
	first, err := n.SubmitForEvent(ctx, ConsumerGroup, e.ID, reqs)
	if errors.Is(err, domain.ErrInvalidRequest) {
		log.WarnContext(ctx, "event not deliverable; skipped", "eventId", e.ID, "type", e.Type, "error", err)
		return nil
	}
	if err != nil {
		return fmt.Errorf("event %s: %w", e.ID, err)
	}
	if !first {
		log.DebugContext(ctx, "duplicate event ignored", "eventId", e.ID)
	}
	return nil
}

// decodePayload decodes the convention fields into p and the whole object (numbers as json.Number, recipient
// removed) as template data.
func decodePayload(raw []byte, p *eventPayload) (map[string]any, error) {
	if err := json.Unmarshal(raw, p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var data map[string]any
	if err := dec.Decode(&data); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	if data == nil {
		data = map[string]any{}
	}
	delete(data, "recipient")
	return data, nil
}

func (h *ingestImpl) generic(ctx context.Context, p eventPayload) (domain.Request, error) {
	channels := make([]domain.Channel, 0, len(p.Channels))
	for _, c := range p.Channels {
		channels = append(channels, domain.Channel(c))
	}
	t, err := fill(ctx, h.cfg.Resolver, p.Recipient, channels)
	if err != nil {
		return domain.Request{}, err
	}
	return domain.Request{TemplateKey: p.TemplateKey, Channels: channels, Recipient: t.recipient, Market: p.Market,
		Locale: p.Locale, Data: p.Data}, nil
}

// channelsFor applies the payload's channel overrides to the rule's channels.
func channelsFor(rule EventRule, p eventPayload) []domain.Channel {
	if p.Channel.Valid() {
		return []domain.Channel{p.Channel}
	}
	if rule.PayloadChannels && len(p.Channels) > 0 {
		var out []domain.Channel
		for _, c := range p.Channels {
			ch := domain.Channel(c)
			if c == "in_app" {
				ch = domain.ChannelPush
			}
			if ch.Valid() && !slices.Contains(out, ch) {
				out = append(out, ch)
			}
		}
		if len(out) > 0 {
			return out
		}
	}
	return rule.Channels
}

// targets resolves who a rule's notification goes to.
func (h *ingestImpl) targets(ctx context.Context, rule EventRule, data map[string]any, p eventPayload,
	channels []domain.Channel) ([]target, error) {
	switch rule.Recipient {
	case RecipientUser:
		id := str(data, rule.IDField)
		if id == "" {
			return nil, nil
		}
		t, err := fill(ctx, h.cfg.Resolver, domain.Recipient{ID: id}, channels)
		if err != nil {
			return nil, err
		}
		return []target{t}, nil
	case RecipientOrgOwners:
		org := str(data, rule.IDField)
		if org == "" || h.cfg.Resolver == nil {
			return nil, nil
		}
		owners, err := h.cfg.Resolver.OrgOwners(ctx, org)
		if errors.Is(err, domain.ErrRecipientNotFound) {
			return nil, nil
		}
		if err != nil {
			return nil, fmt.Errorf("resolve owners of %s: %w", org, err)
		}
		out := make([]target, 0, len(owners))
		for _, id := range owners {
			t, err := fill(ctx, h.cfg.Resolver, domain.Recipient{ID: id}, channels)
			if err != nil {
				return nil, err
			}
			out = append(out, t)
		}
		return out, nil
	default: // RecipientPayload
		r := p.Recipient
		if r.Email == "" && r.Phone == "" && len(r.PushTokens) == 0 {
			r.Email, r.Phone = str(data, "email"), str(data, "phone")
		}
		if r.ID == "" && rule.IDField != "" {
			r.ID = str(data, rule.IDField)
		}
		if rule.Security {
			return []target{{recipient: r}}, nil
		}
		t, err := fill(ctx, h.cfg.Resolver, r, channels)
		if err != nil {
			return nil, err
		}
		return []target{t}, nil
	}
}

func (h *ingestImpl) forRule(ctx context.Context, rule EventRule, data map[string]any, p eventPayload) ([]domain.Request, error) {
	channels := channelsFor(rule, p)
	targets, err := h.targets(ctx, rule, data, p, channels)
	if err != nil {
		return nil, err
	}
	base := maps.Clone(data)
	if rule.Enrich != nil {
		rule.Enrich(base, h.cfg.Links)
	}
	reqs := make([]domain.Request, 0, len(targets))
	for _, t := range targets {
		chans := channels
		if !rule.Security {
			chans = enabled(channels, t.disabled)
		}
		if len(chans) == 0 {
			continue
		}
		d := maps.Clone(base)
		setDefault(d, "firstName", t.firstName)
		setDefault(d, "firstName", "there")
		market := p.Market
		if market == "" {
			market = t.market
		}
		reqs = append(reqs, domain.Request{TemplateKey: rule.Template, Channels: chans, Recipient: t.recipient,
			Market: market, Locale: p.Locale, Data: d})
	}
	return reqs, nil
}
