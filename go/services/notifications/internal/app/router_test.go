package app_test

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

func smsRouter(breakers domain.Breakers, timeout time.Duration, providers ...port.SmsSender) app.Router {
	return app.NewRouter(app.Routes{SMS: map[string][]port.SmsSender{"default": providers}}, breakers, timeout, quietLog())
}

func sms() domain.Outbound {
	return domain.Outbound{Channel: domain.ChannelSMS, Market: "us", SMS: domain.SmsMessage{To: "+15551234567", Body: "hi"}}
}

func TestRouterFailsOverOnRetryable(t *testing.T) {
	twilio := &fakeSms{name: "twilio", errs: []error{domain.RetryableError("twilio", 503, "", nil)}}
	vonage := &fakeSms{name: "vonage"}
	r := smsRouter(domain.NewBreakers(domain.BreakerSettings{}), time.Second, twilio, vonage)
	rec, err := r.Send(context.Background(), sms())
	if err != nil {
		t.Fatal(err)
	}
	if rec.Provider != "vonage" || twilio.Calls() != 1 || vonage.Calls() != 1 {
		t.Fatalf("want vonage after twilio failure, got %+v", rec)
	}
}

func TestRouterStopsOnPermanent(t *testing.T) {
	twilio := &fakeSms{name: "twilio", errs: []error{domain.PermanentError("twilio", 400, "21211", nil)}}
	vonage := &fakeSms{name: "vonage"}
	r := smsRouter(domain.NewBreakers(domain.BreakerSettings{}), time.Second, twilio, vonage)
	_, err := r.Send(context.Background(), sms())
	if err == nil || domain.IsRetryable(err) {
		t.Fatalf("want permanent error, got %v", err)
	}
	if vonage.Calls() != 0 {
		t.Fatal("a permanent error (bad number) must not fail over")
	}
}

func TestRouterTimeoutFailsOver(t *testing.T) {
	slow := &fakeSms{name: "twilio", delay: time.Second}
	fast := &fakeSms{name: "aws"}
	r := smsRouter(domain.NewBreakers(domain.BreakerSettings{}), 50*time.Millisecond, slow, fast)
	start := time.Now()
	rec, err := r.Send(context.Background(), sms())
	if err != nil || rec.Provider != "aws" {
		t.Fatalf("got %+v %v", rec, err)
	}
	if time.Since(start) > 500*time.Millisecond {
		t.Fatal("per-send timeout not applied")
	}
}

func TestRouterBreakerSkipsOpenProvider(t *testing.T) {
	fail := domain.RetryableError("twilio", 500, "", nil)
	twilio := &fakeSms{name: "twilio", errs: []error{fail, fail, fail, fail}}
	vonage := &fakeSms{name: "vonage"}
	breakers := domain.NewBreakers(domain.BreakerSettings{FailureThreshold: 2, CoolDown: time.Hour})
	r := smsRouter(breakers, time.Second, twilio, vonage)
	for range 4 {
		if _, err := r.Send(context.Background(), sms()); err != nil {
			t.Fatal(err)
		}
	}
	if twilio.Calls() != 2 {
		t.Fatalf("open breaker should stop calls to twilio after 2 failures, got %d calls", twilio.Calls())
	}
	if breakers.State("twilio") != domain.BreakerOpen || vonage.Calls() != 4 {
		t.Fatalf("state %s vonage calls %d", breakers.State("twilio"), vonage.Calls())
	}
}

func TestRouterAllFailJoinsErrors(t *testing.T) {
	a := &fakeSms{name: "twilio", errs: []error{domain.RetryableError("twilio", 503, "", nil)}}
	b := &fakeSms{name: "vonage", errs: []error{domain.RetryableError("vonage", 0, "1", errors.New("throttled"))}}
	r := smsRouter(domain.NewBreakers(domain.BreakerSettings{}), time.Second, a, b)
	_, err := r.Send(context.Background(), sms())
	var pe *domain.ProviderError
	if !errors.As(err, &pe) || !domain.IsRetryable(err) {
		t.Fatalf("want joined retryable provider errors, got %v", err)
	}
	if got := err.Error(); !contains(got, "twilio") || !contains(got, "vonage") {
		t.Fatalf("joined error should name every provider: %s", got)
	}
}

func TestRouterNoProvider(t *testing.T) {
	r := app.NewRouter(app.Routes{}, domain.NewBreakers(domain.BreakerSettings{}), time.Second, quietLog())
	if _, err := r.Send(context.Background(), sms()); !errors.Is(err, domain.ErrNoProvider) {
		t.Fatal(err)
	}
}

func TestRouterMarketAndPushPlatform(t *testing.T) {
	usFcm := &fakePush{name: "fcm", platform: domain.PushFCM}
	usApns := &fakePush{name: "apns", platform: domain.PushAPNs}
	defFcm := &fakePush{name: "fcm-default", platform: domain.PushFCM}
	r := app.NewRouter(app.Routes{Push: map[string][]port.PushSender{"us": {usFcm, usApns}, "default": {defFcm}}},
		domain.NewBreakers(domain.BreakerSettings{}), time.Second, quietLog())
	out := domain.Outbound{Channel: domain.ChannelPush, Market: "us", Push: domain.PushMessage{Token: domain.PushToken{Token: "t", Platform: domain.PushAPNs}}}
	rec, err := r.Send(context.Background(), out)
	if err != nil || rec.Provider != "apns" || usFcm.calls != 0 {
		t.Fatalf("apns token must go to apns: %+v %v", rec, err)
	}
	out.Market, out.Push.Token.Platform = "gh", domain.PushFCM
	if rec, _ := r.Send(context.Background(), out); rec.Provider != "fcm-default" {
		t.Fatalf("unknown market must use default route, got %s", rec.Provider)
	}
	if got := r.Providers(domain.ChannelPush, "us"); len(got) != 2 || got[0] != "fcm" {
		t.Fatal(got)
	}
}

func contains(s, sub string) bool { return strings.Contains(s, sub) }
