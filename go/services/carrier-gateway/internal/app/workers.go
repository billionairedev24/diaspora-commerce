package app

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Dispatcher relays outbox rows to the event bus. Several replicas may run it: rows are locked with
// SKIP LOCKED, so each row is published by one replica at a time and only marked sent after the broker acked.
type Dispatcher interface {
	// RunOnce publishes one batch and returns how many rows were published.
	RunOnce(ctx context.Context) (int, error)
	// Run loops until ctx is cancelled.
	Run(ctx context.Context)
}

type dispatcherImpl struct {
	outbox    port.Outbox
	publisher events.Publisher
	batch     int
	interval  time.Duration
	log       *slog.Logger
}

var _ Dispatcher = (*dispatcherImpl)(nil)

// NewDispatcher returns an outbox Dispatcher.
func NewDispatcher(outbox port.Outbox, publisher events.Publisher, batch int, interval time.Duration, log *slog.Logger) Dispatcher {
	if batch <= 0 {
		batch = 100
	}
	if interval <= 0 {
		interval = time.Second
	}
	return &dispatcherImpl{outbox: outbox, publisher: publisher, batch: batch, interval: interval, log: log}
}

func (d *dispatcherImpl) RunOnce(ctx context.Context) (int, error) {
	n, err := d.outbox.Dispatch(ctx, d.batch, d.publisher.Publish)
	if err != nil {
		return n, fmt.Errorf("dispatch outbox: %w", err)
	}
	return n, nil
}

func (d *dispatcherImpl) Run(ctx context.Context) {
	t := time.NewTicker(d.interval)
	defer t.Stop()
	for {
		n, err := d.RunOnce(ctx)
		if err != nil && ctx.Err() == nil {
			d.log.WarnContext(ctx, "outbox dispatch failed", "error", err)
		}
		if n == d.batch {
			continue // backlog: drain without waiting
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// RunPoller polls due tracking numbers every tick until ctx is cancelled.
func RunPoller(ctx context.Context, svc TrackingService, tick time.Duration, log *slog.Logger) {
	t := time.NewTicker(tick)
	defer t.Stop()
	for {
		if _, err := svc.PollDue(ctx); err != nil && ctx.Err() == nil {
			log.WarnContext(ctx, "tracking poll run failed", "error", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
