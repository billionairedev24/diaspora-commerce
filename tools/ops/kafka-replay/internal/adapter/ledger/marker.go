package ledger

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

// MarkerSuffix is appended to a DLQ topic to name its replay marker topic.
const MarkerSuffix = ".replayed"

type markerLedger struct {
	reader port.RecordReader
	writer port.RecordWriter
	topic  string

	once sync.Once
	seen map[domain.RecordID]struct{}
	err  error
}

var _ port.Ledger = (*markerLedger)(nil)

// NewMarker keeps the ledger in <dlqTopic>.replayed, so every operator sees it, not just one laptop's file.
// The topic is read once on first use; a missing topic counts as empty (it is created on first write if the broker
// auto-creates topics, otherwise create it first).
func NewMarker(reader port.RecordReader, writer port.RecordWriter, dlqTopic string) port.Ledger {
	return &markerLedger{reader: reader, writer: writer, topic: dlqTopic + MarkerSuffix}
}

func (m *markerLedger) load(ctx context.Context) {
	m.once.Do(func() {
		m.seen = map[domain.RecordID]struct{}{}
		err := m.reader.Read(ctx, m.topic, func(rec domain.Record) error {
			var e port.LedgerEntry
			if err := json.Unmarshal(rec.Value, &e); err != nil {
				return fmt.Errorf("marker record %s is not a ledger entry: %w", rec.ID(), err)
			}
			m.seen[e.ID()] = struct{}{}
			return nil
		})
		if err != nil && !errors.Is(err, port.ErrTopicNotFound) {
			m.err = fmt.Errorf("read marker topic %s: %w", m.topic, err)
		}
	})
}

func (m *markerLedger) Replayed(ctx context.Context, id domain.RecordID) (bool, error) {
	m.load(ctx)
	if m.err != nil {
		return false, m.err
	}
	_, ok := m.seen[id]
	return ok, nil
}

func (m *markerLedger) Append(ctx context.Context, e port.LedgerEntry) error {
	value, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("encode marker entry: %w", err)
	}
	key := e.DLQTopic + "/" + strconv.Itoa(int(e.Partition)) + "/" + strconv.FormatInt(e.Offset, 10)
	if err := m.writer.Write(ctx, domain.Record{Topic: m.topic, Key: []byte(key), Value: value}); err != nil {
		return fmt.Errorf("write marker topic %s: %w", m.topic, err)
	}
	m.load(ctx)
	if m.err == nil {
		m.seen[e.ID()] = struct{}{}
	}
	return nil
}
