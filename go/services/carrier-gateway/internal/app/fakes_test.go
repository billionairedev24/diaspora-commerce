package app_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

var quietLog = slog.New(slog.NewTextHandler(io.Discard, nil))

// fakeCarrier is a scriptable Carrier (+ WebhookReceiver verifying an "X-Sig: ok" header).
type fakeCarrier struct {
	code       domain.CarrierCode
	delay      time.Duration
	rateErr    error
	quotes     []domain.Quote
	buyErr     error
	rateCalls  atomic.Int32
	buyCalls   atomic.Int32
	voidCalls  atomic.Int32
	track      []domain.TrackingEvent
	trackErr   error
	subscribed []string
}

var (
	_ port.Carrier            = (*fakeCarrier)(nil)
	_ port.WebhookReceiver    = (*fakeCarrier)(nil)
	_ port.TrackingSubscriber = (*fakeCarrier)(nil)
)

func (f *fakeCarrier) Code() domain.CarrierCode { return f.code }

func (f *fakeCarrier) Rate(ctx context.Context, s domain.Shipment) ([]domain.Quote, error) {
	f.rateCalls.Add(1)
	if f.delay > 0 {
		select {
		case <-time.After(f.delay):
		case <-ctx.Done():
			return nil, &domain.CarrierError{Carrier: f.code, Message: ctx.Err().Error(), Kind: domain.ErrCarrierUnavailable}
		}
	}
	if f.rateErr != nil {
		return nil, f.rateErr
	}
	out := make([]domain.Quote, len(f.quotes))
	copy(out, f.quotes)
	for i := range out {
		out[i].Negotiated = s.Account.Negotiated
	}
	return out, nil
}

func (f *fakeCarrier) BuyLabel(_ context.Context, s domain.Shipment, svc domain.ServiceCode) (domain.Label, error) {
	n := f.buyCalls.Add(1)
	if f.buyErr != nil {
		return domain.Label{}, f.buyErr
	}
	return domain.Label{Carrier: f.code, Service: svc, TrackingNumber: fmt.Sprintf("TRK%d-%s", n, s.Reference), CostMinor: 999,
		Currency: "USD", Format: domain.FormatPDF, Data: []byte("%PDF")}, nil
}

func (f *fakeCarrier) Void(context.Context, string) error { f.voidCalls.Add(1); return nil }

func (f *fakeCarrier) Track(context.Context, string) ([]domain.TrackingEvent, error) {
	return f.track, f.trackErr
}

func (f *fakeCarrier) SchedulePickup(_ context.Context, p domain.PickupRequest) (domain.Pickup, error) {
	return domain.Pickup{Carrier: f.code, Confirmation: "CONF1", Date: p.Date}, nil
}

func (f *fakeCarrier) ValidateAddress(context.Context, domain.Address) (domain.AddressValidation, error) {
	return domain.AddressValidation{Carrier: f.code, Valid: true}, nil
}

func (f *fakeCarrier) VerifyWebhook(h http.Header, _ []byte) error {
	if h.Get("X-Sig") != "ok" {
		return domain.ErrSignature
	}
	return nil
}

func (f *fakeCarrier) ParseWebhook([]byte) ([]domain.TrackingEvent, error) { return f.track, nil }

func (f *fakeCarrier) Subscribe(_ context.Context, tns []string) error {
	f.subscribed = append(f.subscribed, tns...)
	return nil
}

type fakeAccounts struct{ accts []domain.Account }

func (f fakeAccounts) Accounts(context.Context, string) ([]domain.Account, error) {
	return f.accts, nil
}

type memCache struct {
	mu   sync.Mutex
	m    map[string][]domain.Quote
	puts int
}

func (c *memCache) Get(_ context.Context, k string) ([]domain.Quote, bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	q, ok := c.m[k]
	return q, ok, nil
}

func (c *memCache) Put(_ context.Context, k string, q []domain.Quote, _ time.Duration) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = map[string][]domain.Quote{}
	}
	c.m[k] = q
	c.puts++
	return nil
}

type memObjects struct {
	mu sync.Mutex
	m  map[string][]byte
}

func (o *memObjects) Put(_ context.Context, k, _ string, d []byte) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.m == nil {
		o.m = map[string][]byte{}
	}
	o.m[k] = d
	return nil
}

func (o *memObjects) Get(_ context.Context, k string) ([]byte, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	d, ok := o.m[k]
	if !ok {
		return nil, domain.ErrNotFound
	}
	return d, nil
}

func (o *memObjects) SignedURL(context.Context, string, time.Duration) (string, error) {
	return "", nil
}

// memStore implements LabelRepository, TrackingStore and Outbox in memory with all-or-nothing transactions.
type memStore struct {
	mu        sync.Mutex
	labels    map[string]domain.LabelRecord
	keys      map[string]string
	shipments map[string]domain.TrackedShipment
	events    map[string]domain.TrackingEvent
	outbox    []events.Envelope
	polls     map[string]time.Time
	failures  map[string]int
}

func newMemStore() *memStore {
	return &memStore{labels: map[string]domain.LabelRecord{}, keys: map[string]string{}, shipments: map[string]domain.TrackedShipment{},
		events: map[string]domain.TrackingEvent{}, polls: map[string]time.Time{}, failures: map[string]int{}}
}

func skey(c domain.CarrierCode, tn string) string { return string(c) + "/" + tn }

func (m *memStore) Reserve(_ context.Context, rec domain.LabelRecord) (domain.LabelRecord, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if id, ok := m.keys[rec.IdempotencyKey]; ok {
		return m.labels[id], false, nil
	}
	m.keys[rec.IdempotencyKey] = rec.ID
	m.labels[rec.ID] = rec
	return rec, true, nil
}

func (m *memStore) Complete(_ context.Context, rec domain.LabelRecord, purchased events.Envelope) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.labels[rec.ID] = rec
	m.outbox = append(m.outbox, purchased)
	m.shipments[skey(rec.Carrier, rec.TrackingNumber)] = domain.TrackedShipment{Carrier: rec.Carrier, TrackingNumber: rec.TrackingNumber,
		LabelID: rec.ID, Reference: rec.Reference, State: domain.StateLabelCreated, Active: true}
	return nil
}

func (m *memStore) Release(_ context.Context, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.keys, m.labels[id].IdempotencyKey)
	delete(m.labels, id)
	return nil
}

func (m *memStore) ByTracking(_ context.Context, carrier domain.CarrierCode, trackingNumber string) (domain.LabelRecord, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, l := range m.labels {
		if l.Carrier == carrier && l.TrackingNumber == trackingNumber && l.Status == domain.LabelPurchased {
			return l, nil
		}
	}
	return domain.LabelRecord{}, domain.ErrNotFound
}

func (m *memStore) Get(_ context.Context, id string) (domain.LabelRecord, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	l, ok := m.labels[id]
	if !ok {
		return domain.LabelRecord{}, domain.ErrNotFound
	}
	if sh, ok := m.shipments[skey(l.Carrier, l.TrackingNumber)]; ok {
		l.TrackingState = sh.State
	}
	return l, nil
}

func (m *memStore) MarkVoided(_ context.Context, id string, at time.Time, voided events.Envelope) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	l := m.labels[id]
	if l.Status != domain.LabelPurchased {
		return fmt.Errorf("%w: label is not purchased", domain.ErrConflict)
	}
	l.Status, l.VoidedAt = domain.LabelVoided, &at
	m.labels[id] = l
	m.outbox = append(m.outbox, voided)
	return nil
}

func (m *memStore) Ping(context.Context) error { return nil }

type memTx struct{ m *memStore }

func (t memTx) InsertEvent(_ context.Context, ev domain.TrackingEvent) (bool, error) {
	k := string(ev.Carrier) + "/" + ev.EventID
	if _, ok := t.m.events[k]; ok {
		return false, nil
	}
	t.m.events[k] = ev
	return true, nil
}

func (t memTx) LockShipment(_ context.Context, c domain.CarrierCode, tn string) (domain.TrackedShipment, bool, error) {
	sh, ok := t.m.shipments[skey(c, tn)]
	return sh, ok, nil
}

func (t memTx) SaveShipment(_ context.Context, sh domain.TrackedShipment) error {
	t.m.shipments[skey(sh.Carrier, sh.TrackingNumber)] = sh
	return nil
}

func (t memTx) Enqueue(_ context.Context, e events.Envelope) error {
	t.m.outbox = append(t.m.outbox, e)
	return nil
}

func (m *memStore) InTx(ctx context.Context, fn func(ctx context.Context, tx port.TrackingTx) error) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	snapEvents := map[string]domain.TrackingEvent{}
	for k, v := range m.events {
		snapEvents[k] = v
	}
	snapShip := map[string]domain.TrackedShipment{}
	for k, v := range m.shipments {
		snapShip[k] = v
	}
	n := len(m.outbox)
	if err := fn(ctx, memTx{m: m}); err != nil {
		m.events, m.shipments, m.outbox = snapEvents, snapShip, m.outbox[:n]
		return err
	}
	return nil
}

func (m *memStore) Register(_ context.Context, t domain.TrackedShipment) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.shipments[skey(t.Carrier, t.TrackingNumber)]; !ok {
		m.shipments[skey(t.Carrier, t.TrackingNumber)] = t
	}
	return nil
}

func (m *memStore) GetShipment(_ context.Context, c domain.CarrierCode, tn string) (domain.TrackedShipment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	sh, ok := m.shipments[skey(c, tn)]
	if !ok {
		return domain.TrackedShipment{}, domain.ErrNotFound
	}
	return sh, nil
}

func (m *memStore) Events(_ context.Context, c domain.CarrierCode, tn string) ([]domain.TrackingEvent, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []domain.TrackingEvent
	for _, e := range m.events {
		if e.Carrier == c && e.TrackingNumber == tn {
			out = append(out, e)
		}
	}
	domain.SortEvents(out)
	return out, nil
}

func (m *memStore) ClaimDuePolls(_ context.Context, c domain.CarrierCode, now time.Time, lease time.Duration, limit int) ([]port.PollTarget, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []port.PollTarget
	for k, sh := range m.shipments {
		if sh.Carrier != c || !sh.Active || m.polls[k].After(now) || len(out) >= limit {
			continue
		}
		m.polls[k] = now.Add(lease)
		out = append(out, port.PollTarget{Carrier: c, TrackingNumber: sh.TrackingNumber, Failures: m.failures[k]})
	}
	return out, nil
}

func (m *memStore) SchedulePoll(_ context.Context, c domain.CarrierCode, tn string, next time.Time, failures int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.polls[skey(c, tn)], m.failures[skey(c, tn)] = next, failures
	return nil
}

func (m *memStore) Enqueue(_ context.Context, e events.Envelope) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.outbox = append(m.outbox, e)
	return nil
}

func (m *memStore) Dispatch(ctx context.Context, limit int, publish func(context.Context, events.Envelope) error) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for len(m.outbox) > 0 && n < limit {
		if err := publish(ctx, m.outbox[0]); err != nil {
			return n, err
		}
		m.outbox = m.outbox[1:]
		n++
	}
	return n, nil
}

func (m *memStore) outboxLen() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.outbox)
}

var errBoom = errors.New("boom")
