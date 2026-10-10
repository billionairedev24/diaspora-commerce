package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

type fakeIndex struct {
	docs  []searchindex.ProductDocument
	avail []string
	fail  int
}

func (f *fakeIndex) UpsertProduct(_ context.Context, _ string, d searchindex.ProductDocument, _ int64) error {
	if f.fail > 0 {
		f.fail--
		return errors.New("es down")
	}
	f.docs = append(f.docs, d)
	return nil
}

func (f *fakeIndex) ApplyAvailability(_ context.Context, _, sku, node string, qty int, _ int64) (bool, error) {
	f.avail = append(f.avail, sku+"@"+node)
	return true, nil
}

func (f *fakeIndex) Ping(context.Context) error { return nil }

type fakeCatalog struct{}

func (fakeCatalog) Category(context.Context, string) (domain.Category, error) {
	return domain.Category{Department: "Groceries", Path: "Groceries › Pantry › Spices & seasonings"}, nil
}

type fakeSellers struct{}

func (fakeSellers) DisplayName(context.Context, string) (string, error) {
	return "AfroTransact Retail", nil
}

type fakeDLQ struct{ got []events.Envelope }

func (f *fakeDLQ) Publish(_ context.Context, e events.Envelope) error {
	f.got = append(f.got, e)
	return nil
}
func (f *fakeDLQ) Close() {}

func productEnvelope(t *testing.T) events.Envelope {
	t.Helper()
	payload, _ := json.Marshal(domain.ProductEvent{
		ProductID: "p1", ATIN: "AT00000001", OwnerKind: "PLATFORM", OwnerOrgID: "org_afrotransact_retail",
		Status: "LIVE", Title: "Jollof Seasoning Blend", CategoryID: "c1",
		Variants: []domain.VariantPayload{
			{ATIN: "AT0000000A", PriceMinor: 1299, Currency: "usd"},
			{ATIN: "AT0000000B", PriceMinor: 899, Currency: "usd"},
		},
	})
	return events.Envelope{ID: "e1", Type: domain.ProductApproved, Payload: payload, OccurredAt: time.Now()}
}

func TestProductEventBecomesDocument(t *testing.T) {
	idx := &fakeIndex{}
	p := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet)
	if err := p.Handle(context.Background(), productEnvelope(t)); err != nil {
		t.Fatal(err)
	}
	d := idx.docs[0]
	if d.Department != "Groceries" || d.Fulfilment != searchindex.FulfilledByAfroTransact || d.PriceMinor != 899 ||
		d.Currency != "USD" || len(d.VariantATINs) != 2 || d.Status != "LIVE" {
		t.Fatalf("unexpected doc %+v", d)
	}
}

func TestMalformedAndUnknownEventsAreSkipped(t *testing.T) {
	idx := &fakeIndex{}
	p := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet)
	for _, e := range []events.Envelope{
		{ID: "x", Type: domain.ProductApproved, Payload: []byte("{not json")},
		{ID: "y", Type: "orders.order.placed", Payload: []byte("{}")},
	} {
		if err := p.Handle(context.Background(), e); err != nil {
			t.Fatalf("expected skip, got %v", err)
		}
	}
	if len(idx.docs) != 0 {
		t.Fatal("nothing should be indexed")
	}
}

func TestRetriesThenDeadLetters(t *testing.T) {
	idx := &fakeIndex{fail: 10}
	dlq := &fakeDLQ{}
	h := app.NewRetryingHandler(app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet), dlq, 3,
		time.Millisecond, quiet)
	if err := h.Handle(context.Background(), productEnvelope(t)); err != nil {
		t.Fatal(err)
	}
	if len(dlq.got) != 1 || dlq.got[0].Topic() != "at.indexer.dlq.events" {
		t.Fatalf("expected one dead letter, got %+v", dlq.got)
	}
}

func TestTransientFailureRecovers(t *testing.T) {
	idx := &fakeIndex{fail: 2}
	dlq := &fakeDLQ{}
	h := app.NewRetryingHandler(app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet), dlq, 3,
		time.Millisecond, quiet)
	if err := h.Handle(context.Background(), productEnvelope(t)); err != nil {
		t.Fatal(err)
	}
	if len(idx.docs) != 1 || len(dlq.got) != 0 {
		t.Fatal("expected success on third attempt")
	}
}
