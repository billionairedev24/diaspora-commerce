package kafka

import (
	"context"
	"fmt"

	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

type writerImpl struct {
	cluster *Cluster
}

var _ port.RecordWriter = (*writerImpl)(nil)

// NewWriter returns a RecordWriter. franz-go produces idempotently with acks=all by default.
func NewWriter(c *Cluster) port.RecordWriter { return &writerImpl{cluster: c} }

func (w *writerImpl) Write(ctx context.Context, rec domain.Record) error {
	headers := make([]kgo.RecordHeader, 0, len(rec.Headers))
	for _, h := range rec.Headers {
		headers = append(headers, kgo.RecordHeader{Key: h.Key, Value: h.Value})
	}
	out := &kgo.Record{Topic: rec.Topic, Key: rec.Key, Value: rec.Value, Headers: headers}
	if err := w.cluster.client.ProduceSync(ctx, out).FirstErr(); err != nil {
		return fmt.Errorf("produce to %s: %w", rec.Topic, err)
	}
	return nil
}
