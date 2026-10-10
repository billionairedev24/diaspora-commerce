package app

import (
	"context"
	"errors"
	"fmt"
	"slices"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// target is one resolved recipient with what identity told us about them.
type target struct {
	recipient domain.Recipient
	firstName string
	market    string
	disabled  []domain.Channel
	// channels, when set, replaces the rule's channels for this target (an audience member consented on one channel).
	channels []domain.Channel
	// extra is per-recipient template data (the member's unsubscribe link).
	extra map[string]any
}

func hasAddress(ch domain.Channel, r domain.Recipient) bool { return len(addresses(ch, r)) > 0 }

// fill completes r from identity when its user id is known and one of channels has no address. Addresses already in
// the recipient (from the payload or the request) win: they are what the producer or the shopper asked for. An
// unknown user leaves r as it is; other lookup errors are returned (transient: the event is redelivered).
func fill(ctx context.Context, res port.RecipientResolver, r domain.Recipient, channels []domain.Channel) (target, error) {
	t := target{recipient: r}
	if res == nil || r.ID == "" || !slices.ContainsFunc(channels, func(c domain.Channel) bool { return !hasAddress(c, r) }) {
		return t, nil
	}
	c, err := res.Contact(ctx, r.ID)
	if errors.Is(err, domain.ErrRecipientNotFound) {
		return t, nil
	}
	if err != nil {
		return t, fmt.Errorf("resolve recipient %s: %w", r.ID, err)
	}
	if t.recipient.Email == "" {
		t.recipient.Email = c.Email
	}
	if t.recipient.Phone == "" {
		t.recipient.Phone = c.Phone
	}
	if len(t.recipient.PushTokens) == 0 {
		t.recipient.PushTokens = c.PushTokens
	}
	if t.recipient.TimeZone == "" {
		t.recipient.TimeZone = c.TimeZone
	}
	if t.recipient.Locale == "" {
		t.recipient.Locale = c.Locale
	}
	t.firstName, t.market, t.disabled = c.FirstName, c.Market, c.Disabled
	return t, nil
}

// audience pages a pre-order window's audience on each channel: one target per member and channel, addressed with
// what delivery-runs knows (email-only subscribers) or identity's verified contact (signed-in members).
func (h *ingestImpl) audience(ctx context.Context, rule EventRule, windowID string, channels []domain.Channel) ([]target, error) {
	if h.cfg.Audience == nil || windowID == "" {
		h.log.DebugContext(ctx, "no pre-order audience source; rule skipped", "template", rule.Template)
		return nil, nil
	}
	segment := rule.Segment
	if segment == "" {
		segment = port.AudienceSubscribers
	}
	var out []target
	for _, ch := range channels {
		after := ""
		for range maxAudiencePages {
			page, err := h.cfg.Audience.Audience(ctx, windowID, ch, segment, after, h.cfg.AudiencePageSize)
			if errors.Is(err, domain.ErrRecipientNotFound) {
				return out, nil
			}
			if err != nil {
				return nil, fmt.Errorf("audience of %s on %s: %w", windowID, ch, err)
			}
			for _, m := range page.Members {
				r := domain.Recipient{ID: m.UserID}
				switch ch {
				case domain.ChannelEmail:
					r.Email = m.Email
				case domain.ChannelSMS:
					r.Phone = m.Phone
				case domain.ChannelPush, domain.ChannelChat:
					// push tokens and chat numbers come from identity
				}
				one := []domain.Channel{ch}
				t, err := fill(ctx, h.cfg.Resolver, r, one)
				if err != nil {
					return nil, err
				}
				if !hasAddress(ch, t.recipient) {
					continue
				}
				t.channels = one
				t.extra = map[string]any{"unsubscribeUrl": m.UnsubscribeURL}
				out = append(out, t)
			}
			if page.Next == "" || page.Next == after {
				break
			}
			after = page.Next
		}
	}
	return out, nil
}

// enabled drops channels the user switched off in identity.
func enabled(channels, disabled []domain.Channel) []domain.Channel {
	out := make([]domain.Channel, 0, len(channels))
	for _, c := range channels {
		if !slices.Contains(disabled, c) {
			out = append(out, c)
		}
	}
	return out
}
