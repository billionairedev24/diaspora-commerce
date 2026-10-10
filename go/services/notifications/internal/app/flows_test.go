package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

func TestSmsComplianceKeywords(t *testing.T) {
	consent := newFakeConsent()
	c := app.NewSmsCompliance(consent, app.ComplianceTexts{Help: "help text", Stop: "bye", Start: "welcome back", TextVersion: "v1"})
	ctx := context.Background()
	for _, word := range []string{"STOP", "end", "Cancel", "UNSUBSCRIBE", "quit"} {
		reply, err := c.HandleInbound(ctx, "+1 555 000 0001", word)
		if err != nil || reply != "bye" {
			t.Fatalf("%s: %q %v", word, reply, err)
		}
	}
	if consent.optOut["+15550000001"] != "sms-QUIT" {
		t.Fatalf("opt-out not recorded with keyword: %v", consent.optOut)
	}
	if reply, _ := c.HandleInbound(ctx, "+15550000001", "start"); reply != "welcome back" || consent.optIn["+15550000001"] != "sms-START" {
		t.Fatal("START must opt back in")
	}
	if reply, _ := c.HandleInbound(ctx, "+15550000001", "HELP"); reply != "help text" {
		t.Fatal("HELP reply")
	}
	if reply, _ := c.HandleInbound(ctx, "+15550000002", "where is my order?"); reply != "" || len(consent.optOut) != 1 {
		t.Fatal("ordinary messages must not change consent")
	}
}

func TestIngestMapsAndDeduplicates(t *testing.T) {
	store := newFakeStore()
	tpls := templates()
	tpls.items = append(tpls.items, domain.Template{Key: "identity.verification", Locale: "en", Channel: domain.ChannelSMS,
		Version: 1, Category: domain.CategorySecurity, BodyText: "code {{.code}}"})
	n := app.NewNotifier(tpls, app.NewRenderer(), store, nil)
	ing := app.NewIngest(n, nil, quietLog())
	payload, _ := json.Marshal(map[string]any{"recipient": map[string]any{"phone": "+15550001111"}, "channel": "sms", "code": "123456"})
	ev := events.Envelope{ID: "evt-1", Type: "identity.verification.requested", AggregateID: "user-9", Payload: payload}
	ctx := context.Background()
	for range 2 {
		if err := ing.Handle(ctx, ev); err != nil {
			t.Fatal(err)
		}
	}
	if len(store.enqueued) != 1 {
		t.Fatalf("redelivered event must be de-duplicated, got %d rows", len(store.enqueued))
	}
	row := store.enqueued[0]
	if row.Channel != domain.ChannelSMS || row.BodyText != "code 123456" || row.RecipientID != "user-9" || row.EventID != "evt-1" {
		t.Fatalf("%+v", row)
	}
	// Unknown types and undeliverable events are skipped without error (they must not stall the partition).
	if err := ing.Handle(ctx, events.Envelope{ID: "evt-2", Type: "order.cancelled", Payload: payload}); err != nil {
		t.Fatal(err)
	}
	if err := ing.Handle(ctx, events.Envelope{ID: "evt-3", Type: "order.placed", Payload: []byte(`{"recipient":{}}`)}); err != nil {
		t.Fatal(err)
	}
	if err := ing.Handle(ctx, events.Envelope{ID: "evt-4", Type: "order.placed", Payload: []byte(`not json`)}); err != nil {
		t.Fatal(err)
	}
	generic, _ := json.Marshal(map[string]any{"templateKey": "order.confirmed", "channels": []string{"email"},
		"recipient": map[string]any{"email": "a@b.c"}, "data": map[string]any{"n": "AT-7"}})
	if err := ing.Handle(ctx, events.Envelope{ID: "evt-5", Type: app.GenericEventType, Payload: generic}); err != nil {
		t.Fatal(err)
	}
	if len(store.enqueued) != 2 || store.enqueued[1].Subject != "Order AT-7 confirmed" {
		t.Fatalf("generic event: %+v", store.enqueued)
	}
}

func queued(id, addr string, attempts int) domain.Notification {
	return domain.Notification{ID: id, Channel: domain.ChannelSMS, Category: domain.CategoryTransactional, Address: addr,
		BodyText: "hi", Status: domain.StatusQueued, Attempts: attempts, Market: "us"}
}

func TestDispatcherOutcomes(t *testing.T) {
	store := newFakeStore()
	until := time.Now().Add(time.Hour)
	checker := fakeChecker{decision: map[string]domain.Decision{
		"+1000": {Verdict: domain.VerdictDeny, Reason: "opted out"},
		"+2000": {Verdict: domain.VerdictDefer, Until: until},
	}}
	retryable := domain.RetryableError("twilio", 503, "", nil)
	permanent := domain.PermanentError("twilio", 400, "21211", nil)
	// Calls in claim order: n3 ok, n4 retryable, n5 permanent, n6 retryable (exhausted).
	twilio := &fakeSms{name: "twilio", errs: []error{nil, retryable, permanent, retryable}}
	d := app.NewDispatcher(app.DispatcherConfig{Concurrency: 1, Retry: domain.RetryPolicy{Base: time.Second, Max: time.Minute, MaxAttempts: 3}},
		store, checker, smsRouter(domain.NewBreakers(domain.BreakerSettings{FailureThreshold: 100}), time.Second, twilio), quietLog())
	store.claim = []domain.Notification{queued("n1", "+1000", 0), queued("n2", "+2000", 0), queued("n3", "+3000", 0),
		queued("n4", "+4000", 0), queued("n5", "+5000", 0), queued("n6", "+6000", 2)}
	n, err := d.RunOnce(context.Background())
	if err != nil || n != 6 {
		t.Fatalf("%d %v", n, err)
	}
	if store.final["n1"] != domain.StatusSuppressed || store.dead["n1"] {
		t.Error("denied consent must suppress without dead-lettering")
	}
	if !store.resched["n2"].Equal(until) {
		t.Error("quiet hours must reschedule to the end of the window")
	}
	if store.sent["n3"].Provider != "twilio" {
		t.Error("n3 should be sent")
	}
	if store.retries["n4"] != 1 {
		t.Error("retryable failure must re-queue with attempts=1")
	}
	if store.final["n5"] != domain.StatusFailed || !store.dead["n5"] {
		t.Error("permanent failure must dead-letter")
	}
	if store.final["n6"] != domain.StatusFailed || !store.dead["n6"] {
		t.Error("exhausted retries must dead-letter")
	}
}

func TestReceiptsSuppressOnBounce(t *testing.T) {
	consent := newFakeConsent()
	store := &receiptStore{fakeStore: newFakeStore()}
	r := app.NewReceipts(store, consent, quietLog())
	n, err := r.Apply(context.Background(), []domain.DeliveryEvent{
		{Provider: "resend", ProviderMessageID: "m1", Status: domain.StatusDelivered, Address: "a@b.c"},
		{Provider: "resend", ProviderMessageID: "m2", Status: domain.StatusBounced, Address: "Bad@B.c"},
		{Provider: "sendgrid", ProviderMessageID: "m3", Status: domain.StatusComplained, Address: "angry@b.c"},
	})
	if err != nil || n != 3 {
		t.Fatal(n, err)
	}
	if consent.suppressed["bad@b.c"] != "bounced" || consent.suppressed["angry@b.c"] != "complained" || len(consent.suppressed) != 2 {
		t.Fatal(consent.suppressed)
	}
}

type receiptStore struct{ *fakeStore }

func (receiptStore) ApplyDeliveryEvent(context.Context, domain.DeliveryEvent) (bool, error) {
	return true, nil
}

type fakeContacts struct{ email, phone string }

func (f fakeContacts) Resolve(context.Context) (string, string, error) { return f.email, f.phone, nil }

type captureEmail struct{ got []domain.EmailMessage }

func (c *captureEmail) Name() string { return "capture" }
func (c *captureEmail) Send(_ context.Context, m domain.EmailMessage) (domain.Receipt, error) {
	c.got = append(c.got, m)
	return domain.Receipt{Provider: "capture"}, nil
}

func TestTemplateTestSendGoesOnlyToEditor(t *testing.T) {
	email := &captureEmail{}
	router := app.NewRouter(app.Routes{Email: map[string][]port.EmailSender{"default": {email}}},
		domain.NewBreakers(domain.BreakerSettings{}), time.Second, quietLog())
	svc := app.NewTemplates(templates(), app.NewRenderer(), router, fakeContacts{email: "Editor@AfroTransact.com"}, "AT <no-reply@at.com>")
	res, err := svc.TestSend(context.Background(), app.PreviewRequest{Key: "order.confirmed", Channel: domain.ChannelEmail, Data: map[string]any{"n": "AT-1"}})
	if err != nil {
		t.Fatal(err)
	}
	if res.To != "editor@afrotransact.com" || len(email.got) != 1 || email.got[0].To != "editor@afrotransact.com" ||
		!strings.HasPrefix(email.got[0].Subject, "[TEST] ") {
		t.Fatalf("%+v %+v", res, email.got)
	}
	_, err = svc.TestSend(context.Background(), app.PreviewRequest{Key: "order.confirmed", Channel: domain.ChannelSMS, Data: map[string]any{"n": "1"}})
	if !errors.Is(err, app.ErrNoOwnContact) && !errors.Is(err, domain.ErrTemplateNotFound) {
		t.Fatalf("no phone on record: %v", err)
	}
	prev, err := svc.Preview(context.Background(), app.PreviewRequest{Key: "order.confirmed", Channel: domain.ChannelEmail, Version: 1, Data: map[string]any{"n": "9"}})
	if err != nil || prev.Subject != "Order 9" || prev.Version != 1 {
		t.Fatalf("preview of a specific version: %+v %v", prev, err)
	}
}
