package pgstore_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/pgstore"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func startPostgres(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "postgis/postgis:17-3.5",
		testcontainers.WithEnv(map[string]string{"POSTGRES_USER": "carriers", "POSTGRES_PASSWORD": "carriers", "POSTGRES_DB": "carriers"}),
		testcontainers.WithExposedPorts("5432/tcp"),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(2*time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	hp, err := ctr.PortEndpoint(ctx, "5432/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := pgxpool.New(ctx, "postgres://carriers:carriers@"+hp+"/carriers")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err := pgstore.Migrate(ctx, pool, carriergateway.Files); err != nil {
		t.Fatal(err)
	}
	if err := pgstore.Migrate(ctx, pool, carriergateway.Files); err != nil {
		t.Fatalf("migrations must be re-runnable: %v", err)
	}
	return pool
}

type countingPublisher struct {
	mu     sync.Mutex
	seen   map[string]int
	order  []string
	failID string
}

func (p *countingPublisher) Publish(_ context.Context, e events.Envelope) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e.ID == p.failID {
		return errors.New("broker unavailable")
	}
	time.Sleep(2 * time.Millisecond) // widen the race window between dispatchers
	p.seen[e.ID]++
	p.order = append(p.order, e.ID)
	return nil
}

func (p *countingPublisher) Close() {}

func TestPostgresStore(t *testing.T) {
	pool := startPostgres(t)
	ctx := context.Background()
	store := pgstore.New(pool)
	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))

	t.Run("label lifecycle and idempotency slot", func(t *testing.T) {
		rec := domain.LabelRecord{ID: uuid.NewString(), IdempotencyKey: "org_1:k1", RequestHash: "h1", Reference: "PKG-1",
			OriginNode: "fc_houston_tx", Carrier: domain.UPS, Service: "03", Status: domain.LabelPurchasing, CreatedAt: time.Now().UTC()}
		got, created, err := store.Reserve(ctx, rec)
		if err != nil || !created || got.ID != rec.ID {
			t.Fatalf("reserve %v %v", created, err)
		}
		dup := rec
		dup.ID = uuid.NewString()
		got, created, err = store.Reserve(ctx, dup)
		if err != nil || created || got.ID != rec.ID || got.Status != domain.LabelPurchasing {
			t.Fatalf("second reserve must return the first row: %+v %v", got, err)
		}
		rec.TrackingNumber, rec.CostMinor, rec.Currency, rec.Format, rec.LabelKey = "1ZTEST1", 1102, "USD", domain.FormatZPL, "labels/ups/x.zpl"
		rec.Status = domain.LabelPurchased
		if err := store.Complete(ctx, rec); err != nil {
			t.Fatal(err)
		}
		l, err := store.Get(ctx, rec.ID)
		if err != nil || l.Status != domain.LabelPurchased || l.TrackingState != domain.StateLabelCreated || l.CostMinor != 1102 {
			t.Fatalf("get %+v %v", l, err)
		}
		if err := store.MarkVoided(ctx, rec.ID, time.Now()); err != nil {
			t.Fatal(err)
		}
		if err := store.MarkVoided(ctx, rec.ID, time.Now()); !errors.Is(err, domain.ErrConflict) {
			t.Fatalf("double void: %v", err)
		}
		sh, err := store.GetShipment(ctx, domain.UPS, "1ZTEST1")
		if err != nil || sh.Active || sh.LabelID != rec.ID {
			t.Fatalf("voided label must stop tracking: %+v %v", sh, err)
		}
		failed := domain.LabelRecord{ID: uuid.NewString(), IdempotencyKey: "org_1:k2", RequestHash: "h", Carrier: domain.UPS, Service: "03",
			Status: domain.LabelPurchasing, CreatedAt: time.Now()}
		_, _, _ = store.Reserve(ctx, failed)
		if err := store.Release(ctx, failed.ID); err != nil {
			t.Fatal(err)
		}
		if _, created, _ := store.Reserve(ctx, failed); !created {
			t.Fatal("released key must be reusable")
		}
	})

	t.Run("tracking ingest deduplicates and orders", func(t *testing.T) {
		svc := app.NewTrackingService(app.Carriers{}, store, app.PollPolicy{}, quiet, time.Now)
		if err := store.Register(ctx, domain.TrackedShipment{Carrier: domain.FedEx, TrackingNumber: "794", Reference: "ORD-7",
			State: domain.StateLabelCreated, Active: true}); err != nil {
			t.Fatal(err)
		}
		t0 := time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)
		mk := func(id string, s domain.ShipmentState, h int) domain.TrackingEvent {
			return domain.TrackingEvent{Carrier: domain.FedEx, TrackingNumber: "794", EventID: id, Code: id, State: s, OccurredAt: t0.Add(time.Duration(h) * time.Hour)}
		}
		r, err := svc.Ingest(ctx, []domain.TrackingEvent{mk("od", domain.StateOutForDelivery, 20), mk("it", domain.StateInTransit, 10)})
		if err != nil || r.Accepted != 2 || r.Changed != 2 {
			t.Fatalf("batch %+v %v", r, err)
		}
		r, _ = svc.Ingest(ctx, []domain.TrackingEvent{mk("pu", domain.StatePickedUp, 2), mk("od", domain.StateOutForDelivery, 20)})
		if r.Accepted != 1 || r.Duplicates != 1 || r.Changed != 0 {
			t.Fatalf("late + duplicate %+v", r)
		}
		v, err := svc.Track(ctx, domain.FedEx, "794")
		if err != nil || v.Shipment.State != domain.StateOutForDelivery || len(v.Events) != 3 || v.Events[0].EventID != "pu" {
			t.Fatalf("view %+v %v", v, err)
		}
		// Unknown tracking numbers pushed by a carrier are tracked from their first event.
		if _, err := svc.Ingest(ctx, []domain.TrackingEvent{{Carrier: domain.DHL, TrackingNumber: "JD01", EventID: "x", State: domain.StatePickedUp, OccurredAt: t0}}); err != nil {
			t.Fatal(err)
		}
		if sh, err := store.GetShipment(ctx, domain.DHL, "JD01"); err != nil || sh.State != domain.StatePickedUp {
			t.Fatalf("auto-registered %+v %v", sh, err)
		}
	})

	t.Run("poll claims skip locked rows", func(t *testing.T) {
		for _, tn := range []string{"9400A", "9400B", "9400C"} {
			_ = store.Register(ctx, domain.TrackedShipment{Carrier: domain.USPS, TrackingNumber: tn, State: domain.StateLabelCreated, Active: true})
		}
		now := time.Now().Add(time.Minute)
		a, err := store.ClaimDuePolls(ctx, domain.USPS, now, 10*time.Minute, 2)
		if err != nil || len(a) != 2 {
			t.Fatalf("claim %v %v", a, err)
		}
		b, _ := store.ClaimDuePolls(ctx, domain.USPS, now, 10*time.Minute, 5)
		if len(b) != 1 {
			t.Fatalf("leased rows must not be claimed twice, got %d", len(b))
		}
		if err := store.SchedulePoll(ctx, domain.USPS, b[0].TrackingNumber, now.Add(-time.Second), 3); err != nil {
			t.Fatal(err)
		}
		c, _ := store.ClaimDuePolls(ctx, domain.USPS, now, 10*time.Minute, 5)
		if len(c) != 1 || c[0].Failures != 3 {
			t.Fatalf("rescheduled row %+v", c)
		}
	})

	t.Run("outbox dispatchers publish every event exactly once", func(t *testing.T) {
		if _, err := pool.Exec(ctx, `update outbox set published_at = now()`); err != nil {
			t.Fatal(err)
		}
		ids := make([]string, 0, 60)
		for i := range 60 {
			id := uuid.NewString()
			ids = append(ids, id)
			if err := store.Enqueue(ctx, events.Envelope{ID: id, Type: domain.EventTrackingUpdated, Aggregate: domain.EventAggregate,
				AggregateID: "fedex:" + string(rune('A'+i%5)), OccurredAt: time.Now(), Payload: []byte(`{"i":1}`)}); err != nil {
				t.Fatal(err)
			}
		}
		pub := &countingPublisher{seen: map[string]int{}}
		var wg sync.WaitGroup
		for range 4 {
			wg.Go(func() {
				d := app.NewDispatcher(store, pub, 7, 10*time.Millisecond, quiet)
				for {
					n, err := d.RunOnce(ctx)
					if err != nil {
						t.Error(err)
						return
					}
					if n == 0 {
						var pending int
						_ = pool.QueryRow(ctx, `select count(*) from outbox where published_at is null`).Scan(&pending)
						if pending == 0 {
							return
						}
					}
				}
			})
		}
		wg.Wait()
		for _, id := range ids {
			if pub.seen[id] != 1 {
				t.Fatalf("event %s published %d times", id, pub.seen[id])
			}
		}
	})

	t.Run("failed publish is retried later with backoff", func(t *testing.T) {
		id := uuid.NewString()
		_ = store.Enqueue(ctx, events.Envelope{ID: id, Type: domain.EventTrackingUpdated, Aggregate: domain.EventAggregate,
			AggregateID: "ups:1Z", OccurredAt: time.Now(), Payload: []byte(`{}`)})
		pub := &countingPublisher{seen: map[string]int{}, failID: id}
		d := app.NewDispatcher(store, pub, 10, time.Second, quiet)
		if n, err := d.RunOnce(ctx); err != nil || n != 0 {
			t.Fatalf("n=%d err=%v", n, err)
		}
		var attempts int
		var lastErr string
		var due bool
		if err := pool.QueryRow(ctx, `select attempts, last_error, next_attempt_at > now() from outbox where id = $1`, id).Scan(&attempts, &lastErr, &due); err != nil {
			t.Fatal(err)
		}
		if attempts != 1 || lastErr != "broker unavailable" || !due {
			t.Fatalf("attempts=%d lastErr=%q backoff=%v", attempts, lastErr, due)
		}
		_, _ = pool.Exec(ctx, `update outbox set next_attempt_at = now() where id = $1`, id)
		pub.failID = ""
		if n, _ := d.RunOnce(ctx); n != 1 || pub.seen[id] != 1 {
			t.Fatalf("retry must publish: n=%d", n)
		}
	})

	t.Run("pickup locations", func(t *testing.T) {
		loc := domain.PickupLocation{ID: uuid.NewString(), Kind: domain.PickupLocker, OwnerOrg: "org_afrotransact", Name: "Edison FC locker",
			Address: domain.Address{Line1: "100 Raritan Center Pkwy", City: "Edison", State: "NJ", PostalCode: "08837"}, Timezone: "America/New_York",
			Hours: []domain.OpeningHours{{Weekday: time.Monday, Open: "07:00", Close: "22:00"}}, ReadyHours: 4, Active: true, UpdatedAt: time.Now()}
		if err := store.SaveLocation(ctx, loc); err != nil {
			t.Fatal(err)
		}
		ls, err := store.ListLocations(ctx, "", "088", true)
		if err != nil || len(ls) != 1 || ls[0].Hours[0].Open != "07:00" || ls[0].Address.City != "Edison" {
			t.Fatalf("list %+v %v", ls, err)
		}
		if ls, _ := store.ListLocations(ctx, "", "770", true); len(ls) != 0 {
			t.Fatal("postal prefix filter")
		}
		if err := store.SavePickup(ctx, domain.Pickup{ID: uuid.NewString(), Carrier: domain.UPS, Confirmation: "PRN1", Date: time.Now(),
			CreatedAt: time.Now()}, domain.PickupRequest{Packages: 1}); err != nil {
			t.Fatal(err)
		}
	})
}
