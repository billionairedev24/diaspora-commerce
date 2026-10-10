package eventbus_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/eventbus"
)

type flakyPublisher struct {
	mu       sync.Mutex
	failures int
	got      []string
}

func (f *flakyPublisher) Publish(_ context.Context, e events.Envelope) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failures > 0 {
		f.failures--
		return errors.New("broker unavailable")
	}
	f.got = append(f.got, e.ID)
	return nil
}

func (f *flakyPublisher) Close() {}

func (f *flakyPublisher) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.got)
}

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func counter() *prometheus.CounterVec {
	return prometheus.NewCounterVec(prometheus.CounterOpts{Name: "t"}, []string{"type", "outcome"})
}

func TestEventsArePublishedWithRetriesAndDrainedOnShutdown(t *testing.T) {
	pub := &flakyPublisher{failures: 1}
	c := counter()
	bus := eventbus.NewAsync(pub, 10, c, quiet)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- bus.Run(ctx) }()
	for _, id := range []string{"a", "b", "c"} {
		bus.Publish(ctx, events.Envelope{ID: id, Type: "search.query.performed"})
	}
	deadline := time.Now().Add(5 * time.Second)
	for pub.count() < 3 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if pub.count() != 3 || testutil.ToFloat64(c.WithLabelValues("search.query.performed", "published")) != 3 {
		t.Fatalf("published %d", pub.count())
	}
}

func TestFullQueueAndMissingKafkaDropAndCount(t *testing.T) {
	c := counter()
	full := eventbus.NewAsync(&flakyPublisher{}, 1, c, quiet)
	full.Publish(context.Background(), events.Envelope{ID: "1", Type: "x"})
	full.Publish(context.Background(), events.Envelope{ID: "2", Type: "x"})
	none := eventbus.NewAsync(nil, 1, c, quiet)
	none.Publish(context.Background(), events.Envelope{ID: "3", Type: "x"})
	if got := testutil.ToFloat64(c.WithLabelValues("x", "dropped")); got != 2 {
		t.Fatalf("dropped = %v", got)
	}
}
