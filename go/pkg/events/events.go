// Package events defines the platform event envelope and the publisher/consumer ports used by Go services.
// Topics follow at.<aggregate>.events, keyed by aggregate id; headers carry event-id, event-type and occurred-at
// (identical to the Java outbox relay).
package events

import (
	"context"
	"time"
)

// Envelope is one event read from or written to the bus.
type Envelope struct {
	ID          string
	Type        string
	Aggregate   string
	AggregateID string
	OccurredAt  time.Time
	Payload     []byte
}

// Topic returns the Kafka topic for the envelope's aggregate.
func (e Envelope) Topic() string { return "at." + e.Aggregate + ".events" }

// Publisher publishes events; implementations must be idempotent per Envelope.ID.
type Publisher interface {
	Publish(ctx context.Context, e Envelope) error
	Close()
}

// Handler processes one event. Returning an error stops the partition and the event is redelivered.
type Handler interface {
	Handle(ctx context.Context, e Envelope) error
}

// HandlerFunc adapts a function to Handler.
type HandlerFunc func(ctx context.Context, e Envelope) error

// Handle calls f.
func (f HandlerFunc) Handle(ctx context.Context, e Envelope) error { return f(ctx, e) }

// Consumer runs a consumer group until the context is cancelled.
type Consumer interface {
	Run(ctx context.Context, h Handler) error
	Close()
}

// Inbox de-duplicates redelivered events per consumer (effectively-once processing).
type Inbox interface {
	// FirstDelivery records the event id and reports whether this is its first delivery.
	FirstDelivery(ctx context.Context, consumer, eventID string) (bool, error)
}
