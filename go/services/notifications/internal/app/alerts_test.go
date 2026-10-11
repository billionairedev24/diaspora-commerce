package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

type memAlertStore struct {
	prefs map[string][]domain.AlertPreference
	quiet map[string]domain.PersonalQuietHours
}

func (m *memAlertStore) AlertPreferences(_ context.Context, key string) ([]domain.AlertPreference, domain.PersonalQuietHours, error) {
	q, ok := m.quiet[key]
	if !ok {
		q = domain.DefaultPersonalQuietHours
	}
	return m.prefs[key], q, nil
}

func (m *memAlertStore) SetAlertPreferences(_ context.Context, key string, prefs []domain.AlertPreference, quiet *domain.PersonalQuietHours) error {
	m.prefs[key] = append(m.prefs[key], prefs...)
	if quiet != nil {
		m.quiet[key] = *quiet
	}
	return nil
}

func TestAlertPreferencesSaveAndResolve(t *testing.T) {
	store := &memAlertStore{prefs: map[string][]domain.AlertPreference{}, quiet: map[string]domain.PersonalQuietHours{}}
	uc := app.NewAlertPreferences(store)
	ctx := context.Background()

	got, err := uc.Set(ctx, "u1", domain.AudienceStaff, []domain.AlertPreference{{Topic: domain.TopicStaffAssigned, Channel: domain.ChannelSMS, Enabled: false}},
		&domain.PersonalQuietHours{Enabled: true, StartMinute: 22 * 60, EndMinute: 6 * 60})
	if err != nil {
		t.Fatal(err)
	}
	if !got.Quiet.Enabled || got.Quiet.StartMinute != 1320 {
		t.Errorf("quiet hours not saved: %+v", got.Quiet)
	}
	for _, p := range got.Preferences {
		if p.Topic == domain.TopicStaffAssigned && p.Channel == domain.ChannelSMS && p.Enabled {
			t.Error("assigned-queue SMS should be off")
		}
		if p.Topic == domain.TopicStaffEscalations && p.Channel == domain.ChannelEmail && !p.Locked {
			t.Error("escalation email is locked on")
		}
	}
	if _, err := uc.Set(ctx, "u1", domain.AudienceStaff, []domain.AlertPreference{{Topic: domain.TopicStaffEscalations, Channel: domain.ChannelEmail}}, nil); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Errorf("locked switch: want ErrInvalidRequest, got %v", err)
	}
	if _, err := uc.Get(ctx, "u1", "driver"); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Errorf("unknown audience: want ErrInvalidRequest, got %v", err)
	}
}
