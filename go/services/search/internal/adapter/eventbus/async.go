// Package eventbus implements port.EventPublisher: a bounded in-memory queue drained by a background worker into the
// platform Kafka publisher, so analytics and ads events never add latency to a search or click.
package eventbus

import (
	"context"
	"log/slog"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// Publisher is the async publisher with its worker.
type Publisher interface {
	port.EventPublisher
	// Run drains the queue until ctx is done, then flushes what is left (bounded by a short timeout).
	Run(ctx context.Context) error
}

type asyncImpl struct {
	next    events.Publisher
	queue   chan events.Envelope
	log     *slog.Logger
	counter *prometheus.CounterVec
	retries int
}

var _ Publisher = (*asyncImpl)(nil)

// NewAsync returns an async publisher over next (nil: events are counted and dropped, e.g. Kafka not configured).
// counter is search_events_total{type,outcome} (published | dropped | failed).
func NewAsync(next events.Publisher, buffer int, counter *prometheus.CounterVec, log *slog.Logger) Publisher {
	return &asyncImpl{next: next, queue: make(chan events.Envelope, buffer), log: log, counter: counter, retries: 3}
}

func (a *asyncImpl) Publish(_ context.Context, e events.Envelope) {
	if a.next == nil {
		a.counter.WithLabelValues(e.Type, "dropped").Inc()
		return
	}
	select {
	case a.queue <- e:
	default:
		a.counter.WithLabelValues(e.Type, "dropped").Inc()
		a.log.Warn("event queue full; event dropped", "type", e.Type, "event_id", e.ID)
	}
}

func (a *asyncImpl) Run(ctx context.Context) error {
	if a.next == nil {
		<-ctx.Done()
		return nil
	}
	for {
		select {
		case e := <-a.queue:
			a.send(ctx, e)
		case <-ctx.Done():
			drain, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
			defer cancel()
			for {
				select {
				case e := <-a.queue:
					a.send(drain, e)
				default:
					return nil
				}
			}
		}
	}
}

// send publishes with a few retries; the event id is stable, so a retried duplicate is dropped by consumers.
func (a *asyncImpl) send(ctx context.Context, e events.Envelope) {
	backoff := 100 * time.Millisecond
	for attempt := 1; ; attempt++ {
		err := a.next.Publish(ctx, e)
		if err == nil {
			a.counter.WithLabelValues(e.Type, "published").Inc()
			return
		}
		if attempt >= a.retries || ctx.Err() != nil {
			a.counter.WithLabelValues(e.Type, "failed").Inc()
			a.log.Warn("event not published", "type", e.Type, "event_id", e.ID, "error", err)
			return
		}
		select {
		case <-time.After(backoff):
			backoff *= 2
		case <-ctx.Done():
		}
	}
}
