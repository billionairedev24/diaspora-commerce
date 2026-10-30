// Package domain holds the types the replay tool works with. It imports no Kafka client.
package domain

import (
	"fmt"
	"strings"
	"time"
)

// Header names. The first three are written by the outbox relay (java/libs/platform-events KafkaOutboxRelay and Go
// pkg/events) and must survive a replay unchanged so the consumers' inbox de-duplicates on event-id.
const (
	HeaderEventID    = "event-id"
	HeaderEventType  = "event-type"
	HeaderOccurredAt = "occurred-at"

	// Written by the Java dead-letter recoverer (EventDeadLetterRecoverer).
	HeaderDLQExceptionClass    = "x-dlq-exception-class"
	HeaderDLQExceptionMessage  = "x-dlq-exception-message"
	HeaderDLQOriginalTopic     = "x-dlq-original-topic"
	HeaderDLQOriginalPartition = "x-dlq-original-partition"
	HeaderDLQOriginalOffset    = "x-dlq-original-offset"
	HeaderDLQConsumerGroup     = "x-dlq-consumer-group"
	HeaderDLQFailedAt          = "x-dlq-failed-at"
	HeaderDLQAttempts          = "x-dlq-attempts"

	// Added by this tool.
	HeaderReplayedAt   = "x-replayed-at"
	HeaderReplayedBy   = "x-replayed-by"
	HeaderReplayedFrom = "x-replayed-from"

	dlqHeaderPrefix = "x-dlq-"
	// Spring Kafka's own dead-letter diagnostics, which the recoverer may also copy.
	springHeaderPrefix = "kafka_dlt-"
)

// Header is one Kafka record header.
type Header struct {
	Key   string
	Value []byte
}

// RecordID identifies a record on a topic.
type RecordID struct {
	Topic     string
	Partition int32
	Offset    int64
}

func (id RecordID) String() string {
	return fmt.Sprintf("%s/%d/%d", id.Topic, id.Partition, id.Offset)
}

// Record is a Kafka record as read from or written to a topic.
type Record struct {
	Topic     string
	Partition int32
	Offset    int64
	Timestamp time.Time
	Key       []byte
	Value     []byte
	Headers   []Header
}

// ID returns the record's position.
func (r Record) ID() RecordID {
	return RecordID{Topic: r.Topic, Partition: r.Partition, Offset: r.Offset}
}

// HeaderValue returns the last header with the given name, or "" when absent.
func (r Record) HeaderValue(name string) string {
	for i := len(r.Headers) - 1; i >= 0; i-- {
		if r.Headers[i].Key == name {
			return string(r.Headers[i].Value)
		}
	}
	return ""
}

// EventType is the event-type header.
func (r Record) EventType() string { return r.HeaderValue(HeaderEventType) }

// EventID is the event-id header.
func (r Record) EventID() string { return r.HeaderValue(HeaderEventID) }

// OriginalTopic is where the consumer got the record from, hence where a replay sends it.
func (r Record) OriginalTopic() string { return r.HeaderValue(HeaderDLQOriginalTopic) }

// ConsumerGroup is the group whose consumer dead-lettered the record.
func (r Record) ConsumerGroup() string { return r.HeaderValue(HeaderDLQConsumerGroup) }

// ExceptionClass is the class recorded by the consumer.
func (r Record) ExceptionClass() string { return r.HeaderValue(HeaderDLQExceptionClass) }

// FailedAt is when the record was dead-lettered: the x-dlq-failed-at header, else the record timestamp.
func (r Record) FailedAt() time.Time {
	if v := r.HeaderValue(HeaderDLQFailedAt); v != "" {
		if t, err := time.Parse(time.RFC3339Nano, v); err == nil {
			return t
		}
	}
	return r.Timestamp
}

// ReplayHeaders returns the headers a replayed record carries: everything the original had, without the DLQ
// diagnostics, plus the replay markers. The event-id and event-type headers are never touched.
func (r Record) ReplayHeaders(at time.Time, by string) []Header {
	out := make([]Header, 0, len(r.Headers)+3)
	for _, h := range r.Headers {
		switch {
		case strings.HasPrefix(h.Key, dlqHeaderPrefix), strings.HasPrefix(h.Key, springHeaderPrefix):
			continue
		case h.Key == HeaderReplayedAt, h.Key == HeaderReplayedBy, h.Key == HeaderReplayedFrom:
			continue
		}
		out = append(out, h)
	}
	return append(out,
		Header{Key: HeaderReplayedAt, Value: []byte(at.UTC().Format(time.RFC3339Nano))},
		Header{Key: HeaderReplayedBy, Value: []byte(by)},
		Header{Key: HeaderReplayedFrom, Value: []byte(r.ID().String())},
	)
}
