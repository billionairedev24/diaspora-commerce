package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func shipment(ref string) domain.Shipment {
	return domain.Shipment{Reference: ref, OriginNode: "fc_houston_tx",
		From:     domain.Address{Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002"},
		To:       domain.Address{Line1: "2 Court St", City: "Brooklyn", State: "NY", PostalCode: "11201"},
		Parcels:  []domain.Parcel{{WeightLb: 2, LengthIn: 10, WidthIn: 8, HeightIn: 4}},
		ShipDate: time.Date(2026, 10, 12, 0, 0, 0, 0, time.UTC)}
}

func allAccounts() fakeAccounts {
	return fakeAccounts{accts: []domain.Account{
		{Carrier: domain.USPS}, {Carrier: domain.UPS, Negotiated: true}, {Carrier: domain.FedEx, Negotiated: true, AdjustmentPct: 10},
	}}
}

func TestRateFanOutRespectsBudgetAndFlagsPartial(t *testing.T) {
	carriers := app.Carriers{
		domain.USPS:  &fakeCarrier{code: domain.USPS, quotes: []domain.Quote{{Carrier: domain.USPS, Service: "USPS_GROUND_ADVANTAGE", AmountMinor: 935}}},
		domain.UPS:   &fakeCarrier{code: domain.UPS, delay: 5 * time.Second, quotes: []domain.Quote{{Carrier: domain.UPS, Service: "03", AmountMinor: 1100}}},
		domain.FedEx: &fakeCarrier{code: domain.FedEx, rateErr: &domain.CarrierError{Carrier: domain.FedEx, Status: 503, Kind: domain.ErrCarrierUnavailable}},
	}
	cache := &memCache{}
	svc := app.NewRateService(carriers, allAccounts(), cache, 150*time.Millisecond, 10*time.Minute, quietLog)
	start := time.Now()
	res, err := svc.Shop(context.Background(), app.RateRequest{CartID: "cart-1", Shipment: shipment("p1")})
	elapsed := time.Since(start)
	if err != nil {
		t.Fatal(err)
	}
	if elapsed > 400*time.Millisecond {
		t.Fatalf("budget not respected: %v", elapsed)
	}
	if !res.Partial || len(res.Quotes) != 1 || res.Quotes[0].Carrier != domain.USPS {
		t.Fatalf("result %+v", res)
	}
	status := map[domain.CarrierCode]string{}
	for _, c := range res.Carriers {
		status[c.Carrier] = c.Status
	}
	if status[domain.USPS] != app.OutcomeOK || status[domain.UPS] != app.OutcomeTimeout || status[domain.FedEx] != app.OutcomeError {
		t.Fatalf("outcomes %+v", res.Carriers)
	}
	if cache.puts != 0 {
		t.Fatal("partial results must not be cached")
	}
}

func TestRateQuotesAreCachedPerCart(t *testing.T) {
	usps := &fakeCarrier{code: domain.USPS, quotes: []domain.Quote{{Carrier: domain.USPS, Service: "PRIORITY_MAIL", AmountMinor: 1260}}}
	ups := &fakeCarrier{code: domain.UPS, quotes: []domain.Quote{{Carrier: domain.UPS, Service: "03", AmountMinor: 1102}}}
	fedex := &fakeCarrier{code: domain.FedEx, quotes: []domain.Quote{{Carrier: domain.FedEx, Service: "FEDEX_GROUND", AmountMinor: 1000}}}
	cache := &memCache{}
	svc := app.NewRateService(app.Carriers{domain.USPS: usps, domain.UPS: ups, domain.FedEx: fedex}, allAccounts(), cache,
		time.Second, 10*time.Minute, quietLog)
	ctx := context.Background()
	first, err := svc.Shop(ctx, app.RateRequest{CartID: "cart-1", Shipment: shipment("p1")})
	if err != nil || first.Cached || first.Partial || len(first.Quotes) != 3 {
		t.Fatalf("first %+v %v", first, err)
	}
	// FedEx account carries +10 % adjustment; quotes are sorted cheapest first.
	if first.Quotes[0].Carrier != domain.FedEx || first.Quotes[0].AmountMinor != 1100 || first.Quotes[1].AmountMinor != 1102 {
		t.Fatalf("sorted/adjusted quotes %+v", first.Quotes)
	}
	second, err := svc.Shop(ctx, app.RateRequest{CartID: "cart-1", Shipment: shipment("p1")})
	if err != nil || !second.Cached || len(second.Quotes) != 3 || usps.rateCalls.Load() != 1 {
		t.Fatalf("second call must come from cache: %+v calls=%d", second, usps.rateCalls.Load())
	}
	if _, err := svc.Shop(ctx, app.RateRequest{CartID: "cart-2", Shipment: shipment("p1")}); err != nil || usps.rateCalls.Load() != 2 {
		t.Fatalf("another cart is a cache miss, calls=%d", usps.rateCalls.Load())
	}
	filtered, _ := svc.Shop(ctx, app.RateRequest{CartID: "cart-3", Shipment: shipment("p1"), Carriers: []domain.CarrierCode{domain.UPS}})
	if len(filtered.Quotes) != 1 || filtered.Quotes[0].Carrier != domain.UPS || !filtered.Quotes[0].Negotiated {
		t.Fatalf("carrier filter %+v", filtered.Quotes)
	}
}

func TestRateWithoutAccountsIsRejected(t *testing.T) {
	svc := app.NewRateService(app.Carriers{}, allAccounts(), &memCache{}, time.Second, time.Minute, quietLog)
	if _, err := svc.Shop(context.Background(), app.RateRequest{Shipment: shipment("p")}); !errors.Is(err, domain.ErrCarrierDisabled) {
		t.Fatalf("got %v", err)
	}
}

func newLabels(c *fakeCarrier, store *memStore) app.LabelService {
	return app.NewLabelService(app.Carriers{c.code: c}, allAccounts(), store, &memObjects{}, domain.BillingPolicy{}, quietLog, time.Now)
}

func TestLabelPurchaseIsIdempotent(t *testing.T) {
	ups := &fakeCarrier{code: domain.UPS}
	store := newMemStore()
	svc := newLabels(ups, store)
	ctx := context.Background()
	req := app.BuyRequest{IdempotencyKey: "k1", Caller: "org_1", Shipment: shipment("PKG-1"), Carrier: domain.UPS, Service: "03"}
	a, err := svc.Buy(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	b, err := svc.Buy(ctx, req)
	if err != nil || a.ID != b.ID || ups.buyCalls.Load() != 1 {
		t.Fatalf("retry must return the same label without buying again: %v %v calls=%d", a.ID, b.ID, ups.buyCalls.Load())
	}
	if len(ups.subscribed) != 1 || ups.subscribed[0] != a.TrackingNumber {
		t.Fatalf("UPS labels subscribe to Track Alert: %v", ups.subscribed)
	}
	other := req
	other.Service = "01"
	if _, err := svc.Buy(ctx, other); !errors.Is(err, app.ErrIdempotencyMismatch) {
		t.Fatalf("key reuse with another body: %v", err)
	}
	other.Caller = "org_2"
	if c, err := svc.Buy(ctx, other); err != nil || c.ID == a.ID {
		t.Fatalf("keys are scoped per caller: %v", err)
	}
	if _, err := svc.Buy(ctx, app.BuyRequest{Shipment: shipment("x"), Carrier: domain.UPS, Service: "03"}); !errors.Is(err, domain.ErrInvalid) {
		t.Fatal("missing Idempotency-Key must be rejected")
	}
	doc, ct, err := svc.Document(ctx, a.ID)
	if err != nil || string(doc) != "%PDF" || ct != "application/pdf" {
		t.Fatalf("document %q %s %v", doc, ct, err)
	}
}

func TestFailedPurchaseFreesTheKey(t *testing.T) {
	ups := &fakeCarrier{code: domain.UPS, buyErr: &domain.CarrierError{Carrier: domain.UPS, Status: 503, Kind: domain.ErrCarrierUnavailable}}
	store := newMemStore()
	svc := newLabels(ups, store)
	req := app.BuyRequest{IdempotencyKey: "k1", Caller: "org_1", Shipment: shipment("PKG-1"), Carrier: domain.UPS, Service: "03"}
	if _, err := svc.Buy(context.Background(), req); !domain.Retryable(err) {
		t.Fatalf("expected retryable error, got %v", err)
	}
	ups.buyErr = nil
	if _, err := svc.Buy(context.Background(), req); err != nil {
		t.Fatalf("retry with the same key must succeed after a failure: %v", err)
	}
}

func TestVoidOnlyBeforeAcceptance(t *testing.T) {
	ups := &fakeCarrier{code: domain.UPS}
	store := newMemStore()
	svc := newLabels(ups, store)
	ctx := context.Background()
	a, _ := svc.Buy(ctx, app.BuyRequest{IdempotencyKey: "a", Caller: "o", Shipment: shipment("A"), Carrier: domain.UPS, Service: "03"})
	b, _ := svc.Buy(ctx, app.BuyRequest{IdempotencyKey: "b", Caller: "o", Shipment: shipment("B"), Carrier: domain.UPS, Service: "03"})

	tracking := app.NewTrackingService(app.Carriers{domain.UPS: ups}, store, app.PollPolicy{}, quietLog, time.Now)
	if _, err := tracking.Ingest(ctx, []domain.TrackingEvent{{Carrier: domain.UPS, TrackingNumber: b.TrackingNumber, EventID: "e1",
		State: domain.StatePickedUp, OccurredAt: time.Now()}}); err != nil {
		t.Fatal(err)
	}
	v, err := svc.Void(ctx, a.ID)
	if err != nil || v.Status != domain.LabelVoided || ups.voidCalls.Load() != 1 {
		t.Fatalf("void %+v %v", v, err)
	}
	if _, err := svc.Void(ctx, b.ID); !errors.Is(err, domain.ErrConflict) || ups.voidCalls.Load() != 1 {
		t.Fatalf("picked-up label must not be voided: %v", err)
	}
}

func ev(id string, state domain.ShipmentState, at time.Time) domain.TrackingEvent {
	return domain.TrackingEvent{Carrier: domain.FedEx, TrackingNumber: "794", EventID: id, State: state, OccurredAt: at}
}

func TestIngestDeduplicatesAndNeverRegresses(t *testing.T) {
	store := newMemStore()
	_ = store.Register(context.Background(), domain.TrackedShipment{Carrier: domain.FedEx, TrackingNumber: "794", Reference: "ORD-1",
		State: domain.StateLabelCreated, Active: true})
	svc := app.NewTrackingService(app.Carriers{}, store, app.PollPolicy{}, quietLog, time.Now)
	ctx := context.Background()
	t0 := time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

	r, _ := svc.Ingest(ctx, []domain.TrackingEvent{ev("it", domain.StateInTransit, t0.Add(5*time.Hour))})
	if r.Accepted != 1 || r.Changed != 1 {
		t.Fatalf("first %+v", r)
	}
	// The pickup scan arrives late (out of order): stored as history, state stays in_transit, nothing published.
	r, _ = svc.Ingest(ctx, []domain.TrackingEvent{ev("pu", domain.StatePickedUp, t0.Add(1*time.Hour))})
	if r.Accepted != 1 || r.Changed != 0 {
		t.Fatalf("late %+v", r)
	}
	// Webhook retry of the same event.
	r, _ = svc.Ingest(ctx, []domain.TrackingEvent{ev("it", domain.StateInTransit, t0.Add(5*time.Hour))})
	if r.Duplicates != 1 || r.Accepted != 0 {
		t.Fatalf("duplicate %+v", r)
	}
	// One batch delivered newest-first is applied in carrier-timestamp order.
	r, _ = svc.Ingest(ctx, []domain.TrackingEvent{
		ev("dl", domain.StateDelivered, t0.Add(30*time.Hour)),
		ev("od", domain.StateOutForDelivery, t0.Add(26*time.Hour)),
	})
	if r.Accepted != 2 || r.Changed != 2 {
		t.Fatalf("batch %+v", r)
	}
	view, err := svc.Track(ctx, domain.FedEx, "794")
	if err != nil || view.Shipment.State != domain.StateDelivered || view.Shipment.Active || len(view.Events) != 4 || view.Events[0].EventID != "pu" {
		t.Fatalf("view %+v %v", view, err)
	}
	if n := store.outboxLen(); n != 3 {
		t.Fatalf("outbox holds %d events, want 3 (in_transit, out_for_delivery, delivered)", n)
	}
	var last domain.TrackingUpdated
	_ = json.Unmarshal(store.outbox[2].Payload, &last)
	if store.outbox[2].Type != domain.EventTrackingUpdated || store.outbox[2].Topic() != "at.carrier-shipment.events" ||
		last.State != domain.StateDelivered || last.PreviousState != domain.StateOutForDelivery || last.Reference != "ORD-1" {
		t.Fatalf("event %+v payload %+v", store.outbox[2], last)
	}
}

func TestWebhookVerificationGate(t *testing.T) {
	fx := &fakeCarrier{code: domain.FedEx, track: []domain.TrackingEvent{ev("x1", domain.StateInTransit, time.Now())}}
	store := newMemStore()
	svc := app.NewTrackingService(app.Carriers{domain.FedEx: fx}, store, app.PollPolicy{}, quietLog, time.Now)
	if _, err := svc.HandleWebhook(context.Background(), domain.FedEx, http.Header{"X-Sig": {"bad"}}, []byte("{}")); !errors.Is(err, domain.ErrSignature) {
		t.Fatalf("got %v", err)
	}
	if store.outboxLen() != 0 {
		t.Fatal("unverified webhook must not ingest")
	}
	r, err := svc.HandleWebhook(context.Background(), domain.FedEx, http.Header{"X-Sig": {"ok"}}, []byte("{}"))
	if err != nil || r.Accepted != 1 {
		t.Fatalf("%+v %v", r, err)
	}
}

func TestPollDueBacksOffOnFailure(t *testing.T) {
	now := time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)
	usps := &fakeCarrier{code: domain.USPS, track: []domain.TrackingEvent{{Carrier: domain.USPS, TrackingNumber: "9400", EventID: "a",
		State: domain.StatePickedUp, OccurredAt: now.Add(-time.Hour)}}}
	store := newMemStore()
	_ = store.Register(context.Background(), domain.TrackedShipment{Carrier: domain.USPS, TrackingNumber: "9400", State: domain.StateLabelCreated, Active: true})
	svc := app.NewTrackingService(app.Carriers{domain.USPS: usps}, store,
		app.PollPolicy{Carriers: []domain.CarrierCode{domain.USPS}, Interval: 2 * time.Hour, MaxBackoff: 12 * time.Hour}, quietLog,
		func() time.Time { return now })
	n, err := svc.PollDue(context.Background())
	if err != nil || n != 1 {
		t.Fatalf("polled %d %v", n, err)
	}
	if sh, _ := store.GetShipment(context.Background(), domain.USPS, "9400"); sh.State != domain.StatePickedUp {
		t.Fatalf("state %s", sh.State)
	}
	if got := store.polls["usps/9400"]; !got.Equal(now.Add(2 * time.Hour)) {
		t.Fatalf("next poll %v", got)
	}
	if n, _ := svc.PollDue(context.Background()); n != 0 {
		t.Fatal("not due yet")
	}
	usps.trackErr = &domain.CarrierError{Carrier: domain.USPS, Status: 503, Kind: domain.ErrCarrierUnavailable}
	now = now.Add(3 * time.Hour)
	_, _ = svc.PollDue(context.Background())
	_ = store.SchedulePoll(context.Background(), domain.USPS, "9400", now, store.failures["usps/9400"])
	_, _ = svc.PollDue(context.Background())
	if store.failures["usps/9400"] != 2 || !store.polls["usps/9400"].Equal(now.Add(8*time.Hour)) {
		t.Fatalf("failures=%d next=%v", store.failures["usps/9400"], store.polls["usps/9400"])
	}
}

type recordingPublisher struct {
	got  []events.Envelope
	fail bool
}

func (p *recordingPublisher) Publish(_ context.Context, e events.Envelope) error {
	if p.fail {
		return errBoom
	}
	p.got = append(p.got, e)
	return nil
}

func (p *recordingPublisher) Close() {}

func TestDispatcherRunOnce(t *testing.T) {
	store := newMemStore()
	for i := range 3 {
		_ = store.Enqueue(context.Background(), events.Envelope{ID: string(rune('a' + i)), Type: domain.EventTrackingUpdated})
	}
	pub := &recordingPublisher{}
	n, err := app.NewDispatcher(store, pub, 2, time.Second, quietLog).RunOnce(context.Background())
	if err != nil || n != 2 || len(pub.got) != 2 || store.outboxLen() != 1 {
		t.Fatalf("n=%d err=%v", n, err)
	}
}

func TestPickupLocationsAreOrgScoped(t *testing.T) {
	repo := &memPickups{m: map[string]domain.PickupLocation{}}
	store := newMemStore()
	svc := app.NewPickupService(app.Carriers{}, allAccounts(), repo, store, time.Now)
	ctx := context.Background()
	loc := domain.PickupLocation{Kind: domain.PickupSeller, Name: "Mama Put Shop", Timezone: "America/Chicago", Active: true,
		Address: domain.Address{Line1: "9 Bissonnet St", City: "Houston", State: "TX", PostalCode: "77005"},
		Hours:   []domain.OpeningHours{{Weekday: time.Saturday, Open: "10:00", Close: "16:00"}}}
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}
	saved, err := svc.SaveLocation(ctx, seller, loc)
	if err != nil || saved.OwnerOrg != "org_1" || saved.ID == "" {
		t.Fatalf("seller creates own location: %+v %v", saved, err)
	}
	intruder := auth.Actor{Subject: "u2", Type: "SELLER_MEMBER", OrgID: "org_2", Permissions: []string{app.PermLabel}}
	if _, err := svc.SaveLocation(ctx, intruder, saved); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("other org must not edit: %v", err)
	}
	counter := loc
	counter.Kind, counter.OwnerOrg = domain.PickupCounter, "org_afrotransact"
	if _, err := svc.SaveLocation(ctx, seller, counter); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("sellers cannot create platform counters: %v", err)
	}
	if _, err := svc.SaveLocation(ctx, auth.Actor{Type: "STAFF", Permissions: []string{app.PermAdmin}}, counter); err != nil {
		t.Fatal(err)
	}
	if err := svc.MarkReady(ctx, seller, saved.ID, app.ReadyRequest{OrderRef: "ORD-9", CustomerRef: "cus_1", PickupCode: "4821"}); err != nil {
		t.Fatal(err)
	}
	if store.outboxLen() != 1 || store.outbox[0].Type != domain.EventPickupReady {
		t.Fatalf("pickup ready event not enqueued: %+v", store.outbox)
	}
	if err := svc.MarkReady(ctx, intruder, saved.ID, app.ReadyRequest{OrderRef: "ORD-9", CustomerRef: "cus_1"}); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("other org cannot mark ready: %v", err)
	}
}

type memPickups struct {
	m map[string]domain.PickupLocation
}

func (p *memPickups) SavePickup(context.Context, domain.Pickup, domain.PickupRequest) error {
	return nil
}

func (p *memPickups) SaveLocation(_ context.Context, l domain.PickupLocation) error {
	p.m[l.ID] = l
	return nil
}

func (p *memPickups) GetLocation(_ context.Context, id string) (domain.PickupLocation, error) {
	l, ok := p.m[id]
	if !ok {
		return domain.PickupLocation{}, domain.ErrNotFound
	}
	return l, nil
}

func (p *memPickups) ListLocations(_ context.Context, owner, _ string, activeOnly bool) ([]domain.PickupLocation, error) {
	out := []domain.PickupLocation{}
	for _, l := range p.m {
		if (owner == "" || l.OwnerOrg == owner) && (!activeOnly || l.Active) {
			out = append(out, l)
		}
	}
	return out, nil
}

func TestManagedLocationsIncludeInactiveOnesOfTheActorOnly(t *testing.T) {
	hours := []domain.OpeningHours{{Weekday: time.Monday, Open: "09:00", Close: "17:00"}}
	addr := domain.Address{Line1: "1 Main St", City: "Houston", State: "TX", PostalCode: "77002"}
	repo := &memPickups{m: map[string]domain.PickupLocation{
		"a": {ID: "a", Kind: domain.PickupSeller, OwnerOrg: "org_1", Name: "Store", Address: addr, Timezone: "America/Chicago", Hours: hours, Active: false},
		"b": {ID: "b", Kind: domain.PickupSeller, OwnerOrg: "org_2", Name: "Other", Address: addr, Timezone: "America/Chicago", Hours: hours, Active: true},
		"c": {ID: "c", Kind: domain.PickupCounter, OwnerOrg: "afrotransact", Name: "Counter", Address: addr, Timezone: "America/Chicago", Hours: hours, Active: false},
	}}
	svc := app.NewPickupService(app.Carriers{}, allAccounts(), repo, newMemStore(), time.Now)
	ctx := context.Background()
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}
	ls, err := svc.ManagedLocations(ctx, seller, "")
	if err != nil || len(ls) != 1 || ls[0].ID != "a" {
		t.Fatalf("seller sees its own inactive location only: %+v %v", ls, err)
	}
	if _, err := svc.ManagedLocations(ctx, seller, "org_2"); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("another org's locations: %v", err)
	}
	staff := auth.Actor{Type: "STAFF", Permissions: []string{app.PermAdmin}}
	if ls, err := svc.ManagedLocations(ctx, staff, ""); err != nil || len(ls) != 3 {
		t.Fatalf("staff see every location: %d %v", len(ls), err)
	}
	if _, err := svc.ManagedLocations(ctx, auth.Actor{Type: "CUSTOMER"}, ""); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("customers cannot list managed locations: %v", err)
	}
}

func TestPickupLocationPositionIsValidated(t *testing.T) {
	loc := domain.PickupLocation{Kind: domain.PickupCounter, OwnerOrg: "afrotransact", Name: "Counter", Timezone: "America/Chicago",
		Address: domain.Address{Line1: "1 Main St", PostalCode: "77002"}, Hours: []domain.OpeningHours{{Weekday: time.Monday, Open: "09:00", Close: "17:00"}},
		Position: &domain.Position{Lat: 29.76, Lng: -95.37}}
	if err := loc.Validate(); err != nil {
		t.Fatal(err)
	}
	loc.Position = &domain.Position{Lat: 129, Lng: 0}
	if err := loc.Validate(); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("bad latitude accepted: %v", err)
	}
}
