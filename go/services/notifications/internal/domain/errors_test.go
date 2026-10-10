package domain_test

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestIsRetryable(t *testing.T) {
	perm := domain.PermanentError("twilio", 400, "21211", nil)
	retry := domain.RetryableError("vonage", 503, "", nil)
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"permanent", perm, false},
		{"retryable", retry, true},
		{"joined all permanent", errors.Join(perm, domain.PermanentError("aws", 0, "ValidationException", nil)), false},
		{"joined mixed", errors.Join(perm, retry), true},
		{"wrapped join", fmt.Errorf("send: %w", errors.Join(perm, retry)), true},
		{"no provider", fmt.Errorf("x: %w", domain.ErrNoProvider), true},
		{"timeout", context.DeadlineExceeded, true},
	}
	for _, tc := range cases {
		if got := domain.IsRetryable(tc.err); got != tc.want {
			t.Errorf("%s: got %v", tc.name, got)
		}
	}
}

func TestRetryDelay(t *testing.T) {
	p := domain.RetryPolicy{Base: 10 * time.Second, Max: time.Minute, MaxAttempts: 3}
	for n, want := range map[int]time.Duration{1: 10 * time.Second, 2: 20 * time.Second, 3: 40 * time.Second, 6: time.Minute} {
		d := p.Delay(n)
		if d < want*8/10 || d > want*12/10 {
			t.Errorf("attempt %d: %s not within 20%% of %s", n, d, want)
		}
	}
	if p.Exhausted(2) || !p.Exhausted(3) {
		t.Error("exhausted")
	}
}

func TestPushAddressAndPhone(t *testing.T) {
	tok := domain.PushToken{Token: "abc:def", Platform: domain.PushFCM}
	back, err := domain.ParsePushAddress(tok.Address())
	if err != nil || back != tok {
		t.Fatalf("round trip: %v %v", back, err)
	}
	if _, err := domain.ParsePushAddress("web:abc"); err == nil {
		t.Fatal("unknown platform accepted")
	}
	if got := domain.NormalizePhone("+1 (555) 123-4567"); got != "+15551234567" {
		t.Fatal(got)
	}
	if got := domain.LocaleFallbacks("fr-CA"); len(got) != 3 || got[0] != "fr-CA" || got[1] != "fr" || got[2] != "en" {
		t.Fatal(got)
	}
}
