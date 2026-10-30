package app_test

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

// fakeKafka is an in-memory cluster implementing the reader and writer ports.
type fakeKafka struct {
	mu      sync.Mutex
	topics  map[string][]domain.Record
	written []domain.Record
	// failWriteAt makes the Nth write (1-based) fail. 0 never fails.
	failWriteAt int
	writes      int
}

var (
	_ port.RecordReader = (*fakeKafka)(nil)
	_ port.RecordWriter = (*fakeKafka)(nil)
)

func newFakeKafka() *fakeKafka { return &fakeKafka{topics: map[string][]domain.Record{}} }

func (f *fakeKafka) add(topic string, recs ...domain.Record) {
	for i := range recs {
		recs[i].Topic = topic
		recs[i].Offset = int64(len(f.topics[topic]))
		f.topics[topic] = append(f.topics[topic], recs[i])
	}
}

func (f *fakeKafka) Read(_ context.Context, topic string, fn func(domain.Record) error) error {
	recs, ok := f.topics[topic]
	if !ok {
		return port.ErrTopicNotFound
	}
	for _, r := range recs {
		if err := fn(r); err != nil {
			if errors.Is(err, port.ErrStop) {
				return nil
			}
			return err
		}
	}
	return nil
}

func (f *fakeKafka) Write(_ context.Context, rec domain.Record) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.writes++
	if f.failWriteAt > 0 && f.writes == f.failWriteAt {
		return errors.New("broker unavailable")
	}
	f.written = append(f.written, rec)
	return nil
}

// memLedger is an in-memory ledger.
type memLedger struct {
	entries []port.LedgerEntry
	failOn  bool
}

var _ port.Ledger = (*memLedger)(nil)

func (m *memLedger) Replayed(_ context.Context, id domain.RecordID) (bool, error) {
	for _, e := range m.entries {
		if e.ID() == id {
			return true, nil
		}
	}
	return false, nil
}

func (m *memLedger) Append(_ context.Context, e port.LedgerEntry) error {
	if m.failOn {
		return errors.New("disk full")
	}
	m.entries = append(m.entries, e)
	return nil
}

// fakeClock never really sleeps; it records what it was asked to wait.
type fakeClock struct {
	now    time.Time
	sleeps []time.Duration
}

var _ port.Clock = (*fakeClock)(nil)

func (c *fakeClock) Now() time.Time { return c.now }

func (c *fakeClock) Sleep(_ context.Context, d time.Duration) error {
	c.sleeps = append(c.sleeps, d)
	return nil
}

// dlqRecord builds a record as the Java dead-letter recoverer writes it.
func dlqRecord(key, eventID, eventType, original, group, failedAt string) domain.Record {
	h := func(k, v string) domain.Header { return domain.Header{Key: k, Value: []byte(v)} }
	return domain.Record{
		Key:       []byte(key),
		Value:     []byte(`{"id":"` + key + `","email":"a@example.com"}`),
		Timestamp: time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC),
		Headers: []domain.Header{
			h("event-id", eventID), h("event-type", eventType), h("occurred-at", "2026-03-01T00:00:00Z"),
			h("x-dlq-exception-class", "java.lang.IllegalStateException"),
			h("x-dlq-exception-message", "boom"),
			h("x-dlq-original-topic", original),
			h("x-dlq-original-partition", "1"), h("x-dlq-original-offset", "9"),
			h("x-dlq-consumer-group", group), h("x-dlq-failed-at", failedAt), h("x-dlq-attempts", "6"),
			h("kafka_dlt-original-topic", original),
		},
	}
}
