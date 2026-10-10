package events

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
)

type kafkaPublisher struct {
	client *kgo.Client
}

type kafkaConsumer struct {
	client *kgo.Client
}

var (
	_ Publisher = (*kafkaPublisher)(nil)
	_ Consumer  = (*kafkaConsumer)(nil)
)

// NewKafkaPublisher returns an idempotent producer (acks=all).
func NewKafkaPublisher(brokers []string) (Publisher, error) {
	c, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.RequiredAcks(kgo.AllISRAcks()))
	if err != nil {
		return nil, fmt.Errorf("kafka producer: %w", err)
	}
	return &kafkaPublisher{client: c}, nil
}

func (p *kafkaPublisher) Publish(ctx context.Context, e Envelope) error {
	rec := &kgo.Record{
		Topic: e.Topic(),
		Key:   []byte(e.AggregateID),
		Value: e.Payload,
		Headers: []kgo.RecordHeader{
			{Key: "event-id", Value: []byte(e.ID)},
			{Key: "event-type", Value: []byte(e.Type)},
			{Key: "occurred-at", Value: []byte(e.OccurredAt.UTC().Format(time.RFC3339Nano))},
		},
	}
	if err := p.client.ProduceSync(ctx, rec).FirstErr(); err != nil {
		return fmt.Errorf("publish %s: %w", e.Type, err)
	}
	return nil
}

func (p *kafkaPublisher) Close() { p.client.Close() }

// NewKafkaConsumer joins group and consumes topics; offsets are committed only after the handler succeeds.
func NewKafkaConsumer(brokers []string, group string, topics ...string) (Consumer, error) {
	c, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topics...),
		kgo.DisableAutoCommit(),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
	)
	if err != nil {
		return nil, fmt.Errorf("kafka consumer: %w", err)
	}
	return &kafkaConsumer{client: c}, nil
}

func (c *kafkaConsumer) Run(ctx context.Context, h Handler) error {
	for {
		fetches := c.client.PollFetches(ctx)
		select {
		case <-ctx.Done():
			return nil
		default:
		}
		if errs := fetches.Errors(); len(errs) > 0 {
			joined := make([]error, 0, len(errs))
			for _, fe := range errs {
				joined = append(joined, fmt.Errorf("%s/%d: %w", fe.Topic, fe.Partition, fe.Err))
			}
			return errors.Join(joined...)
		}
		var handleErr error
		fetches.EachRecord(func(r *kgo.Record) {
			if handleErr != nil {
				return
			}
			handleErr = h.Handle(ctx, toEnvelope(r))
		})
		if handleErr != nil {
			return fmt.Errorf("handle: %w", handleErr)
		}
		if err := c.client.CommitUncommittedOffsets(ctx); err != nil {
			return fmt.Errorf("commit: %w", err)
		}
	}
}

func (c *kafkaConsumer) Close() { c.client.Close() }

func toEnvelope(r *kgo.Record) Envelope {
	e := Envelope{AggregateID: string(r.Key), Payload: r.Value, OccurredAt: r.Timestamp}
	for _, h := range r.Headers {
		switch h.Key {
		case "event-id":
			e.ID = string(h.Value)
		case "event-type":
			e.Type = string(h.Value)
		case "occurred-at":
			if t, err := time.Parse(time.RFC3339Nano, string(h.Value)); err == nil {
				e.OccurredAt = t
			}
		}
	}
	return e
}
