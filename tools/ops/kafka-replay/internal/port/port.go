// Package port declares what the use cases need from the outside world. Adapters implement it; tests fake it.
package port

import (
	"context"
	"errors"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
)

// ErrTopicNotFound is returned by RecordReader when the topic does not exist.
var ErrTopicNotFound = errors.New("topic not found")

// ErrStop is returned by a RecordReader callback to end the scan early without an error.
var ErrStop = errors.New("stop reading")

// RecordReader scans a topic from its earliest offset up to the offsets that were current when the scan began.
type RecordReader interface {
	// Read calls fn for each record, partition by partition and in offset order within a partition. fn may return
	// ErrStop to finish early. It never joins a consumer group and never commits offsets.
	Read(ctx context.Context, topic string, fn func(domain.Record) error) error
}

// RecordWriter produces records synchronously and returns once the broker acknowledged them (acks=all).
type RecordWriter interface {
	Write(ctx context.Context, rec domain.Record) error
}

// LedgerEntry records that a DLQ record was replayed.
type LedgerEntry struct {
	DLQTopic    string    `json:"dlq_topic"`
	Partition   int32     `json:"partition"`
	Offset      int64     `json:"offset"`
	EventID     string    `json:"event_id,omitempty"`
	EventType   string    `json:"event_type,omitempty"`
	TargetTopic string    `json:"target_topic"`
	Key         string    `json:"key,omitempty"`
	ReplayedAt  time.Time `json:"replayed_at"`
	ReplayedBy  string    `json:"replayed_by"`
}

// ID returns the DLQ record the entry is about.
func (e LedgerEntry) ID() domain.RecordID {
	return domain.RecordID{Topic: e.DLQTopic, Partition: e.Partition, Offset: e.Offset}
}

// Ledger remembers which DLQ records were replayed so they are not replayed twice.
type Ledger interface {
	Replayed(ctx context.Context, id domain.RecordID) (bool, error)
	Append(ctx context.Context, entry LedgerEntry) error
}

// GroupInspector reports the state of a consumer group.
type GroupInspector interface {
	// State returns the broker's group state: Empty, Stable, PreparingRebalance, CompletingRebalance or Dead.
	// A group the broker does not know is reported as Dead.
	State(ctx context.Context, group string) (GroupState, error)
}

// GroupState is the broker's view of a consumer group.
type GroupState struct {
	State   string
	Members int
}

// Inactive reports whether no consumer is attached, so offsets may be reset.
func (g GroupState) Inactive() bool { return g.State == "Empty" || g.State == "Dead" }

// CommandRunner runs an external program (kafka-consumer-groups.sh).
type CommandRunner interface {
	Run(ctx context.Context, name string, args ...string) ([]byte, error)
}

// Clock supplies the time and sleeps; faked in tests.
type Clock interface {
	Now() time.Time
	Sleep(ctx context.Context, d time.Duration) error
}
