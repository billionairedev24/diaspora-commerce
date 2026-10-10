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
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/sellers"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

type fakeIndex struct {
	docs    []searchindex.ProductDocument
	opts    []port.UpsertOptions
	avail   []string
	renames []string
	fail    int
}

func (f *fakeIndex) UpsertProduct(_ context.Context, _ string, d searchindex.ProductDocument, _ int64,
	o port.UpsertOptions,
) error {
	if f.fail > 0 {
		f.fail--
		return errors.New("es down")
	}
	f.docs = append(f.docs, d)
	f.opts = append(f.opts, o)
	return nil
}

func (f *fakeIndex) RenameSeller(_ context.Context, _, sellerID, name string) (int, error) {
	f.renames = append(f.renames, sellerID+"="+name)
	return 3, nil
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

func (fakeSellers) DisplayName(context.Context, string) (string, bool, error) {
	return "AfroTransact Retail", true, nil
}

func (fakeSellers) Remember(context.Context, string, string) {}

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

func sellerProduct(t *testing.T, typ string, hidden bool, at time.Time) events.Envelope {
	t.Helper()
	payload, err := json.Marshal(domain.ProductEvent{
		ProductID: "p2", ATIN: "AT00000002", OwnerKind: "SELLER", OwnerOrgID: "org_kemi", Status: "LIVE",
		Title: "Ankara Wrap Dress", SellerHidden: hidden,
		Variants: []domain.VariantPayload{{ATIN: "AT0000000C", PriceMinor: 6499, Currency: "usd"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return events.Envelope{ID: typ, Type: typ, Payload: payload, OccurredAt: at}
}

func TestHiddenListingsLeaveSearchAndRestoredOnesReturn(t *testing.T) {
	idx := &fakeIndex{}
	p := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet)
	now := time.Now()
	for _, e := range []events.Envelope{
		sellerProduct(t, domain.ProductHidden, true, now),
		sellerProduct(t, domain.ProductResumed, true, now.Add(time.Second)),
		sellerProduct(t, domain.ProductRestored, false, now.Add(2*time.Second)),
	} {
		if err := p.Handle(context.Background(), e); err != nil {
			t.Fatal(err)
		}
	}
	if len(idx.docs) != 3 {
		t.Fatalf("want 3 upserts, got %d", len(idx.docs))
	}
	if idx.docs[0].Status != domain.StatusSellerHidden || idx.docs[1].Status != domain.StatusSellerHidden {
		t.Fatalf("hidden listings must not be LIVE: %q %q", idx.docs[0].Status, idx.docs[1].Status)
	}
	if idx.docs[2].Status != searchindex.StatusLive {
		t.Fatalf("restored listing should be LIVE again, got %q", idx.docs[2].Status)
	}
}

func TestSellerOrgUpdatedRenamesIndexedDocsAndFeedsTheDirectory(t *testing.T) {
	idx := &fakeIndex{}
	dir := sellers.NewMemoryDirectory(nil, "Marketplace seller")
	p := app.NewProjector(idx, fakeCatalog{}, dir, "us", quiet)
	ctx := context.Background()

	// Unknown seller: fallback name, but an already indexed name is preserved.
	if err := p.Handle(ctx, sellerProduct(t, domain.ProductApproved, false, time.Now())); err != nil {
		t.Fatal(err)
	}
	if idx.docs[0].SellerName != "Marketplace seller" || !idx.opts[0].PreserveSellerName {
		t.Fatalf("unknown seller: %q %+v", idx.docs[0].SellerName, idx.opts[0])
	}

	payload, _ := json.Marshal(domain.SellerOrgEvent{OrgID: "org_kemi", Kind: "SELLER", LegalName: "Kemi Foods LLC",
		DisplayName: "Kemi's Pantry", Status: "APPROVED"})
	if err := p.Handle(ctx, events.Envelope{ID: "o1", Type: domain.SellerOrgUpdated, Payload: payload}); err != nil {
		t.Fatal(err)
	}
	if len(idx.renames) != 1 || idx.renames[0] != "org_kemi=Kemi's Pantry" {
		t.Fatalf("renames %v", idx.renames)
	}

	if err := p.Handle(ctx, sellerProduct(t, domain.ProductResumed, false, time.Now())); err != nil {
		t.Fatal(err)
	}
	if idx.docs[1].SellerName != "Kemi's Pantry" || idx.opts[1].PreserveSellerName {
		t.Fatalf("known seller: %q %+v", idx.docs[1].SellerName, idx.opts[1])
	}

	// Legal name is the fallback; malformed and nameless events are skipped.
	legal, _ := json.Marshal(domain.SellerOrgEvent{OrgID: "org_ama", LegalName: "Ama Braids LLC"})
	for _, e := range []events.Envelope{
		{ID: "o2", Type: domain.SellerOrgApproved, Payload: legal},
		{ID: "o3", Type: domain.SellerOrgUpdated, Payload: []byte("{oops")},
		{ID: "o4", Type: domain.SellerOrgUpdated, Payload: []byte(`{"orgId":"org_z"}`)},
	} {
		if err := p.Handle(ctx, e); err != nil {
			t.Fatal(err)
		}
	}
	if len(idx.renames) != 2 || idx.renames[1] != "org_ama=Ama Braids LLC" {
		t.Fatalf("renames %v", idx.renames)
	}
}
