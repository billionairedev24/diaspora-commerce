package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestDefaultsFollowThePolicyTable(t *testing.T) {
	const day = 24 * time.Hour
	want := map[domain.Audience]domain.SessionPolicy{
		domain.AudienceStaff:       {Idle: 15 * time.Minute, Absolute: 8 * time.Hour, Warn: time.Minute},
		domain.AudienceSeller:      {Idle: 30 * time.Minute, Absolute: 12 * time.Hour, Warn: time.Minute},
		domain.AudienceCustomerWeb: {Idle: 30 * day, Absolute: 90 * day},
		domain.AudienceCustomerApp: {Idle: 30 * day, Absolute: 90 * day},
		domain.AudienceDriver:      {Idle: 14 * time.Hour, Absolute: 14 * time.Hour},
	}
	for a, p := range want {
		if got := domain.DefaultSessionPolicy(a); got != p || !got.Valid() {
			t.Fatalf("%s = %+v", a, got)
		}
	}
}

func TestEndedByAndExpiresAt(t *testing.T) {
	p := domain.SessionPolicy{Idle: 15 * time.Minute, Absolute: time.Hour}
	now := time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)
	s := &domain.Session{CreatedAt: now.Add(-50 * time.Minute), LastActivityAt: now.Add(-10 * time.Minute)}
	if got := s.EndedBy(p, now); got != "" {
		t.Fatalf("alive session ended: %s", got)
	}
	if got := s.ExpiresAt(p, now); !got.Equal(now.Add(5 * time.Minute)) {
		t.Fatalf("idle expiry = %v", got)
	}
	if got := s.EndedBy(p, now.Add(6*time.Minute)); got != domain.CodeSessionIdleTimeout {
		t.Fatalf("idle: %s", got)
	}
	s.LastActivityAt = now.Add(-time.Minute)
	if got := s.ExpiresAt(p, now); !got.Equal(now.Add(10 * time.Minute)) {
		t.Fatalf("absolute expiry = %v", got)
	}
	if got := s.EndedBy(p, now.Add(11*time.Minute)); got != domain.CodeSessionAbsoluteTimeout {
		t.Fatalf("absolute: %s", got)
	}
	// Both over: the session length is reported.
	s.LastActivityAt = now.Add(-2 * time.Hour)
	if got := s.EndedBy(p, now.Add(time.Hour)); got != domain.CodeSessionAbsoluteTimeout {
		t.Fatalf("both: %s", got)
	}
}

func TestAudienceForOriginAndRoles(t *testing.T) {
	apps := map[string]string{"storefront": "https://shop.example.test/", "seller-center": "https://sell.example.test", "staff-console": "https://staff.example.test"}
	cases := map[string]domain.Audience{"https://shop.example.test": domain.AudienceCustomerWeb, "https://SELL.example.test/": domain.AudienceSeller,
		"https://staff.example.test": domain.AudienceStaff}
	for origin, want := range cases {
		if got, ok := domain.AudienceForOrigin(origin, apps); !ok || got != want {
			t.Fatalf("%s = %q %v", origin, got, ok)
		}
	}
	if _, ok := domain.AudienceForOrigin("https://evil.example.test", apps); ok {
		t.Fatal("unknown origin must not match")
	}
	if _, ok := domain.AudienceForOrigin("", apps); ok {
		t.Fatal("empty origin must not match")
	}
	if domain.AudienceForRoles([]string{"platform.super_admin"}) != domain.AudienceStaff ||
		domain.AudienceForRoles([]string{"seller.owner"}) != domain.AudienceSeller ||
		domain.AudienceForRoles(nil) != domain.AudienceCustomerWeb {
		t.Fatal("roles fallback")
	}
}

func TestShorterAndWriteInterval(t *testing.T) {
	a := domain.SessionPolicy{Idle: time.Hour, Absolute: 5 * time.Hour, Warn: time.Minute}
	b := domain.SessionPolicy{Idle: 30 * time.Minute, Absolute: 9 * time.Hour, Warn: 2 * time.Minute}
	if got := a.Shorter(b); got != (domain.SessionPolicy{Idle: 30 * time.Minute, Absolute: 5 * time.Hour, Warn: time.Minute}) {
		t.Fatalf("shorter = %+v", got)
	}
	if got := (domain.SessionPolicy{Idle: 20 * time.Second, Absolute: time.Hour}).ActivityWriteInterval(); got != 5*time.Second {
		t.Fatalf("a short idle writes more often, got %s", got)
	}
	if got := a.ActivityWriteInterval(); got != 30*time.Second {
		t.Fatalf("got %s", got)
	}
}

func TestParseAudience(t *testing.T) {
	if a, ok := domain.ParseAudience(" Staff "); !ok || a != domain.AudienceStaff {
		t.Fatal("staff")
	}
	if _, ok := domain.ParseAudience("admin"); ok {
		t.Fatal("unknown audience accepted")
	}
}
