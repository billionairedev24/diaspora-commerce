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

// memAlerts is an in-memory StockAlertStore with the same claim semantics as Postgres.
type memAlerts struct {
	rows    []domain.StockAlert
	eventOf map[string]string
}

func (m *memAlerts) Subscribe(_ context.Context, a domain.StockAlert) (domain.StockAlert, bool, error) {
	for i, r := range m.rows {
		if r.UserID == a.UserID && r.Atin == a.Atin {
			if r.Status == domain.StockAlertActive {
				return r, false, nil
			}
			m.rows[i].Status, m.rows[i].NotifiedAt = domain.StockAlertActive, nil
			return m.rows[i], true, nil
		}
	}
	a.ID, a.Status = "sa-"+a.UserID+"-"+a.Atin, domain.StockAlertActive
	m.rows = append(m.rows, a)
	return a, true, nil
}

func (m *memAlerts) Unsubscribe(_ context.Context, userID, atin string) error {
	for i, r := range m.rows {
		if r.UserID == userID && r.Atin == atin {
			m.rows = append(m.rows[:i], m.rows[i+1:]...)
			return nil
		}
	}
	return domain.ErrNotFound
}

func (m *memAlerts) ListByUser(_ context.Context, userID string) ([]domain.StockAlert, error) {
	var out []domain.StockAlert
	for _, r := range m.rows {
		if r.UserID == userID {
			out = append(out, r)
		}
	}
	return out, nil
}

func (m *memAlerts) ClaimForRestock(_ context.Context, atin, eventID string, limit int) ([]domain.StockAlert, error) {
	var out []domain.StockAlert
	now := time.Now()
	for i, r := range m.rows {
		if r.Atin != atin || len(out) >= limit {
			continue
		}
		if r.Status == domain.StockAlertActive || m.eventOf[r.ID] == eventID {
			m.rows[i].Status, m.rows[i].NotifiedAt = domain.StockAlertNotified, &now
			m.eventOf[r.ID] = eventID
			out = append(out, m.rows[i])
		}
	}
	return out, nil
}

var _ port.StockAlertStore = (*memAlerts)(nil)

func availabilityEvent(id string, available int) events.Envelope {
	const sku = "ATGARI0001"
	raw, _ := json.Marshal(map[string]any{"sku": sku, "nodeId": "HOU-FC", "ownerOrg": "retail", "available": available,
		"onHand": available, "reserved": 0, "version": 3})
	return events.Envelope{ID: id, Type: app.AvailabilityChangedType, AggregateID: sku, Payload: raw}
}

func TestStockAlertsSubscribeOnceAndSendOnce(t *testing.T) {
	ctx := context.Background()
	store := newFakeStore()
	alerts := &memAlerts{eventOf: map[string]string{}}
	res := &fakeResolver{contacts: map[string]port.Contact{"shopper-1": ada()}}
	n := app.NewNotifier(echoTemplates{fields: []string{"firstName", "productTitle", "productUrl"}}, app.NewRenderer(), store, nil)
	sa := app.NewStockAlerts(alerts, n, res, links, quietLog())
	ing := app.NewIngest(n, app.IngestConfig{Resolver: res, Links: links, StockAlerts: sa}, quietLog())

	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "nope"}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatalf("bad atin: %v", err)
	}
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{Atin: "ATGARI0001"}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatalf("no user: %v", err)
	}
	a, created, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "atgari0001", ProductTitle: "Gari 1kg"})
	if err != nil || !created || a.Atin != "ATGARI0001" || a.Market != "us" || a.Locale != "en" {
		t.Fatalf("%+v %v %v", a, created, err)
	}
	if _, created, _ := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001"}); created {
		t.Fatal("a second subscription for the same variant must not create another alert")
	}
	// A guest-style explicit email for another shopper unknown to identity.
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-2", Atin: "ATGARI0001", Email: "Two@Example.com"}); err != nil {
		t.Fatal(err)
	}

	// No units: nothing is sent.
	if err := ing.Handle(ctx, availabilityEvent("inv-1", 0)); err != nil || len(store.enqueued) != 0 {
		t.Fatalf("%v %d", err, len(store.enqueued))
	}
	// Restock: each alert fires once, redelivery and later restocks do not repeat it.
	for _, id := range []string{"inv-2", "inv-2", "inv-3"} {
		if err := ing.Handle(ctx, availabilityEvent(id, 12)); err != nil {
			t.Fatal(err)
		}
	}
	got := summary(store.enqueued)
	want := []string{app.StockAlertTemplate + "|email|ada@example.com", app.StockAlertTemplate + "|email|two@example.com",
		app.StockAlertTemplate + "|push|fcm:tok"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	for _, r := range store.enqueued {
		if r.Address == "ada@example.com" && (!strings.Contains(r.BodyText, "productTitle=Gari 1kg") ||
			!strings.Contains(r.BodyText, "productUrl=https://shop.test/p/ATGARI0001") || !strings.Contains(r.BodyText, "firstName=Ada")) {
			t.Fatalf("%q", r.BodyText)
		}
		if r.Address == "two@example.com" && !strings.Contains(r.BodyText, "firstName=there") {
			t.Fatalf("%q", r.BodyText)
		}
	}
	mine, _ := sa.List(ctx, "shopper-1")
	if len(mine) != 1 || mine[0].Status != domain.StockAlertNotified {
		t.Fatalf("%+v", mine)
	}
	// Subscribing again after a notification re-arms the alert.
	if _, created, _ := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001"}); !created {
		t.Fatal("re-subscribe after notification must re-arm")
	}
	if err := sa.Unsubscribe(ctx, "shopper-1", "atgari0001"); err != nil {
		t.Fatal(err)
	}
	if err := sa.Unsubscribe(ctx, "shopper-1", "ATGARI0001"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
}

func TestStockAlertsIdentityOutageRedelivers(t *testing.T) {
	ctx := context.Background()
	store := newFakeStore()
	alerts := &memAlerts{eventOf: map[string]string{}}
	res := &fakeResolver{err: errors.New("timeout")}
	n := app.NewNotifier(echoTemplates{fields: []string{"productTitle"}}, app.NewRenderer(), store, nil)
	sa := app.NewStockAlerts(alerts, n, res, links, quietLog())
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001"}); err != nil {
		t.Fatal(err)
	}
	if err := sa.HandleAvailability(ctx, availabilityEvent("inv-9", 5)); err == nil {
		t.Fatal("identity failure must be returned")
	}
	res.err, res.contacts = nil, map[string]port.Contact{"shopper-1": ada()}
	// The redelivered event finds the alerts it already claimed and sends them.
	if err := sa.HandleAvailability(ctx, availabilityEvent("inv-9", 5)); err != nil || len(store.enqueued) != 2 {
		t.Fatalf("%v %v", err, summary(store.enqueued))
	}
}
