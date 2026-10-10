package app_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/authz-sync/internal/port"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

type fakeStore struct {
	changes  []domain.Change
	markets  []domain.Tuple
	failures int
	reject   bool
}

func (f *fakeStore) Write(_ context.Context, c domain.Change) error {
	if f.reject {
		return fmt.Errorf("400: %w", port.ErrRejected)
	}
	if f.failures > 0 {
		f.failures--
		return errors.New("openfga down")
	}
	f.changes = append(f.changes, c)
	return nil
}

func (f *fakeStore) Read(_ context.Context, _, _ string) ([]domain.Tuple, error) {
	return f.markets, nil
}

func (f *fakeStore) Ping(context.Context) error { return nil }

type fakeDLQ struct{ parked []events.Envelope }

func (d *fakeDLQ) Publish(_ context.Context, e events.Envelope) error {
	d.parked = append(d.parked, e)
	return nil
}

func (d *fakeDLQ) Close() {}

func envelope(typ, payload string) events.Envelope {
	return events.Envelope{ID: "e-" + typ, Type: typ, Payload: []byte(payload)}
}

func TestSyncerMirrorsRolesAndOrganisations(t *testing.T) {
	store := &fakeStore{}
	s := app.NewSyncer(store, "org_retail", quiet)
	ctx := context.Background()

	must(t, s.Handle(ctx, envelope(domain.RoleGranted,
		`{"userId":"u1","roleKey":"seller.catalog","orgId":"org_a","actorId":"admin","at":"2026-10-10T10:00:00Z"}`)))
	must(t, s.Handle(ctx, envelope(domain.RoleRevoked,
		`{"userId":"u1","roleKey":"seller.catalog","orgId":"org_a","actorId":"admin","at":"2026-10-10T11:00:00Z"}`)))
	must(t, s.Handle(ctx, envelope(domain.SellerOrgApproved,
		`{"orgId":"org_a","kind":"SELLER","country":"US","status":"APPROVED","occurredAt":"2026-10-10T10:00:00Z"}`)))
	must(t, s.Handle(ctx, envelope("identity.user.registered", `{"userId":"u9"}`)))
	must(t, s.Handle(ctx, envelope(domain.RoleGranted, `{"userId":"d1","roleKey":"platform.driver"}`)))

	if len(store.changes) != 3 {
		t.Fatalf("changes = %+v", store.changes)
	}
	member := domain.Tuple{User: "user:u1", Relation: "catalog", Object: "organization:org_a"}
	if store.changes[0].Writes[0] != member || store.changes[1].Deletes[0] != member {
		t.Fatalf("membership = %+v", store.changes[:2])
	}
	if got := store.changes[2].Writes[2]; got != (domain.Tuple{User: "market:US", Relation: "market",
		Object: "organization:org_a"}) {
		t.Fatalf("market = %+v", got)
	}
}

func TestMalformedEventsArePoison(t *testing.T) {
	s := app.NewSyncer(&fakeStore{}, "", quiet)
	for _, e := range []events.Envelope{
		envelope(domain.RoleGranted, `{not json`),
		envelope(domain.SellerOrgUpdated, `{"kind":"SELLER"}`),
	} {
		if err := s.Handle(context.Background(), e); !errors.Is(err, app.ErrPoison) {
			t.Fatalf("%s: err = %v, want poison", e.Type, err)
		}
	}
	rejected := app.NewSyncer(&fakeStore{reject: true}, "", quiet)
	err := rejected.Handle(context.Background(), envelope(domain.RoleGranted,
		`{"userId":"u1","roleKey":"seller.owner","orgId":"org_a"}`))
	if !errors.Is(err, app.ErrPoison) {
		t.Fatalf("rejected write: err = %v, want poison", err)
	}
}

func TestOutagesAreRetriedUntilTheyHealAndPoisonIsParked(t *testing.T) {
	store := &fakeStore{failures: 3}
	dlq := &fakeDLQ{}
	h := app.NewRetryingHandler(app.NewSyncer(store, "", quiet), dlq, time.Millisecond, 2*time.Millisecond, quiet)

	must(t, h.Handle(context.Background(), envelope(domain.RoleRevoked,
		`{"userId":"u1","roleKey":"seller.owner","orgId":"org_a"}`)))
	if len(store.changes) != 1 || len(dlq.parked) != 0 {
		t.Fatalf("revocation lost: changes=%d parked=%d", len(store.changes), len(dlq.parked))
	}

	must(t, h.Handle(context.Background(), envelope(domain.RoleGranted, `{broken`)))
	if len(dlq.parked) != 1 {
		t.Fatalf("poison not parked: %d", len(dlq.parked))
	}
}

func TestRetryStopsWhenTheContextEnds(t *testing.T) {
	store := &fakeStore{failures: 1 << 30}
	h := app.NewRetryingHandler(app.NewSyncer(store, "", quiet), &fakeDLQ{}, time.Millisecond, time.Millisecond, quiet)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := h.Handle(ctx, envelope(domain.RoleGranted,
		`{"userId":"u1","roleKey":"seller.owner","orgId":"org_a"}`)); err == nil {
		t.Fatal("expected cancellation error")
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}
