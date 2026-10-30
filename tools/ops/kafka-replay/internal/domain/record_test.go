package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
)

func h(k, v string) domain.Header { return domain.Header{Key: k, Value: []byte(v)} }

func TestReplayHeadersKeepEventHeadersDropDiagnosticsAndAddMarkers(t *testing.T) {
	rec := domain.Record{Topic: "t.dlq", Partition: 2, Offset: 5, Headers: []domain.Header{
		h("event-id", "abc"), h("event-type", "order.paid"), h("custom", "keep"),
		h("x-dlq-attempts", "6"), h("kafka_dlt-exception-fqcn", "x"),
		h("x-replayed-by", "old"), h("x-replayed-at", "old"),
	}}
	at := time.Date(2026, 3, 4, 5, 6, 7, 0, time.UTC)

	out := domain.Record{Headers: rec.ReplayHeaders(at, "ada")}

	if out.EventID() != "abc" || out.EventType() != "order.paid" || out.HeaderValue("custom") != "keep" {
		t.Fatalf("headers lost: %+v", out.Headers)
	}
	if out.HeaderValue("x-dlq-attempts") != "" || out.HeaderValue("kafka_dlt-exception-fqcn") != "" {
		t.Fatalf("diagnostics kept: %+v", out.Headers)
	}
	if out.HeaderValue("x-replayed-by") != "ada" || out.HeaderValue("x-replayed-from") != "t.dlq/2/5" ||
		out.HeaderValue("x-replayed-at") != "2026-03-04T05:06:07Z" {
		t.Fatalf("markers wrong: %+v", out.Headers)
	}
	count := 0
	for _, hd := range out.Headers {
		if hd.Key == "x-replayed-by" {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("x-replayed-by appears %d times", count)
	}
}

func TestFailedAtFallsBackToTheRecordTimestamp(t *testing.T) {
	ts := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	if got := (domain.Record{Timestamp: ts}).FailedAt(); !got.Equal(ts) {
		t.Fatalf("got %v", got)
	}
	if got := (domain.Record{Timestamp: ts, Headers: []domain.Header{h("x-dlq-failed-at", "garbage")}}).FailedAt(); !got.Equal(ts) {
		t.Fatalf("garbage header should be ignored, got %v", got)
	}
}
