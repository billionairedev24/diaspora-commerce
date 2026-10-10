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
