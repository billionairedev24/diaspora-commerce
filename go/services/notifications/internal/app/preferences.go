package app

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Preferences manages per-recipient channel/category preferences, TCPA SMS consent, one-click unsubscribe
// and the suppression list.
type Preferences interface {
	// Get returns the effective preference for every channel and non-security category.
	Get(ctx context.Context, recipientKey string) ([]domain.Preference, error)
	Set(ctx context.Context, recipientKey string, prefs []domain.Preference) error
	RecordSmsConsent(ctx context.Context, c domain.SmsConsent) error
	SmsConsent(ctx context.Context, phone string) (*domain.SmsConsent, error)
	// Unsubscribe applies a signed one-click unsubscribe token.
	Unsubscribe(ctx context.Context, token string) (UnsubscribeClaims, error)
	Suppressions(ctx context.Context, address string, limit int) ([]domain.Suppression, error)
	Unsuppress(ctx context.Context, address string) error
}

type preferencesImpl struct {
	store port.ConsentStore
	unsub Unsubscriber
}

var _ Preferences = (*preferencesImpl)(nil)

// NewPreferences returns the Preferences use case.
func NewPreferences(store port.ConsentStore, unsub Unsubscriber) Preferences {
	return &preferencesImpl{store: store, unsub: unsub}
}

// Defaults: everything on except marketing, which needs explicit opt-in.
func defaultEnabled(c domain.Category) bool { return c != domain.CategoryMarketing }

func (p *preferencesImpl) Get(ctx context.Context, key string) ([]domain.Preference, error) {
	stored, err := p.store.Preferences(ctx, key)
	if err != nil {
		return nil, fmt.Errorf("preferences: %w", err)
	}
	set := map[[2]string]bool{}
	for _, s := range stored {
		set[[2]string{string(s.Channel), string(s.Category)}] = s.Enabled
	}
	var out []domain.Preference
	for _, ch := range []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush, domain.ChannelChat} {
		for _, c := range []domain.Category{domain.CategoryTransactional, domain.CategoryReminder, domain.CategoryMarketing} {
			en, ok := set[[2]string{string(ch), string(c)}]
			if !ok {
				en = defaultEnabled(c)
			}
			out = append(out, domain.Preference{Channel: ch, Category: c, Enabled: en})
		}
	}
	return out, nil
}

func (p *preferencesImpl) Set(ctx context.Context, key string, prefs []domain.Preference) error {
	for _, pr := range prefs {
		if !pr.Channel.Valid() || !pr.Category.Valid() {
			return errors.Join(domain.ErrInvalidRequest, errors.New("unknown channel or category"))
		}
		if pr.Category == domain.CategorySecurity {
			return errors.Join(domain.ErrInvalidRequest, errors.New("security messages cannot be turned off"))
		}
	}
	if err := p.store.SetPreferences(ctx, key, prefs); err != nil {
		return fmt.Errorf("set preferences: %w", err)
	}
	return nil
}

func (p *preferencesImpl) RecordSmsConsent(ctx context.Context, c domain.SmsConsent) error {
	c.Phone = domain.NormalizePhone(c.Phone)
	if c.Phone == "" || c.Source == "" || c.TextVersion == "" {
		return errors.Join(domain.ErrInvalidRequest, errors.New("phone, source and textVersion are required"))
	}
	if c.ConsentedAt.IsZero() {
		c.ConsentedAt = time.Now().UTC()
	}
	if err := p.store.RecordSmsConsent(ctx, c); err != nil {
		return fmt.Errorf("record consent: %w", err)
	}
	return nil
}

func (p *preferencesImpl) SmsConsent(ctx context.Context, phone string) (*domain.SmsConsent, error) {
	c, err := p.store.SmsConsent(ctx, domain.NormalizePhone(phone))
	if err != nil {
		return nil, fmt.Errorf("sms consent: %w", err)
	}
	if c == nil {
		return nil, domain.ErrNotFound
	}
	return c, nil
}

func (p *preferencesImpl) Unsubscribe(ctx context.Context, token string) (UnsubscribeClaims, error) {
	c, err := p.unsub.Verify(token)
	if err != nil {
		return UnsubscribeClaims{}, fmt.Errorf("verify: %w", err)
	}
	pref := domain.Preference{Channel: c.Channel, Category: c.Category, Enabled: false}
	if err := p.store.SetPreferences(ctx, c.RecipientKey, []domain.Preference{pref}); err != nil {
		return UnsubscribeClaims{}, fmt.Errorf("unsubscribe: %w", err)
	}
	return c, nil
}

func (p *preferencesImpl) Suppressions(ctx context.Context, address string, limit int) ([]domain.Suppression, error) {
	out, err := p.store.Suppressions(ctx, address, limit)
	if err != nil {
		return nil, fmt.Errorf("suppressions: %w", err)
	}
	return out, nil
}

func (p *preferencesImpl) Unsuppress(ctx context.Context, address string) error {
	if err := p.store.Unsuppress(ctx, domain.NormalizeEmail(address)); err != nil {
		return fmt.Errorf("unsuppress: %w", err)
	}
	return nil
}
