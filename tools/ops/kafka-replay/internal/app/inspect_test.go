package app_test

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/app"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

func ledgerFor(offset int64) port.LedgerEntry {
	return port.LedgerEntry{DLQTopic: dlq, Partition: 0, Offset: offset, TargetTopic: orig}
}

func TestListSummarisesTheDLQ(t *testing.T) {
	k, _, _, _ := fixture()
	s, err := app.NewInspector(k).List(context.Background(), dlq, domain.Filter{})
	if err != nil {
		t.Fatal(err)
	}
	if s.Total != 4 || s.ByEventType["order.paid"] != 2 || s.ByConsumerGrp[group] != 3 ||
		s.ByException["java.lang.IllegalStateException"] != 4 || s.ByOriginal[orig] != 4 {
		t.Fatalf("summary = %+v", s)
	}
	if !s.Oldest.Equal(time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)) || !s.Newest.Equal(time.Date(2026, 3, 4, 10, 0, 0, 0, time.UTC)) {
		t.Fatalf("oldest %v newest %v", s.Oldest, s.Newest)
	}
	var out bytes.Buffer
	if err := app.RenderSummary(&out, dlq, s); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"4 record(s)", "by exception class", "order.paid", "payments-service"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("summary output lacks %q:\n%s", want, out.String())
		}
	}
}

func TestShowFindsOneRecordAndCanRedact(t *testing.T) {
	k, _, _, _ := fixture()
	insp := app.NewInspector(k)
	id := domain.RecordID{Topic: dlq, Partition: 0, Offset: 1}

	rec, err := insp.Show(context.Background(), id, false)
	if err != nil {
		t.Fatal(err)
	}
	if rec.EventType() != "order.paid" || !strings.Contains(string(rec.Value), "a@example.com") {
		t.Fatalf("record = %+v", rec)
	}

	red, err := insp.Show(context.Background(), id, true)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(red.Value), "example.com") || !strings.Contains(string(red.Value), `"email":"***"`) {
		t.Fatalf("redacted payload = %s", red.Value)
	}
}

func TestShowUnknownOffsetIsNotFound(t *testing.T) {
	k, _, _, _ := fixture()
	_, err := app.NewInspector(k).Show(context.Background(), domain.RecordID{Topic: dlq, Offset: 99}, false)
	if !errors.Is(err, app.ErrRecordNotFound) {
		t.Fatalf("err = %v", err)
	}
}

func TestRedactPayload(t *testing.T) {
	got := string(app.RedactPayload([]byte(`{"a":{"b":["x",1,true]},"n":null}`)))
	if got != `{"a":{"b":["***",1,true]},"n":null}` {
		t.Fatalf("got %s", got)
	}
	if got := string(app.RedactPayload([]byte("not json at all"))); !strings.HasPrefix(got, "<redacted") || strings.Contains(got, "not json") {
		t.Fatalf("non-JSON payload leaked: %s", got)
	}
}
