package app_test

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

type failedStore struct {
	mu   sync.Mutex
	rows []domain.FailedEvent
	err  error
}

func (s *failedStore) RecordFailedEvent(_ context.Context, f domain.FailedEvent) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err != nil {
		return s.err
	}
	s.rows = append(s.rows, f)
	return nil
}

type failureCount struct{ reasons []string }

func (c *failureCount) EventFailed(reason string) { c.reasons = append(c.reasons, reason) }

// scripted fails events by id with the given error, every time, and counts calls per id.
type scripted struct {
	fail  map[string]error
	calls map[string]int
}

func (s *scripted) Handle(_ context.Context, e events.Envelope) error {
	s.calls[e.ID]++
	return s.fail[e.ID]
}

func forbidden() error {
	return fmt.Errorf("handle: event e1: resolve recipient u1: %w", &domain.UpstreamError{Service: "identity",
		Path: "/v1/internal/users/u1/contact", Status: 403, Code: "identity.service-lookup-only",
		Detail: `{"detail":"Contact lookups need a service token with users:read"}`})
}

func fastPolicy() app.RetryPolicy {
	return app.RetryPolicy{Attempts: 3, Backoff: time.Nanosecond, MaxBackoff: time.Nanosecond, MaxRounds: 2}
}

func TestPermanentUpstreamErrorIsDeadLetteredAndTheConsumerMovesOn(t *testing.T) {
	next := &scripted{fail: map[string]error{"d3a264e4": forbidden()}, calls: map[string]int{}}
	store, counter := &failedStore{}, &failureCount{}
	h := app.NewResilientHandler(next, store, counter, fastPolicy(), quietLog())

	if err := h.Handle(context.Background(), events.Envelope{ID: "d3a264e4", Type: "OrderPlaced", Payload: []byte(`{"x":1}`)}); err != nil {
		t.Fatalf("a 403 must be dead-lettered and the offset committed (nil), got %v", err)
	}
	if next.calls["d3a264e4"] != 1 {
		t.Fatalf("permanent errors are not retried: %d calls", next.calls["d3a264e4"])
	}
	if len(store.rows) != 1 {
		t.Fatalf("%+v", store.rows)
	}
	r := store.rows[0]
	if r.EventID != "d3a264e4" || r.EventType != "OrderPlaced" || r.Reason != app.ReasonUpstreamClientError ||
		r.ErrorClass != "permanent" || r.Status != 403 || r.Code != "identity.service-lookup-only" || string(r.Payload) != `{"x":1}` {
		t.Fatalf("%+v", r)
	}
	if len(counter.reasons) != 1 || counter.reasons[0] != app.ReasonUpstreamClientError {
		t.Fatalf("metric: %v", counter.reasons)
	}
	// the next event is processed normally
	if err := h.Handle(context.Background(), events.Envelope{ID: "next"}); err != nil || next.calls["next"] != 1 {
		t.Fatalf("later events flow: %v", err)
	}
}

func TestTransientErrorIsRetriedWithinBoundsThenSucceeds(t *testing.T) {
	boom := &domain.UpstreamError{Service: "identity", Status: 503}
	var n int
	h := app.NewResilientHandler(events.HandlerFunc(func(context.Context, events.Envelope) error {
		n++
		if n < 3 {
			return boom
		}
		return nil
	}), &failedStore{}, &failureCount{}, fastPolicy(), quietLog())
	if err := h.Handle(context.Background(), events.Envelope{ID: "e"}); err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
}

func TestTransientFailureRestartsTheConsumerThenDeadLettersAfterMaxRounds(t *testing.T) {
	for name, cause := range map[string]error{
		"5xx":        &domain.UpstreamError{Service: "identity", Status: 500},
		"429":        &domain.UpstreamError{Service: "identity", Status: 429},
		"timeout":    context.DeadlineExceeded,
		"connection": errors.New("dial tcp 127.0.0.1:8100: connect: connection refused"),
	} {
		t.Run(name, func(t *testing.T) {
			next := &scripted{fail: map[string]error{"e": cause}, calls: map[string]int{}}
			store, counter := &failedStore{}, &failureCount{}
			h := app.NewResilientHandler(next, store, counter, fastPolicy(), quietLog())
			// round 1: attempts exhausted, error returned so the consumer restarts and redelivers
			if err := h.Handle(context.Background(), events.Envelope{ID: "e"}); err == nil {
				t.Fatal("round 1 must return the error (event stays in Kafka)")
			}
			if len(store.rows) != 0 {
				t.Fatal("not dead-lettered yet")
			}
			// round 2 = MaxRounds: dead-lettered, offset committed
			if err := h.Handle(context.Background(), events.Envelope{ID: "e"}); err != nil {
				t.Fatalf("round 2: %v", err)
			}
			if next.calls["e"] != 6 || len(store.rows) != 1 || store.rows[0].Reason != app.ReasonRetriesExhausted ||
				store.rows[0].ErrorClass != "transient" || counter.reasons[0] != app.ReasonRetriesExhausted {
				t.Fatalf("calls=%d rows=%+v metric=%v", next.calls["e"], store.rows, counter.reasons)
			}
		})
	}
}

func TestADifferentEventResetsTheRoundCount(t *testing.T) {
	next := &scripted{fail: map[string]error{"a": &domain.UpstreamError{Status: 500}}, calls: map[string]int{}}
	store := &failedStore{}
	h := app.NewResilientHandler(next, store, nil, fastPolicy(), quietLog())
	_ = h.Handle(context.Background(), events.Envelope{ID: "a"})
	_ = h.Handle(context.Background(), events.Envelope{ID: "b"}) // success resets
	if err := h.Handle(context.Background(), events.Envelope{ID: "a"}); err == nil || len(store.rows) != 0 {
		t.Fatalf("a starts again at round 1: %v %+v", err, store.rows)
	}
}

func TestWhenTheDeadLetterCannotBeWrittenTheOffsetIsNotCommitted(t *testing.T) {
	next := &scripted{fail: map[string]error{"e": forbidden()}, calls: map[string]int{}}
	store := &failedStore{err: errors.New("db down")}
	h := app.NewResilientHandler(next, store, &failureCount{}, fastPolicy(), quietLog())
	if err := h.Handle(context.Background(), events.Envelope{ID: "e"}); err == nil {
		t.Fatal("the event must stay uncommitted so it is not lost")
	}
}

func TestClassify(t *testing.T) {
	cases := []struct {
		err       error
		permanent bool
		reason    string
	}{
		{&domain.UpstreamError{Status: 403}, true, app.ReasonUpstreamClientError},
		{&domain.UpstreamError{Status: 400}, true, app.ReasonUpstreamClientError},
		{&domain.UpstreamError{Status: 401}, true, app.ReasonUpstreamClientError},
		{&domain.UpstreamError{Status: 408}, false, app.ReasonRetriesExhausted},
		{&domain.UpstreamError{Status: 429}, false, app.ReasonRetriesExhausted},
		{&domain.UpstreamError{Status: 502}, false, app.ReasonRetriesExhausted},
		{fmt.Errorf("wrapped: %w", domain.ErrInvalidRequest), true, app.ReasonRejected},
		{errors.New("pq: connection reset"), false, app.ReasonRetriesExhausted},
	}
	for _, c := range cases {
		if p, r := app.Classify(c.err); p != c.permanent || r != c.reason {
			t.Errorf("%v: got %v %s", c.err, p, r)
		}
	}
}
