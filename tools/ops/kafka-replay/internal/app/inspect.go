package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

// ErrRecordNotFound is returned by Show when the partition/offset holds no record.
var ErrRecordNotFound = errors.New("record not found")

// Inspector reads dead-letter topics without changing them.
type Inspector interface {
	// List summarises a DLQ topic.
	List(ctx context.Context, topic string, filter domain.Filter) (*domain.Summary, error)
	// Show returns one record. With redact the payload's string values are masked.
	Show(ctx context.Context, id domain.RecordID, redact bool) (domain.Record, error)
}

type inspectorImpl struct {
	reader port.RecordReader
}

var _ Inspector = (*inspectorImpl)(nil)

// NewInspector wires the list and show use cases.
func NewInspector(reader port.RecordReader) Inspector {
	return &inspectorImpl{reader: reader}
}

func (i *inspectorImpl) List(ctx context.Context, topic string, filter domain.Filter) (*domain.Summary, error) {
	summary := domain.NewSummary()
	err := i.reader.Read(ctx, topic, func(rec domain.Record) error {
		if filter.Matches(rec) {
			summary.Add(rec)
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("list %s: %w", topic, err)
	}
	return summary, nil
}

func (i *inspectorImpl) Show(ctx context.Context, id domain.RecordID, redact bool) (domain.Record, error) {
	var found domain.Record
	ok := false
	err := i.reader.Read(ctx, id.Topic, func(rec domain.Record) error {
		if rec.Partition == id.Partition && rec.Offset == id.Offset {
			found, ok = rec, true
			return port.ErrStop
		}
		return nil
	})
	if err != nil && !errors.Is(err, port.ErrStop) {
		return domain.Record{}, fmt.Errorf("show %s: %w", id, err)
	}
	if !ok {
		return domain.Record{}, fmt.Errorf("show %s: %w", id, ErrRecordNotFound)
	}
	if redact {
		found.Value = RedactPayload(found.Value)
	}
	return found, nil
}

// RedactPayload masks every string value in a JSON payload (keys and structure stay visible, so the shape of the
// event can still be diagnosed). Payloads that are not JSON are replaced by a size marker.
func RedactPayload(payload []byte) []byte {
	var doc any
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.UseNumber()
	if err := dec.Decode(&doc); err != nil {
		return []byte(fmt.Sprintf("<redacted: %d bytes, not JSON>", len(payload)))
	}
	out, err := json.Marshal(maskStrings(doc))
	if err != nil {
		return []byte(fmt.Sprintf("<redacted: %d bytes>", len(payload)))
	}
	return out
}

func maskStrings(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, val := range t {
			t[k] = maskStrings(val)
		}
		return t
	case []any:
		for idx, val := range t {
			t[idx] = maskStrings(val)
		}
		return t
	case string:
		return "***"
	default:
		return v
	}
}

// PrintablePayload renders a payload for a terminal: UTF-8 text as is, anything else as a size marker.
func PrintablePayload(payload []byte) string {
	if utf8.Valid(payload) {
		return string(payload)
	}
	return fmt.Sprintf("<%d bytes, not UTF-8>", len(payload))
}
