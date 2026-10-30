package app_test

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/app"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
)

const (
	dlq   = "at.order.events.dlq"
	orig  = "at.order.events"
	group = "payments-service"
)

func fixture() (*fakeKafka, *memLedger, *fakeClock, app.Replayer) {
	k := newFakeKafka()
	k.add(dlq,
		dlqRecord("o-1", "11111111-0000-0000-0000-000000000001", "order.created", orig, group, "2026-03-01T10:00:00Z"),
		dlqRecord("o-2", "11111111-0000-0000-0000-000000000002", "order.paid", orig, group, "2026-03-02T10:00:00Z"),
		dlqRecord("o-3", "11111111-0000-0000-0000-000000000003", "order.paid", orig, "fulfilment-service", "2026-03-03T10:00:00Z"),
		dlqRecord("o-2", "11111111-0000-0000-0000-000000000004", "order.cancelled", orig, group, "2026-03-04T10:00:00Z"),
	)
	led := &memLedger{}
	clock := &fakeClock{now: time.Date(2026, 3, 10, 12, 0, 0, 0, time.UTC)}
	return k, led, clock, app.NewReplayer(k, k, led, clock)
}

func TestDryRunIsTheDefaultAndSendsNothing(t *testing.T) {
	k, led, _, replayer := fixture()

	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq})
	if err != nil {
		t.Fatal(err)
	}
	if len(k.written) != 0 {
		t.Fatalf("dry run published %d record(s)", len(k.written))
	}
	if len(led.entries) != 0 {
		t.Fatalf("dry run wrote the ledger: %v", led.entries)
	}
	if got := rep.Count(app.ActionWouldReplay); got != 4 {
		t.Fatalf("would-replay = %d, want 4", got)
	}
}

func TestExecuteRequiresAnOperatorName(t *testing.T) {
	_, _, _, replayer := fixture()
	if _, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Execute: true}); err == nil {
		t.Fatal("expected an error without --by")
	}
}

func TestFiltersSelectRecords(t *testing.T) {
	cases := []struct {
		name   string
		filter domain.Filter
		want   []string
	}{
		{"event type", domain.Filter{EventType: "order.paid"}, []string{"11111111-0000-0000-0000-000000000002", "11111111-0000-0000-0000-000000000003"}},
		{"consumer group", domain.Filter{ConsumerGroup: "fulfilment-service"}, []string{"11111111-0000-0000-0000-000000000003"}},
		{"key", domain.Filter{Key: "o-2"}, []string{"11111111-0000-0000-0000-000000000002", "11111111-0000-0000-0000-000000000004"}},
		{"original topic", domain.Filter{OriginalTopic: "at.payment.events"}, nil},
		{"since", domain.Filter{Since: time.Date(2026, 3, 3, 0, 0, 0, 0, time.UTC)}, []string{"11111111-0000-0000-0000-000000000003", "11111111-0000-0000-0000-000000000004"}},
		{"until is exclusive", domain.Filter{Until: time.Date(2026, 3, 2, 10, 0, 0, 0, time.UTC)}, []string{"11111111-0000-0000-0000-000000000001"}},
		{"combined", domain.Filter{EventType: "order.paid", ConsumerGroup: group, Since: time.Date(2026, 3, 2, 0, 0, 0, 0, time.UTC)}, []string{"11111111-0000-0000-0000-000000000002"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, _, _, replayer := fixture()
			rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Filter: tc.filter})
			if err != nil {
				t.Fatal(err)
			}
			var got []string
			for _, it := range rep.Items {
				got = append(got, it.EventID)
			}
			if len(got) != len(tc.want) {
				t.Fatalf("got %v, want %v", got, tc.want)
			}
			for i := range got {
				if got[i] != tc.want[i] {
					t.Fatalf("got %v, want %v", got, tc.want)
				}
			}
		})
	}
}

func TestMaxLimitsHowManyAreReplayed(t *testing.T) {
	k, _, _, replayer := fixture()
	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Max: 2, Execute: true, By: "ada"})
	if err != nil {
		t.Fatal(err)
	}
	if len(k.written) != 2 || rep.Count(app.ActionReplayed) != 2 {
		t.Fatalf("written %d, replayed %d, want 2", len(k.written), rep.Count(app.ActionReplayed))
	}
}

func TestExecutePublishesToTheOriginalTopicKeepingKeyPayloadAndEventHeaders(t *testing.T) {
	k, _, clock, replayer := fixture()
	_, err := replayer.Replay(context.Background(), app.ReplayOptions{
		DLQTopic: dlq, Filter: domain.Filter{EventType: "order.created"}, Execute: true, By: "ada"})
	if err != nil {
		t.Fatal(err)
	}
	if len(k.written) != 1 {
		t.Fatalf("written %d, want 1", len(k.written))
	}
	out := k.written[0]
	if out.Topic != orig {
		t.Errorf("topic = %q, want %q", out.Topic, orig)
	}
	if string(out.Key) != "o-1" {
		t.Errorf("key = %q, want o-1", out.Key)
	}
	if string(out.Value) != `{"id":"o-1","email":"a@example.com"}` {
		t.Errorf("value changed: %s", out.Value)
	}
	if out.HeaderValue("event-id") != "11111111-0000-0000-0000-000000000001" || out.EventType() != "order.created" {
		t.Errorf("event headers not preserved: %+v", out.Headers)
	}
	if out.HeaderValue("occurred-at") != "2026-03-01T00:00:00Z" {
		t.Errorf("occurred-at not preserved")
	}
	for _, h := range out.Headers {
		if strings.HasPrefix(h.Key, "x-dlq-") || strings.HasPrefix(h.Key, "kafka_dlt-") {
			t.Errorf("diagnostic header %q leaked into the replayed record", h.Key)
		}
	}
	if out.HeaderValue("x-replayed-by") != "ada" {
		t.Errorf("x-replayed-by = %q", out.HeaderValue("x-replayed-by"))
	}
	if out.HeaderValue("x-replayed-at") != clock.now.Format(time.RFC3339Nano) {
		t.Errorf("x-replayed-at = %q", out.HeaderValue("x-replayed-at"))
	}
	if out.HeaderValue("x-replayed-from") != dlq+"/0/0" {
		t.Errorf("x-replayed-from = %q", out.HeaderValue("x-replayed-from"))
	}
}

func TestLedgerPreventsReplayingTheSameRecordTwiceUnlessForced(t *testing.T) {
	k, led, _, replayer := fixture()
	opts := app.ReplayOptions{DLQTopic: dlq, Filter: domain.Filter{EventType: "order.created"}, Execute: true, By: "ada"}

	if _, err := replayer.Replay(context.Background(), opts); err != nil {
		t.Fatal(err)
	}
	if len(led.entries) != 1 || led.entries[0].EventID != "11111111-0000-0000-0000-000000000001" ||
		led.entries[0].TargetTopic != orig || led.entries[0].ReplayedBy != "ada" {
		t.Fatalf("ledger = %+v", led.entries)
	}

	rep, err := replayer.Replay(context.Background(), opts)
	if err != nil {
		t.Fatal(err)
	}
	if len(k.written) != 1 || rep.Count(app.ActionAlreadyReplayed) != 1 {
		t.Fatalf("second run: written %d, already-replayed %d", len(k.written), rep.Count(app.ActionAlreadyReplayed))
	}

	opts.Force = true
	if _, err := replayer.Replay(context.Background(), opts); err != nil {
		t.Fatal(err)
	}
	if len(k.written) != 2 {
		t.Fatalf("--force should replay again, written %d", len(k.written))
	}
}

func TestDryRunDoesNotCountAlreadyReplayedAsPlanned(t *testing.T) {
	_, led, _, replayer := fixture()
	led.entries = append(led.entries, ledgerFor(0))
	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq})
	if err != nil {
		t.Fatal(err)
	}
	if rep.Count(app.ActionAlreadyReplayed) != 1 || rep.Count(app.ActionWouldReplay) != 3 {
		t.Fatalf("already %d, would %d", rep.Count(app.ActionAlreadyReplayed), rep.Count(app.ActionWouldReplay))
	}
}

func TestStopsOnTheFirstPublishError(t *testing.T) {
	k, led, _, replayer := fixture()
	k.failWriteAt = 2

	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Execute: true, By: "ada"})
	if err == nil {
		t.Fatal("expected the publish error")
	}
	if len(k.written) != 1 || len(led.entries) != 1 {
		t.Fatalf("written %d, ledger %d: nothing after the failure may be sent or recorded", len(k.written), len(led.entries))
	}
	if rep.Count(app.ActionPublishFailed) != 1 || rep.Count(app.ActionReplayed) != 1 {
		t.Fatalf("report: %+v", rep.Items)
	}
}

func TestLedgerWriteFailureStopsTheRun(t *testing.T) {
	k, led, _, replayer := fixture()
	led.failOn = true
	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Execute: true, By: "ada"})
	if err == nil {
		t.Fatal("expected an error")
	}
	if len(k.written) != 1 || rep.Count(app.ActionLedgerWriteFailed) != 1 {
		t.Fatalf("written %d, items %+v", len(k.written), rep.Items)
	}
}

func TestRateLimitSpacesPublishes(t *testing.T) {
	_, _, clock, replayer := fixture()
	_, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Execute: true, By: "ada", Rate: 4})
	if err != nil {
		t.Fatal(err)
	}
	// 4 records at 4/s: three waits of 250ms, none before the first.
	if len(clock.sleeps) != 3 {
		t.Fatalf("sleeps = %v", clock.sleeps)
	}
	for _, d := range clock.sleeps {
		if d != 250*time.Millisecond {
			t.Fatalf("sleep = %v, want 250ms", d)
		}
	}
}

func TestRecordWithoutOriginalTopicIsSkippedNotGuessed(t *testing.T) {
	k := newFakeKafka()
	k.add(dlq, domain.Record{Key: []byte("x"), Value: []byte("{}")})
	replayer := app.NewReplayer(k, k, &memLedger{}, &fakeClock{})
	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: dlq, Execute: true, By: "ada"})
	if err != nil {
		t.Fatal(err)
	}
	if len(k.written) != 0 || rep.Count(app.ActionNoOriginalTopic) != 1 {
		t.Fatalf("written %d, items %+v", len(k.written), rep.Items)
	}
}

func TestToTopicReplaysGoDLQRecordsThatCarryNoOriginalTopic(t *testing.T) {
	k := newFakeKafka()
	k.add("at.indexer.dlq.events", domain.Record{Key: []byte("p-1"), Value: []byte("{}"), Headers: []domain.Header{
		{Key: "event-id", Value: []byte("e-1")}, {Key: "event-type", Value: []byte("catalog.product.created")},
	}})
	replayer := app.NewReplayer(k, k, &memLedger{}, &fakeClock{})

	rep, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: "at.indexer.dlq.events", Execute: true,
		By: "ada"})
	if err != nil || rep.Count(app.ActionNoOriginalTopic) != 1 || len(k.written) != 0 {
		t.Fatalf("without --to-topic: err %v items %+v written %d", err, rep.Items, len(k.written))
	}

	rep, err = replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: "at.indexer.dlq.events", Execute: true,
		By: "ada", TargetTopic: "at.product.events"})
	if err != nil || rep.Count(app.ActionReplayed) != 1 || len(k.written) != 1 || k.written[0].Topic != "at.product.events" ||
		k.written[0].EventID() != "e-1" {
		t.Fatalf("with --to-topic: err %v items %+v written %+v", err, rep.Items, k.written)
	}
}

func TestMissingTopicIsAnError(t *testing.T) {
	_, _, _, replayer := fixture()
	if _, err := replayer.Replay(context.Background(), app.ReplayOptions{DLQTopic: "at.nope.events.dlq"}); err == nil {
		t.Fatal("expected topic-not-found")
	}
}
