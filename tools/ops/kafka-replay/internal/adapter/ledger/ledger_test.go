package ledger_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/adapter/ledger"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

func entry(offset int64) port.LedgerEntry {
	return port.LedgerEntry{DLQTopic: "at.order.events.dlq", Partition: 1, Offset: offset, EventID: "e",
		EventType: "order.paid", TargetTopic: "at.order.events", ReplayedAt: time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC),
		ReplayedBy: "ada"}
}

func id(offset int64) domain.RecordID {
	return domain.RecordID{Topic: "at.order.events.dlq", Partition: 1, Offset: offset}
}

func TestFileLedgerSurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ledger.jsonl")
	l, err := ledger.NewFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := l.Append(context.Background(), entry(7)); err != nil {
		t.Fatal(err)
	}
	if done, _ := l.Replayed(context.Background(), id(7)); !done {
		t.Fatal("not recorded")
	}
	if done, _ := l.Replayed(context.Background(), id(8)); done {
		t.Fatal("another offset reported as replayed")
	}
	if err := l.Close(); err != nil {
		t.Fatal(err)
	}

	again, err := ledger.NewFile(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = again.Close() }()
	if done, _ := again.Replayed(context.Background(), id(7)); !done {
		t.Fatal("ledger forgot the entry after reopening")
	}
	raw, _ := os.ReadFile(path)
	if strings.Count(string(raw), "\n") != 1 || !strings.Contains(string(raw), `"replayed_by":"ada"`) {
		t.Fatalf("file = %q", raw)
	}
}

func TestFileLedgerRefusesACorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ledger.jsonl")
	if err := os.WriteFile(path, []byte("{\"dlq_topic\":\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ledger.NewFile(path); err == nil {
		t.Fatal("a corrupt ledger must not be silently treated as empty")
	}
}

type topicStore struct {
	topics map[string][]domain.Record
}

func (s *topicStore) Read(_ context.Context, topic string, fn func(domain.Record) error) error {
	recs, ok := s.topics[topic]
	if !ok {
		return port.ErrTopicNotFound
	}
	for _, r := range recs {
		if err := fn(r); err != nil {
			return err
		}
	}
	return nil
}

func (s *topicStore) Write(_ context.Context, rec domain.Record) error {
	s.topics[rec.Topic] = append(s.topics[rec.Topic], rec)
	return nil
}

func TestMarkerLedgerUsesTheReplayedTopic(t *testing.T) {
	store := &topicStore{topics: map[string][]domain.Record{}}
	l := ledger.NewMarker(store, store, "at.order.events.dlq")

	if done, err := l.Replayed(context.Background(), id(3)); err != nil || done {
		t.Fatalf("missing marker topic should be empty: %v %v", done, err)
	}
	if err := l.Append(context.Background(), entry(3)); err != nil {
		t.Fatal(err)
	}
	if done, _ := l.Replayed(context.Background(), id(3)); !done {
		t.Fatal("entry not visible to the same process")
	}
	if len(store.topics["at.order.events.dlq.replayed"]) != 1 {
		t.Fatalf("topics = %v", store.topics)
	}

	// A different operator starts fresh and reads the marker topic.
	other := ledger.NewMarker(store, store, "at.order.events.dlq")
	if done, _ := other.Replayed(context.Background(), id(3)); !done {
		t.Fatal("marker topic not honoured by another operator")
	}
}

type failingLedger struct{}

func (failingLedger) Replayed(context.Context, domain.RecordID) (bool, error) { return false, nil }
func (failingLedger) Append(context.Context, port.LedgerEntry) error          { return errors.New("nope") }

func TestMultiLedgerReportsReplayedIfAnyLedgerSaysSoAndStopsOnWriteFailure(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ledger.jsonl")
	file, err := ledger.NewFile(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = file.Close() }()
	if err := file.Append(context.Background(), entry(1)); err != nil {
		t.Fatal(err)
	}
	multi := ledger.NewMulti(failingLedger{}, file)
	if done, _ := multi.Replayed(context.Background(), id(1)); !done {
		t.Fatal("second ledger's entry ignored")
	}
	if err := multi.Append(context.Background(), entry(2)); err == nil {
		t.Fatal("a failing ledger must fail the append")
	}
	if done, _ := file.Replayed(context.Background(), id(2)); done {
		t.Fatal("entry written after an earlier ledger failed")
	}
}
