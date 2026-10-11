package domain_test

import (
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestTopicForTemplate(t *testing.T) {
	cases := map[string]domain.AlertTopic{
		"payout.failed":                  domain.TopicSellerPayouts,
		"seller.payouts.paused":          domain.TopicSellerPayouts,
		"seller.compliance.reminder":     domain.TopicSellerCompliance,
		"listing.taken_down":             domain.TopicSellerCompliance,
		"claim.seller_response_reminder": domain.TopicSellerClaims,
		"seller.order.new":               domain.TopicSellerOrders,
		"staff.escalation.opened":        domain.TopicStaffEscalations,
	}
	for key, want := range cases {
		d, ok := domain.TopicForTemplate(key)
		if !ok || d.Topic != want {
			t.Errorf("%s: got %q %v, want %q", key, d.Topic, ok, want)
		}
	}
	for _, key := range []string{"order.confirmed", "identity.verification", "claim.decided", "payout"} {
		if d, ok := domain.TopicForTemplate(key); ok {
			t.Errorf("%s is a customer or security template, got topic %q", key, d.Topic)
		}
	}
}

func TestResolveAlertsDefaultsOnAndKeepsLockedChannelsOn(t *testing.T) {
	got := domain.ResolveAlerts(domain.AudienceSeller, []domain.AlertPreference{
		{Topic: domain.TopicSellerPayouts, Channel: domain.ChannelSMS, Enabled: false},
		{Topic: domain.TopicSellerCompliance, Channel: domain.ChannelEmail, Enabled: false}, // locked: ignored
	})
	if len(got) != 10 {
		t.Fatalf("5 seller topics × 2 channels, got %d", len(got))
	}
	find := func(topic domain.AlertTopic, ch domain.Channel) domain.AlertPreference {
		for _, p := range got {
			if p.Topic == topic && p.Channel == ch {
				return p
			}
		}
		t.Fatalf("missing %s/%s", topic, ch)
		return domain.AlertPreference{}
	}
	if find(domain.TopicSellerPayouts, domain.ChannelSMS).Enabled {
		t.Error("payouts by SMS should be off")
	}
	if p := find(domain.TopicSellerCompliance, domain.ChannelEmail); !p.Enabled || !p.Locked {
		t.Errorf("compliance email must stay on and locked, got %+v", p)
	}
	if !find(domain.TopicSellerOrders, domain.ChannelEmail).Enabled {
		t.Error("topics default on")
	}
	if staff := domain.ResolveAlerts(domain.AudienceStaff, nil); len(staff) != 6 {
		t.Errorf("3 staff topics × 2 channels, got %d", len(staff))
	}
}

func TestValidateAlerts(t *testing.T) {
	ok := []domain.AlertPreference{{Topic: domain.TopicSellerOrders, Channel: domain.ChannelSMS, Enabled: false}}
	if err := domain.ValidateAlerts(domain.AudienceSeller, ok, &domain.PersonalQuietHours{Enabled: true, StartMinute: 1320, EndMinute: 420}); err != nil {
		t.Fatalf("valid change refused: %v", err)
	}
	bad := map[string]struct {
		audience domain.AlertAudience
		prefs    []domain.AlertPreference
		quiet    *domain.PersonalQuietHours
	}{
		"unknown audience":     {"customer", nil, nil},
		"other audience topic": {domain.AudienceStaff, ok, nil},
		"push has no switches": {domain.AudienceSeller, []domain.AlertPreference{{Topic: domain.TopicSellerOrders, Channel: domain.ChannelPush}}, nil},
		"locked turned off":    {domain.AudienceSeller, []domain.AlertPreference{{Topic: domain.TopicSellerCompliance, Channel: domain.ChannelEmail, Enabled: false}}, nil},
		"empty quiet window":   {domain.AudienceSeller, nil, &domain.PersonalQuietHours{Enabled: true, StartMinute: 60, EndMinute: 60}},
		"minute out of range":  {domain.AudienceSeller, nil, &domain.PersonalQuietHours{StartMinute: 1440}},
	}
	for name, c := range bad {
		if err := domain.ValidateAlerts(c.audience, c.prefs, c.quiet); !errors.Is(err, domain.ErrInvalidRequest) {
			t.Errorf("%s: want ErrInvalidRequest, got %v", name, err)
		}
	}
}

func TestDecideMutedTopicsAndPersonalQuietHours(t *testing.T) {
	chicago := mustLoc(t, "America/Chicago")
	night := time.Date(2026, 1, 15, 4, 0, 0, 0, time.UTC) // 22:00 in Chicago
	quiet := domain.QuietHours{Start: 21 * time.Hour, End: 7 * time.Hour}

	muted := domain.Decide(domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryTransactional,
		Topic: domain.TopicSellerPayouts, TopicOff: true, Now: night, Location: chicago})
	if muted.Verdict != domain.VerdictDeny {
		t.Errorf("muted topic: got %v", muted)
	}
	otp := domain.Decide(domain.ConsentFacts{Channel: domain.ChannelSMS, Category: domain.CategorySecurity,
		TopicOff: true, PersonalQuiet: &quiet, Now: night, Location: chicago})
	if otp.Verdict != domain.VerdictAllow {
		t.Errorf("security codes ignore topics and quiet hours: got %v", otp)
	}
	held := domain.Decide(domain.ConsentFacts{Channel: domain.ChannelSMS, Category: domain.CategoryTransactional,
		SmsConsent: optedIn(), PersonalQuiet: &quiet, Now: night, Location: chicago})
	if held.Verdict != domain.VerdictDefer || held.Until.In(chicago).Hour() != 7 {
		t.Errorf("SMS held until 07:00 local: got %+v", held)
	}
	email := domain.Decide(domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryTransactional,
		PersonalQuiet: &quiet, Now: night, Location: chicago})
	if email.Verdict != domain.VerdictAllow {
		t.Errorf("email is not held by personal quiet hours: got %v", email)
	}
}

func TestClockMinutes(t *testing.T) {
	m, err := domain.ParseClockMinutes("21:30")
	if err != nil || m != 1290 || domain.FormatClockMinutes(m) != "21:30" {
		t.Fatalf("got %d %v", m, err)
	}
	if _, err := domain.ParseClockMinutes("25:00"); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Errorf("want ErrInvalidRequest, got %v", err)
	}
	if w, ok := domain.DefaultPersonalQuietHours.Window(); ok {
		t.Errorf("default quiet hours are off, got %v", w)
	}
}
