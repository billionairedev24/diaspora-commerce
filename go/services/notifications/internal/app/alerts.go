package app

import (
	"context"
	"fmt"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// AlertPreferences manages a back-office member's alert topics (seller: new orders, low stock, payouts, compliance,
// claims; staff: assigned queues, escalations, approvals) per channel and their personal quiet hours for SMS and push.
type AlertPreferences interface {
	Get(ctx context.Context, recipientKey string, audience domain.AlertAudience) (domain.AlertSettings, error)
	// Set applies changes (only the listed topic × channel pairs; quiet hours when not nil) and returns the result.
	Set(ctx context.Context, recipientKey string, audience domain.AlertAudience, prefs []domain.AlertPreference,
		quiet *domain.PersonalQuietHours) (domain.AlertSettings, error)
}

type alertPreferencesImpl struct{ store port.AlertPreferenceStore }

var _ AlertPreferences = (*alertPreferencesImpl)(nil)

// NewAlertPreferences returns the AlertPreferences use case.
func NewAlertPreferences(store port.AlertPreferenceStore) AlertPreferences {
	return &alertPreferencesImpl{store: store}
}

func (a *alertPreferencesImpl) Get(ctx context.Context, key string, audience domain.AlertAudience) (domain.AlertSettings, error) {
	if err := domain.ValidateAlerts(audience, nil, nil); err != nil {
		return domain.AlertSettings{}, fmt.Errorf("alert preferences: %w", err)
	}
	stored, quiet, err := a.store.AlertPreferences(ctx, key)
	if err != nil {
		return domain.AlertSettings{}, fmt.Errorf("alert preferences: %w", err)
	}
	return domain.AlertSettings{Audience: audience, Preferences: domain.ResolveAlerts(audience, stored), Quiet: quiet}, nil
}

func (a *alertPreferencesImpl) Set(ctx context.Context, key string, audience domain.AlertAudience,
	prefs []domain.AlertPreference, quiet *domain.PersonalQuietHours) (domain.AlertSettings, error) {
	if err := domain.ValidateAlerts(audience, prefs, quiet); err != nil {
		return domain.AlertSettings{}, fmt.Errorf("set alert preferences: %w", err)
	}
	if err := a.store.SetAlertPreferences(ctx, key, prefs, quiet); err != nil {
		return domain.AlertSettings{}, fmt.Errorf("set alert preferences: %w", err)
	}
	return a.Get(ctx, key, audience)
}
