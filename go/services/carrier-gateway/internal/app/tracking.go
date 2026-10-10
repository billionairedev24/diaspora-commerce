package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"slices"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// TrackingView is a tracked shipment with its scan history (oldest first).
type TrackingView struct {
	Shipment domain.TrackedShipment `json:"shipment"`
	Events   []domain.TrackingEvent `json:"events"`
	Live     bool                   `json:"live"` // fetched from the carrier, not yet tracked by us
}

// IngestResult counts what happened to a batch of carrier events.
type IngestResult struct {
	Accepted   int `json:"accepted"`
	Duplicates int `json:"duplicates"`
	Changed    int `json:"changed"`
}

// PollPolicy configures USPS (and any non-push carrier) polling.
type PollPolicy struct {
	Carriers   []domain.CarrierCode
	Interval   time.Duration // normal gap between polls of one tracking number
	MaxBackoff time.Duration // cap for the exponential backoff after failures
	Lease      time.Duration // how long a claimed row is hidden from other replicas
	Batch      int
}

// TrackingService ingests carrier tracking (webhooks and polling) and serves tracking reads.
type TrackingService interface {
	Track(ctx context.Context, carrier domain.CarrierCode, trackingNumber string) (TrackingView, error)
	// Register starts tracking a number that was not bought through us (e.g. a seller's own label).
	Register(ctx context.Context, carrier domain.CarrierCode, trackingNumber, reference string) (TrackingView, error)
	Ingest(ctx context.Context, evs []domain.TrackingEvent) (IngestResult, error)
	HandleWebhook(ctx context.Context, carrier domain.CarrierCode, header http.Header, body []byte) (IngestResult, error)
	// PollDue polls one batch of due tracking numbers per polled carrier; returns how many were polled.
	PollDue(ctx context.Context) (int, error)
}

type trackingServiceImpl struct {
	carriers Carriers
	store    port.TrackingStore
	policy   PollPolicy
	log      *slog.Logger
	now      func() time.Time
}

var _ TrackingService = (*trackingServiceImpl)(nil)

// NewTrackingService returns a TrackingService.
func NewTrackingService(carriers Carriers, store port.TrackingStore, policy PollPolicy, log *slog.Logger, now func() time.Time) TrackingService {
	if policy.Interval <= 0 {
		policy.Interval = 2 * time.Hour
	}
	if policy.MaxBackoff <= 0 {
		policy.MaxBackoff = 12 * time.Hour
	}
	if policy.Lease <= 0 {
		policy.Lease = 10 * time.Minute
	}
	if policy.Batch <= 0 {
		policy.Batch = 50
	}
	return &trackingServiceImpl{carriers: carriers, store: store, policy: policy, log: log, now: now}
}

func (s *trackingServiceImpl) Track(ctx context.Context, carrier domain.CarrierCode, tn string) (TrackingView, error) {
	ts, err := s.store.GetShipment(ctx, carrier, tn)
	switch {
	case err == nil:
		evs, err := s.store.Events(ctx, carrier, tn)
		if err != nil {
			return TrackingView{}, fmt.Errorf("tracking events: %w", err)
		}
		return TrackingView{Shipment: ts, Events: evs}, nil
	case !errors.Is(err, domain.ErrNotFound):
		return TrackingView{}, fmt.Errorf("tracked shipment: %w", err)
	}
	c, err := s.carriers.get(carrier)
	if err != nil {
		return TrackingView{}, err
	}
	evs, err := c.Track(ctx, tn)
	if err != nil {
		return TrackingView{}, fmt.Errorf("live tracking: %w", err)
	}
	live := domain.TrackedShipment{Carrier: carrier, TrackingNumber: tn, Active: true}
	for _, ev := range evs {
		live, _ = live.Apply(ev)
	}
	return TrackingView{Shipment: live, Events: evs, Live: true}, nil
}

func (s *trackingServiceImpl) Register(ctx context.Context, carrier domain.CarrierCode, tn, reference string) (TrackingView, error) {
	c, err := s.carriers.get(carrier)
	if err != nil {
		return TrackingView{}, err
	}
	if tn == "" {
		return TrackingView{}, fmt.Errorf("%w: tracking number is required", domain.ErrInvalid)
	}
	if err := s.store.Register(ctx, domain.TrackedShipment{Carrier: carrier, TrackingNumber: tn, Reference: reference,
		State: domain.StateLabelCreated, Active: true}); err != nil {
		return TrackingView{}, fmt.Errorf("register tracking: %w", err)
	}
	if sub, ok := c.(port.TrackingSubscriber); ok {
		if err := sub.Subscribe(ctx, []string{tn}); err != nil {
			return TrackingView{}, fmt.Errorf("subscribe: %w", err)
		}
	}
	if evs, err := c.Track(ctx, tn); err == nil {
		if _, err := s.Ingest(ctx, evs); err != nil {
			return TrackingView{}, err
		}
	} else {
		s.log.InfoContext(ctx, "initial track fetch failed; will rely on push/poll", "carrier", carrier, "error", err)
	}
	return s.Track(ctx, carrier, tn)
}

func (s *trackingServiceImpl) Ingest(ctx context.Context, evs []domain.TrackingEvent) (IngestResult, error) {
	sorted := slices.Clone(evs)
	domain.SortEvents(sorted)
	var res IngestResult
	for _, ev := range sorted {
		if ev.EventID == "" || ev.TrackingNumber == "" {
			continue
		}
		err := s.store.InTx(ctx, func(ctx context.Context, tx port.TrackingTx) error {
			inserted, err := tx.InsertEvent(ctx, ev)
			if err != nil {
				return fmt.Errorf("insert event: %w", err)
			}
			if !inserted {
				res.Duplicates++
				return nil
			}
			res.Accepted++
			cur, found, err := tx.LockShipment(ctx, ev.Carrier, ev.TrackingNumber)
			if err != nil {
				return fmt.Errorf("lock shipment: %w", err)
			}
			if !found {
				cur = domain.TrackedShipment{Carrier: ev.Carrier, TrackingNumber: ev.TrackingNumber, Active: true}
			}
			next, changed := cur.Apply(ev)
			applied := !next.LastEventAt.Equal(cur.LastEventAt) || next.LastSequence != cur.LastSequence
			if !applied && found {
				return nil // older than what we already applied: history only
			}
			if err := tx.SaveShipment(ctx, next); err != nil {
				return fmt.Errorf("save shipment: %w", err)
			}
			if changed {
				res.Changed++
			}
			env, err := trackingEnvelope(cur, next, changed, ev, s.now())
			if err != nil {
				return err
			}
			return tx.Enqueue(ctx, env)
		})
		if err != nil {
			return res, fmt.Errorf("ingest %s/%s: %w", ev.Carrier, ev.TrackingNumber, err)
		}
	}
	return res, nil
}

func trackingEnvelope(before, after domain.TrackedShipment, changed bool, ev domain.TrackingEvent, now time.Time) (events.Envelope, error) {
	payload, err := json.Marshal(domain.TrackingUpdated{
		Carrier: ev.Carrier, TrackingNumber: ev.TrackingNumber, LabelID: after.LabelID, Reference: after.Reference,
		State: after.State, PreviousState: before.State, StateChanged: changed, Event: ev,
	})
	if err != nil {
		return events.Envelope{}, fmt.Errorf("encode event: %w", err)
	}
	return events.Envelope{ID: uuid.NewString(), Type: domain.EventTrackingUpdated, Aggregate: domain.EventAggregate,
		AggregateID: string(ev.Carrier) + ":" + ev.TrackingNumber, OccurredAt: now.UTC(), Payload: payload}, nil
}

func (s *trackingServiceImpl) HandleWebhook(ctx context.Context, carrier domain.CarrierCode, h http.Header, body []byte) (IngestResult, error) {
	c, err := s.carriers.get(carrier)
	if err != nil {
		return IngestResult{}, err
	}
	rcv, ok := c.(port.WebhookReceiver)
	if !ok {
		return IngestResult{}, fmt.Errorf("%w: %s does not push tracking", domain.ErrUnsupported, carrier)
	}
	if err := rcv.VerifyWebhook(h, body); err != nil {
		return IngestResult{}, fmt.Errorf("verify %s webhook: %w", carrier, err)
	}
	evs, err := rcv.ParseWebhook(body)
	if err != nil {
		return IngestResult{}, fmt.Errorf("parse %s webhook: %w", carrier, err)
	}
	return s.Ingest(ctx, evs)
}

func (s *trackingServiceImpl) PollDue(ctx context.Context) (int, error) {
	polled := 0
	for _, code := range s.policy.Carriers {
		c, ok := s.carriers[code]
		if !ok {
			continue
		}
		now := s.now()
		targets, err := s.store.ClaimDuePolls(ctx, code, now, s.policy.Lease, s.policy.Batch)
		if err != nil {
			return polled, fmt.Errorf("claim %s polls: %w", code, err)
		}
		for _, t := range targets {
			polled++
			failures := 0
			evs, err := c.Track(ctx, t.TrackingNumber)
			if err == nil {
				_, err = s.Ingest(ctx, evs)
			}
			next := s.now().Add(s.policy.Interval)
			if err != nil {
				failures = t.Failures + 1
				next = s.now().Add(s.backoff(failures))
				s.log.WarnContext(ctx, "tracking poll failed", "carrier", code, "tracking", t.TrackingNumber, "failures", failures, "error", err)
			}
			if err := s.store.SchedulePoll(ctx, code, t.TrackingNumber, next, failures); err != nil {
				return polled, fmt.Errorf("schedule poll: %w", err)
			}
		}
	}
	return polled, nil
}

func (s *trackingServiceImpl) backoff(failures int) time.Duration {
	d := s.policy.Interval
	for range failures {
		d *= 2
		if d >= s.policy.MaxBackoff {
			return s.policy.MaxBackoff
		}
	}
	return d
}
