// Package app holds the replay tool's use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

// ReplayOptions configure one replay run.
type ReplayOptions struct {
	// DLQTopic is the dead-letter topic to read, normally "<original topic>.dlq".
	DLQTopic string
	Filter   domain.Filter
	// TargetTopic overrides x-dlq-original-topic. The Go services' own DLQs (at.indexer.dlq.events,
	// at.authz-sync.dlq.events) carry no such header, so replaying them needs this.
	TargetTopic string
	// Max stops after this many records were replayed (or would be, in a dry run). 0 means no limit.
	Max int
	// Rate is the maximum number of records published per second. 0 means unlimited.
	Rate float64
	// Execute sends the records. Without it nothing is published and the ledger is not touched.
	Execute bool
	// Force replays records the ledger says were replayed already.
	Force bool
	// By is written to the x-replayed-by header and the ledger.
	By string
}

// ReplayAction says what happened to one matching record.
type ReplayAction string

// The possible outcomes for a matching record.
const (
	ActionReplayed          ReplayAction = "replayed"
	ActionWouldReplay       ReplayAction = "would-replay"
	ActionAlreadyReplayed   ReplayAction = "skipped-already-replayed"
	ActionNoOriginalTopic   ReplayAction = "skipped-no-original-topic"
	ActionPublishFailed     ReplayAction = "publish-failed"
	ActionLedgerWriteFailed ReplayAction = "published-ledger-write-failed"
)

// ReplayItem is one line of the replay report.
type ReplayItem struct {
	ID        domain.RecordID
	EventID   string
	EventType string
	Target    string
	Action    ReplayAction
}

// ReplayReport summarises a run, including a run that stopped on an error.
type ReplayReport struct {
	Scanned int
	Matched int
	Items   []ReplayItem
}

// Count returns how many items had the given action.
func (r ReplayReport) Count(a ReplayAction) int {
	n := 0
	for _, it := range r.Items {
		if it.Action == a {
			n++
		}
	}
	return n
}

// Replayer re-publishes dead-lettered records to their original topic.
type Replayer interface {
	Replay(ctx context.Context, opts ReplayOptions) (ReplayReport, error)
}

type replayerImpl struct {
	reader port.RecordReader
	writer port.RecordWriter
	ledger port.Ledger
	clock  port.Clock
}

var _ Replayer = (*replayerImpl)(nil)

// NewReplayer wires the replay use case.
func NewReplayer(reader port.RecordReader, writer port.RecordWriter, ledger port.Ledger, clock port.Clock) Replayer {
	return &replayerImpl{reader: reader, writer: writer, ledger: ledger, clock: clock}
}

func (r *replayerImpl) Replay(ctx context.Context, opts ReplayOptions) (ReplayReport, error) {
	var report ReplayReport
	if opts.DLQTopic == "" {
		return report, errors.New("replay: DLQ topic is required")
	}
	if opts.Execute && opts.By == "" {
		return report, errors.New("replay: --by (who is replaying) is required with --execute")
	}
	var interval time.Duration
	if opts.Rate > 0 {
		interval = time.Duration(float64(time.Second) / opts.Rate)
	}
	planned := 0
	published := 0

	err := r.reader.Read(ctx, opts.DLQTopic, func(rec domain.Record) error {
		report.Scanned++
		if !opts.Filter.Matches(rec) {
			return nil
		}
		report.Matched++
		item := ReplayItem{ID: rec.ID(), EventID: rec.EventID(), EventType: rec.EventType(), Target: rec.OriginalTopic()}
		if opts.TargetTopic != "" {
			item.Target = opts.TargetTopic
		}

		if item.Target == "" {
			item.Action = ActionNoOriginalTopic
			report.Items = append(report.Items, item)
			return nil
		}
		if !opts.Force {
			done, err := r.ledger.Replayed(ctx, rec.ID())
			if err != nil {
				return fmt.Errorf("check ledger for %s: %w", rec.ID(), err)
			}
			if done {
				item.Action = ActionAlreadyReplayed
				report.Items = append(report.Items, item)
				return nil
			}
		}
		if opts.Max > 0 && planned >= opts.Max {
			return port.ErrStop
		}
		planned++

		if !opts.Execute {
			item.Action = ActionWouldReplay
			report.Items = append(report.Items, item)
			return nil
		}
		if published > 0 && interval > 0 {
			if err := r.clock.Sleep(ctx, interval); err != nil {
				return fmt.Errorf("rate limit: %w", err)
			}
		}
		return r.publish(ctx, opts, rec, item, &report, &published)
	})
	if err != nil && !errors.Is(err, port.ErrStop) {
		return report, fmt.Errorf("replay from %s: %w", opts.DLQTopic, err)
	}
	return report, nil
}

func (r *replayerImpl) publish(ctx context.Context, opts ReplayOptions, rec domain.Record, item ReplayItem,
	report *ReplayReport, published *int) error {
	now := r.clock.Now()
	out := domain.Record{
		Topic:   item.Target,
		Key:     rec.Key,
		Value:   rec.Value,
		Headers: rec.ReplayHeaders(now, opts.By),
	}
	if err := r.writer.Write(ctx, out); err != nil {
		item.Action = ActionPublishFailed
		report.Items = append(report.Items, item)
		return fmt.Errorf("publish %s to %s (stopping; nothing after it was sent): %w", rec.ID(), item.Target, err)
	}
	*published++
	entry := port.LedgerEntry{
		DLQTopic: rec.Topic, Partition: rec.Partition, Offset: rec.Offset,
		EventID: item.EventID, EventType: item.EventType, TargetTopic: item.Target,
		Key: string(rec.Key), ReplayedAt: now, ReplayedBy: opts.By,
	}
	if err := r.ledger.Append(ctx, entry); err != nil {
		item.Action = ActionLedgerWriteFailed
		report.Items = append(report.Items, item)
		return fmt.Errorf("published %s but could not write the ledger (running again would send it twice; the "+
			"consumers de-duplicate on event-id): %w", rec.ID(), err)
	}
	item.Action = ActionReplayed
	report.Items = append(report.Items, item)
	return nil
}
