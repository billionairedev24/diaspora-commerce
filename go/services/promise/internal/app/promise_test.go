package app_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/nodes"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/adapter/ziptable"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

type memStore struct {
	mu        sync.Mutex
	computed  map[string]domain.Promise
	snapshots map[string]domain.Promise
	gets      int
	hits      int
}

func newMemStore() *memStore {
	return &memStore{computed: map[string]domain.Promise{}, snapshots: map[string]domain.Promise{}}
}

func (m *memStore) GetComputed(_ context.Context, k string) (domain.Promise, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.gets++
	p, ok := m.computed[k]
	if ok {
		m.hits++
	}
	return p, ok, nil
}

func (m *memStore) PutComputed(_ context.Context, k string, p domain.Promise, _ time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.computed[k] = p
	return nil
}

func (m *memStore) SaveSnapshots(_ context.Context, ps []domain.Promise, _ time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, p := range ps {
		m.snapshots[p.ID] = p
	}
	return nil
}

func (m *memStore) Snapshot(_ context.Context, id string) (domain.Promise, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	p, ok := m.snapshots[id]
	if !ok {
		return domain.Promise{}, domain.ErrNotFound
	}
	return p, nil
}

func (m *memStore) Ping(context.Context) error { return nil }

var _ port.PromiseStore = (*memStore)(nil)

func fcConfig() []nodes.FCConfig {
	var h nodes.FCConfig
	h.ID, h.ZIP, h.Timezone, h.Cutoff = "fc_houston_tx", "77061", "America/Chicago", "14:00"
	h.Pickup.ReadyHours = 2
	for wd := time.Monday; wd <= time.Friday; wd++ {
		h.Pickup.Hours = append(h.Pickup.Hours, domain.OpeningHours{Weekday: wd, Open: "09:00", Close: "19:00"})
	}
	h.Delivery.SameDayCutoff, h.Delivery.DeliversSaturday = "11:00", true
	h.Delivery.SameDayZIP3, h.Delivery.NextDayZIP3 = []string{"770", "772", "773", "774", "775"}, []string{"776", "777"}
	return []nodes.FCConfig{h}
}

func service(t *testing.T, store port.PromiseStore, now *time.Time) app.PromiseService {
	t.Helper()
	tbl, err := ziptable.Load(promise.Files, "data/zip3_regions.csv", "data/fc_transit.csv")
	if err != nil {
		t.Fatal(err)
	}
	dir, err := nodes.New(fcConfig())
	if err != nil {
		t.Fatal(err)
	}
	return app.NewPromiseService(domain.NewPlanner(domain.NewUSCalendar()), tbl, tbl, dir, store, app.Settings{
		ServiceLevels: map[domain.ServiceLevel]domain.ServiceRule{
			domain.LevelGround: {}, domain.LevelTwoDay: {MaxDays: 2}, domain.LevelOvernight: {MaxDays: 1, SaturdayDelivery: true},
		},
		SellerCutoff: domain.Clock{Hour: 12}, SellerPickupReady: 2,
		SellerPickupHours: []domain.OpeningHours{{Weekday: time.Tuesday, Open: "10:00", Close: "18:00"}},
	}, slog.New(slog.NewTextHandler(io.Discard, nil)), func() time.Time { return *now })
}

func fc(id string, m app.MethodInput) app.OfferInput {
	return app.OfferInput{OfferID: id, Origin: app.OriginInput{Type: domain.OriginFC, ID: "fc_houston_tx"}, Method: m}
}

func TestBatchPromises(t *testing.T) {
	central, _ := time.LoadLocation("America/Chicago")
	now := time.Date(2026, 10, 8, 10, 30, 0, 0, central) // Thursday
	store := newMemStore()
	svc := service(t, store, &now)
	ready := 3
	res, err := svc.Promise(context.Background(), app.BatchRequest{DestinationZIP: "77002", Offers: []app.OfferInput{
		fc("o1", app.MethodInput{Type: domain.MethodCarrier, ServiceLevel: domain.LevelGround}),
		{OfferID: "o2", Origin: app.OriginInput{Type: domain.OriginSeller, ID: "org_mama", ShipFromZIP: "30310", HandlingDays: 2},
			Method: app.MethodInput{Type: domain.MethodCarrier, ServiceLevel: domain.LevelGround}},
		fc("o3", app.MethodInput{Type: domain.MethodAfroDelivery}),
		fc("o4", app.MethodInput{Type: domain.MethodPickup}),
		{OfferID: "o5", Origin: app.OriginInput{Type: domain.OriginSeller, ID: "org_shop", ShipFromZIP: "77005", HandlingDays: 1},
			Method: app.MethodInput{Type: domain.MethodPickup, Pickup: &app.PickupInput{LocationID: "loc1", ReadyHours: &ready}}},
		fc("o6", app.MethodInput{Type: domain.MethodCarrier, ServiceLevel: "TELEPORT"}),
		{OfferID: "o7", Origin: app.OriginInput{Type: domain.OriginFC, ID: "fc_nowhere"}, Method: app.MethodInput{Type: domain.MethodCarrier, ServiceLevel: domain.LevelGround}},
	}})
	if err != nil {
		t.Fatal(err)
	}
	p := res.Promises
	if len(p) != 7 || res.DestinationZIP != "77002" {
		t.Fatalf("%+v", res)
	}
	if !p[0].Available || p[0].Label != "Tomorrow, Oct 9" || p[0].CutoffLabel != "Order within 3 h 30 min" || p[0].Transit.Source != "table" {
		t.Fatalf("FC ground: %+v", p[0])
	}
	// Seller in Atlanta (ZIP3 303, a table lane): 2 handling days from Thu skip the weekend and Columbus Day → ships Tue Oct 13.
	if !p[1].Available || p[1].ShipDate.String() != "2026-10-13" || p[1].Transit.Source != "table" || p[1].CutoffLabel != "Order within 30 min" || p[1].Label != "Thu, Oct 15 – Fri, Oct 16" {
		t.Fatalf("seller: %+v", p[1])
	}
	if p[2].Label != "Today, Oct 8" || p[2].CutoffLabel != "Order within 30 min" {
		t.Fatalf("AfroTransact same-day: %+v", p[2])
	}
	if p[3].Label != "Ready for pickup today at 12:30 PM" {
		t.Fatalf("FC pickup: %+v", p[3])
	}
	if p[4].Label != "Ready for pickup Tue, Oct 13 at 10:00 AM" {
		t.Fatalf("seller pickup after handling: %+v", p[4])
	}
	if p[5].Available || p[5].Reason != `unknown service level "TELEPORT"` || p[6].Available || p[6].ID != "" {
		t.Fatalf("invalid offers must be unavailable with a reason: %+v %+v", p[5], p[6])
	}
	snap, err := svc.Get(context.Background(), p[0].ID)
	if err != nil || snap.Label != p[0].Label || snap.OfferID != "o1" || !snap.ExpiresAt.Equal(now.Add(24*time.Hour).UTC()) {
		t.Fatalf("snapshot %+v %v", snap, err)
	}
	if _, err := svc.Get(context.Background(), "prm_unknown"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("unknown id: %v", err)
	}
}

func TestComputedPromisesAreCachedPerHour(t *testing.T) {
	central, _ := time.LoadLocation("America/Chicago")
	now := time.Date(2026, 10, 8, 9, 10, 0, 0, central)
	store := newMemStore()
	svc := service(t, store, &now)
	req := app.BatchRequest{DestinationZIP: "10001", Offers: []app.OfferInput{
		fc("a", app.MethodInput{Type: domain.MethodCarrier, ServiceLevel: domain.LevelGround}),
		fc("b", app.MethodInput{Type: domain.MethodCarrier, ServiceLevel: domain.LevelGround}),
	}}
	first, _ := svc.Promise(context.Background(), req)
	if store.hits != 1 {
		t.Fatalf("second offer with the same origin/method/zip must reuse the computation, hits=%d", store.hits)
	}
	now = now.Add(20 * time.Minute)
	second, _ := svc.Promise(context.Background(), req)
	if store.hits != 3 || second.Promises[0].CutoffLabel != "Order within 4 h 30 min" || first.Promises[0].CutoffLabel != "Order within 4 h 50 min" {
		t.Fatalf("hits=%d labels %q / %q", store.hits, first.Promises[0].CutoffLabel, second.Promises[0].CutoffLabel)
	}
	if first.Promises[0].ID != second.Promises[0].ID || first.Promises[0].ID == first.Promises[1].ID {
		t.Fatal("promise ids are stable per offer and dates, distinct per offer")
	}
	now = now.Add(5 * time.Hour) // past the cutoff: new bucket, new dates
	third, _ := svc.Promise(context.Background(), req)
	if third.Promises[0].ShipDate.String() != "2026-10-09" {
		t.Fatalf("after cutoff %+v", third.Promises[0])
	}
}

func TestBatchValidation(t *testing.T) {
	now := time.Now()
	svc := service(t, newMemStore(), &now)
	if _, err := svc.Promise(context.Background(), app.BatchRequest{DestinationZIP: "7700", Offers: []app.OfferInput{fc("a", app.MethodInput{})}}); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("bad zip: %v", err)
	}
	if _, err := svc.Promise(context.Background(), app.BatchRequest{DestinationZIP: "77002"}); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("no offers: %v", err)
	}
	res, _ := svc.Promise(context.Background(), app.BatchRequest{DestinationZIP: "10001", Offers: []app.OfferInput{fc("a", app.MethodInput{Type: domain.MethodAfroDelivery})}})
	if res.Promises[0].Available {
		t.Fatal("Houston drivers do not deliver to New York")
	}
}
