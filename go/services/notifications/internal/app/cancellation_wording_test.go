package app_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// seededRepo serves the real seeded templates (the latest version of each key, locale and channel), so a test sees the
// text a customer gets, not a placeholder.
type seededRepo struct{ all []domain.Template }

func (r seededRepo) Find(_ context.Context, key string, locales []string, ch domain.Channel, _ int) (domain.Template, error) {
	for _, loc := range locales {
		for _, t := range r.all {
			if t.Key == key && t.Locale == loc && t.Channel == ch {
				return t, nil
			}
		}
	}
	return domain.Template{}, errors.New("no template " + key + "/" + string(ch))
}
func (seededRepo) Create(context.Context, domain.Template) (domain.Template, error) {
	return domain.Template{}, nil
}
func (seededRepo) List(context.Context, string) ([]domain.Template, error) { return nil, nil }

func cancellationIngest(t *testing.T, store *fakeStore) app.Ingest {
	t.Helper()
	res := &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}
	n := app.NewNotifier(seededRepo{all: seededTemplates(t)}, app.NewRenderer(), store, nil)
	return app.NewIngest(n, app.IngestConfig{Resolver: res, Links: links}, quietLog())
}

func bodies(store *fakeStore) map[domain.Channel]string {
	out := map[domain.Channel]string{}
	for _, n := range store.enqueued {
		out[n.Channel] = n.BodyText
	}
	return out
}

// S262 / ADR 0005 §1: a cancelled pre-order window refunds every order in full, delivery fee included, and each
// customer is told so by email, text message and push.
func TestCancelledWindowTellsCustomersTheyAreRefundedInFullByEmailSmsAndPush(t *testing.T) {
	store := newFakeStore()
	handle(t, cancellationIngest(t, store), "orders.shipment.cancelled", map[string]any{"orderId": "o-1",
		"orderNumber": "AT-2026-7KQ3MD", "customerId": "cust-1", "shipmentId": "s-1", "currency": "USD", "totalMinor": 2799,
		"captured": true, "actorType": "SYSTEM", "fulfilmentType": "PREORDER", "windowId": "w-1",
		"reason": "Pre-order window cancelled: low demand"})

	got := bodies(store)
	if len(got) != 3 {
		t.Fatalf("channels = %v", got)
	}
	for _, ch := range []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush} {
		body, ok := got[ch]
		if !ok {
			t.Fatalf("no %s", ch)
		}
		if !strings.Contains(strings.ToLower(body), "refund") || !strings.Contains(body, "delivery fee included") || !strings.Contains(body, "$27.99") {
			t.Errorf("%s does not promise the full refund, delivery fee included: %q", ch, body)
		}
		if strings.Contains(body, "low demand") {
			t.Errorf("%s shows the staff reason: %q", ch, body)
		}
	}
	if sms := got[domain.ChannelSMS]; sms != "AfroTransact: we cancelled your pre-order AT-2026-7KQ3MD. "+
		"Refunded in full, delivery fee included: $27.99. Reply STOP to opt out" ||
		domain.CountSmsSegments(sms).Encoding != domain.EncodingGSM7 || domain.CountSmsSegments(sms).Segments != 1 {
		t.Errorf("sms = %q (%+v)", sms, domain.CountSmsSegments(sms))
	}
	if !strings.Contains(got[domain.ChannelEmail], "We're refunding the full $27.99, delivery fee included") {
		t.Errorf("email = %q", got[domain.ChannelEmail])
	}
}

// A window cancelled before the card was charged: nothing to refund, the hold is released; still said by text.
func TestCancelledWindowBeforeCaptureSaysTheCustomerWasNotCharged(t *testing.T) {
	store := newFakeStore()
	handle(t, cancellationIngest(t, store), "orders.shipment.cancelled", map[string]any{"orderId": "o-1",
		"orderNumber": "AT-2026-7KQ3MD", "customerId": "cust-1", "shipmentId": "s-1", "currency": "USD", "totalMinor": 2799,
		"captured": false, "actorType": "SYSTEM", "fulfilmentType": "PREORDER", "windowId": "w-1"})

	got := bodies(store)
	for _, ch := range []domain.Channel{domain.ChannelEmail, domain.ChannelSMS} {
		if !strings.Contains(got[ch], "not charged") && !strings.Contains(got[ch], "haven't been charged") {
			t.Errorf("%s = %q", ch, got[ch])
		}
	}
}

// A customer who cancels their own pre-order is not texted: they just did it.
func TestACustomersOwnPreorderCancellationIsNotTexted(t *testing.T) {
	store := newFakeStore()
	handle(t, cancellationIngest(t, store), "orders.shipment.cancelled", map[string]any{"orderId": "o-1",
		"orderNumber": "AT-2026-7KQ3MD", "customerId": "cust-1", "shipmentId": "s-1", "currency": "USD", "totalMinor": 2799,
		"captured": true, "actorType": "CUSTOMER", "fulfilmentType": "PREORDER", "windowId": "w-1"})

	got := bodies(store)
	if _, texted := got[domain.ChannelSMS]; texted || len(got) != 2 {
		t.Fatalf("channels = %v", got)
	}
}

// A cancelled delivery run says the same to the customers on it, by email, text message and push.
func TestCancelledRunTellsCustomersTheyAreRefundedInFullByEmailSmsAndPush(t *testing.T) {
	store := newFakeStore()
	ev := app.SampleEvents(links)["delivery-runs.run.cancelled"][0]
	handle(t, cancellationIngest(t, store), "delivery-runs.run.cancelled", ev)

	got := bodies(store)
	if len(got) != 3 {
		t.Fatalf("channels = %v", got)
	}
	for _, ch := range []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush} {
		if !strings.Contains(got[ch], "refunded in full, delivery fee included") {
			t.Errorf("%s does not promise the full refund, delivery fee included: %q", ch, got[ch])
		}
	}
}
