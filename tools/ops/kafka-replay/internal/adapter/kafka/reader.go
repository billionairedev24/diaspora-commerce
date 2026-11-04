package kafka

import (
	"context"
	"errors"
	"fmt"

	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

type readerImpl struct {
	cluster *Cluster
}

var _ port.RecordReader = (*readerImpl)(nil)

// NewReader returns a RecordReader that scans topics without a consumer group.
func NewReader(c *Cluster) port.RecordReader { return &readerImpl{cluster: c} }

type partitionRange struct{ start, end int64 }

func (r *readerImpl) Read(ctx context.Context, topic string, fn func(domain.Record) error) error {
	ranges, err := r.ranges(ctx, topic)
	if err != nil {
		return err
	}
	wanted := map[int32]kgo.Offset{}
	remaining := 0
	for p, rg := range ranges {
		if rg.end > rg.start {
			wanted[p] = kgo.NewOffset().At(rg.start)
			remaining++
		}
	}
	if remaining == 0 {
		return nil
	}
	opts, err := options(r.cluster.cfg)
	if err != nil {
		return err
	}
	opts = append(opts, kgo.ConsumePartitions(map[string]map[int32]kgo.Offset{topic: wanted}))
	consumer, err := kgo.NewClient(opts...)
	if err != nil {
		return fmt.Errorf("kafka consumer: %w", err)
	}
	defer consumer.Close()

	done := map[int32]bool{}
	for remaining > 0 {
		fetches := consumer.PollFetches(ctx)
		if ctx.Err() != nil {
			return fmt.Errorf("read %s: %w", topic, ctx.Err())
		}
		for _, fe := range fetches.Errors() {
			return fmt.Errorf("read %s/%d: %w", fe.Topic, fe.Partition, fe.Err)
		}
		var stop error
		fetches.EachRecord(func(rec *kgo.Record) {
			if stop != nil || done[rec.Partition] {
				return
			}
			if err := fn(toDomain(rec)); err != nil {
				stop = err
				return
			}
			if rec.Offset >= ranges[rec.Partition].end-1 {
				done[rec.Partition] = true
				remaining--
			}
		})
		if stop != nil {
			if errors.Is(stop, port.ErrStop) {
				return nil
			}
			return stop
		}
	}
	return nil
}

// ranges returns [earliest, end) for every partition of the topic as of now.
func (r *readerImpl) ranges(ctx context.Context, topic string) (map[int32]partitionRange, error) {
	meta := kmsg.NewPtrMetadataRequest()
	mt := kmsg.NewMetadataRequestTopic()
	mt.Topic = kmsg.StringPtr(topic)
	meta.Topics = append(meta.Topics, mt)
	mresp, err := meta.RequestWith(ctx, r.cluster.client)
	if err != nil {
		return nil, fmt.Errorf("metadata for %s: %w", topic, err)
	}
	if len(mresp.Topics) != 1 {
		return nil, fmt.Errorf("metadata for %s: unexpected answer", topic)
	}
	if code := kerr.ErrorForCode(mresp.Topics[0].ErrorCode); code != nil {
		if errors.Is(code, kerr.UnknownTopicOrPartition) {
			return nil, fmt.Errorf("%s: %w", topic, port.ErrTopicNotFound)
		}
		return nil, fmt.Errorf("metadata for %s: %w", topic, code)
	}
	partitions := make([]int32, 0, len(mresp.Topics[0].Partitions))
	for _, p := range mresp.Topics[0].Partitions {
		partitions = append(partitions, p.Partition)
	}
	earliest, err := r.offsets(ctx, topic, partitions, -2)
	if err != nil {
		return nil, err
	}
	latest, err := r.offsets(ctx, topic, partitions, -1)
	if err != nil {
		return nil, err
	}
	out := make(map[int32]partitionRange, len(partitions))
	for _, p := range partitions {
		out[p] = partitionRange{start: earliest[p], end: latest[p]}
	}
	return out, nil
}

// offsets asks for the offset at a timestamp: -2 is the earliest, -1 the log end.
func (r *readerImpl) offsets(ctx context.Context, topic string, partitions []int32, timestamp int64) (map[int32]int64, error) {
	req := kmsg.NewPtrListOffsetsRequest()
	req.ReplicaID = -1
	t := kmsg.NewListOffsetsRequestTopic()
	t.Topic = topic
	for _, p := range partitions {
		rp := kmsg.NewListOffsetsRequestTopicPartition()
		rp.Partition = p
		rp.Timestamp = timestamp
		t.Partitions = append(t.Partitions, rp)
	}
	req.Topics = append(req.Topics, t)
	resp, err := req.RequestWith(ctx, r.cluster.client)
	if err != nil {
		return nil, fmt.Errorf("list offsets for %s: %w", topic, err)
	}
	out := map[int32]int64{}
	for _, rt := range resp.Topics {
		for _, rp := range rt.Partitions {
			if code := kerr.ErrorForCode(rp.ErrorCode); code != nil {
				return nil, fmt.Errorf("list offsets for %s/%d: %w", topic, rp.Partition, code)
			}
			out[rp.Partition] = rp.Offset
		}
	}
	return out, nil
}

func toDomain(r *kgo.Record) domain.Record {
	headers := make([]domain.Header, 0, len(r.Headers))
	for _, h := range r.Headers {
		headers = append(headers, domain.Header{Key: h.Key, Value: h.Value})
	}
	return domain.Record{
		Topic: r.Topic, Partition: r.Partition, Offset: r.Offset, Timestamp: r.Timestamp,
		Key: r.Key, Value: r.Value, Headers: headers,
	}
}
