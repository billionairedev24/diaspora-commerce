package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func TestBreakerLifecycle(t *testing.T) {
	c := &clock{t: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}
	b := domain.NewBreakersWithClock(domain.BreakerSettings{FailureThreshold: 3, CoolDown: 30 * time.Second, HalfOpenProbes: 1}, c.now)

	for i := range 2 {
		if !b.Allow("twilio") {
			t.Fatalf("closed breaker rejected call %d", i)
		}
		b.Failure("twilio")
	}
	if b.State("twilio") != domain.BreakerClosed {
		t.Fatal("breaker opened before threshold")
	}
	b.Failure("twilio")
	if b.State("twilio") != domain.BreakerOpen || b.Allow("twilio") {
		t.Fatal("breaker should be open after 3 consecutive failures")
	}
	if !b.Allow("vonage") {
		t.Fatal("breakers must be per provider")
	}

	c.t = c.t.Add(29 * time.Second)
	if b.Allow("twilio") {
		t.Fatal("open breaker admitted a call during cool-down")
	}
	c.t = c.t.Add(time.Second)
	if b.State("twilio") != domain.BreakerHalfOpen {
		t.Fatalf("want half-open after cool-down, got %s", b.State("twilio"))
	}
	if !b.Allow("twilio") {
		t.Fatal("half-open breaker must admit one probe")
	}
	if b.Allow("twilio") {
		t.Fatal("half-open breaker admitted a second concurrent probe")
	}
	b.Failure("twilio")
	if b.State("twilio") != domain.BreakerOpen {
		t.Fatal("failed probe must re-open the breaker")
	}

	c.t = c.t.Add(30 * time.Second)
	if !b.Allow("twilio") {
		t.Fatal("probe after second cool-down")
	}
	b.Success("twilio")
	if b.State("twilio") != domain.BreakerClosed || !b.Allow("twilio") {
		t.Fatal("successful probe must close the breaker")
	}
}

func TestBreakerSuccessResetsCount(t *testing.T) {
	b := domain.NewBreakers(domain.BreakerSettings{FailureThreshold: 2})
	b.Failure("resend")
	b.Success("resend")
	b.Failure("resend")
	if b.State("resend") != domain.BreakerClosed {
		t.Fatal("failures must be consecutive to open the breaker")
	}
}
