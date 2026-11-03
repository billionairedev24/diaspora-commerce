package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

var reindexNow = time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)

// pagedSource serves the exported products in pages of the requested size, by cursor (the ATIN of the last item).
type pagedSource struct {
	mu      sync.Mutex
	items   []domain.ProductExportItem
	queries []domain.ProductExportQuery
	// failFrom makes every call from this call number on fail (1-based; 0 = never).
	failFrom int
	calls    int
}

func (s *pagedSource) Products(_ context.Context, q domain.ProductExportQuery) (domain.ProductExportPage, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	s.queries = append(s.queries, q)
	if s.failFrom > 0 && s.calls >= s.failFrom {
		return domain.ProductExportPage{}, errors.New("catalog down")
	}
	start := 0
	if q.After != "" {
		for i, it := range s.items {
			if it.Product.ATIN == q.After {
				start = i + 1
			}
		}
	}
	end := min(start+q.Size, len(s.items))
	page := domain.ProductExportPage{Items: s.items[start:end]}
	if end < len(s.items) {
		page.NextAfter = s.items[end-1].Product.ATIN
	}
	return page, nil
}

type fakeBuyBoxes struct {
	mu    sync.Mutex
	boxes map[string]domain.VariantBuyBox
	calls [][]string
	fail  int // number of leading calls that fail
}

func (f *fakeBuyBoxes) BuyBoxes(_ context.Context, atins []string) ([]domain.VariantBuyBox, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, atins)
	if f.fail > 0 {
		f.fail--
		return nil, errors.New("offers down")
	}
	var out []domain.VariantBuyBox
	for _, a := range atins {
		if bb, ok := f.boxes[a]; ok {
			out = append(out, bb)
		}
	}
	return out, nil
}

type fakeStock struct {
	mu    sync.Mutex
	nodes map[string][]domain.NodeStock
	calls [][]string
	err   error
}

func (f *fakeStock) Stock(_ context.Context, skus []string) ([]domain.NodeStock, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, skus)
	if f.err != nil {
		return nil, f.err
	}
	var out []domain.NodeStock
	for _, s := range skus {
		out = append(out, f.nodes[s]...)
	}
	return out, nil
}

// recorder is a thread-safe events.Handler that can fail for chosen products.
type recorder struct {
	mu     sync.Mutex
	got    []events.Envelope
	failOn map[string]bool // aggregate id
}

func (r *recorder) Handle(_ context.Context, e events.Envelope) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.failOn[e.AggregateID] {
		return errors.New("index down")
	}
	r.got = append(r.got, e)
	return nil
}

func (r *recorder) types() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]string, 0, len(r.got))
	for _, e := range r.got {
		out = append(out, e.Type)
	}
	return out
}

func exportItem(atin string, variants ...string) domain.ProductExportItem {
	pe := domain.ProductEvent{ProductID: "id-" + atin, ATIN: atin, OwnerKind: "PLATFORM", OwnerOrgID: "org_afrotransact_retail",
		Status: "LIVE", Title: "Product " + atin, CategoryID: "c1", Brand: "AfroTransact Brand"}
	for i, v := range variants {
		pe.Variants = append(pe.Variants, domain.VariantPayload{ATIN: v, PriceMinor: int64(1000 - i*100), Currency: "usd"})
	}
	return domain.ProductExportItem{Product: pe, Version: reindexNow.Add(-24 * time.Hour)}
}

type harness struct {
	src      *pagedSource
	boxes    *fakeBuyBoxes
	stock    *fakeStock
	ensured  []string
	ensureFn func(string) error
}

func (h *harness) reindexer(handlers map[string]events.Handler) app.Reindexer {
	return app.NewReindexer(app.ReindexDeps{
		Products: h.src, BuyBoxes: h.boxes, Stock: h.stock,
		EnsureIndex: func(_ context.Context, market string) error {
			h.ensured = append(h.ensured, market)
			if h.ensureFn != nil {
				return h.ensureFn(market)
			}
			return nil
		},
		Handlers: handlers, Log: quiet, Now: func() time.Time { return reindexNow },
		SnapshotSkew: 30 * time.Second, RetryBackoff: time.Millisecond,
	})
}

func newHarness(items ...domain.ProductExportItem) *harness {
	return &harness{src: &pagedSource{items: items}, boxes: &fakeBuyBoxes{boxes: map[string]domain.VariantBuyBox{}},
		stock: &fakeStock{nodes: map[string][]domain.NodeStock{}}}
}

// TestReindexWritesTheDocumentTheEventPathWrites replays an exported product through the real projector and compares
// the result with the document the same product event produces on the live path.
func TestReindexWritesTheDocumentTheEventPathWrites(t *testing.T) {
	item := exportItem("AT00000001", "AT0000000A", "AT0000000B")
	item.Product.Brand = "AfroTransact Brand"
	item.Product.Description = "Smoky party jollof seasoning."
	item.Product.Keywords = []string{"party rice"}
	item.Product.Communities = []string{"Nigerian"}
	item.Rating = &domain.ProductExportRating{Average: 4.5, Count: 12, UpdatedAt: reindexNow.Add(-time.Hour)}

	h := newHarness(item)
	h.boxes.boxes["AT0000000A"] = domain.VariantBuyBox{ATIN: "AT0000000A", WinnerOfferID: "o1", AvailableQty: 7}
	h.boxes.boxes["AT0000000B"] = domain.VariantBuyBox{ATIN: "AT0000000B"} // currently unavailable
	h.stock.nodes["AT0000000A"] = []domain.NodeStock{{SKU: "AT0000000A", Node: "dc1", Available: 9, Version: 4}}

	reindexIdx := &fakeIndex{}
	projector := app.NewProjector(reindexIdx, fakeCatalog{}, fakeSellers{}, "us", quiet)
	sum, err := h.reindexer(map[string]events.Handler{"us": projector}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}})
	if err != nil {
		t.Fatal(err)
	}

	eventIdx := &fakeIndex{}
	live := app.NewProjector(eventIdx, fakeCatalog{}, fakeSellers{}, "us", quiet)
	payload := mustJSON(t, item.Product)
	if err := live.Handle(t.Context(), events.Envelope{ID: "e1", Type: domain.ProductApproved, Payload: payload,
		OccurredAt: item.Version}); err != nil {
		t.Fatal(err)
	}

	if len(reindexIdx.docs) != 1 || !reflect.DeepEqual(reindexIdx.docs[0], eventIdx.docs[0]) {
		t.Fatalf("reindexed document differs from the event path's:\n reindex %+v\n event   %+v", reindexIdx.docs, eventIdx.docs)
	}
	if reindexIdx.opts[0] != eventIdx.opts[0] {
		t.Fatalf("upsert options differ: %+v vs %+v", reindexIdx.opts[0], eventIdx.opts[0])
	}
	if len(reindexIdx.ratings) != 1 || reindexIdx.ratings[0].average != 4.5 || reindexIdx.ratings[0].count != 12 {
		t.Fatalf("ratings = %+v", reindexIdx.ratings)
	}
	wantAvail := []string{"AT0000000A@BUYBOX", "AT0000000A@dc1", "AT0000000B@BUYBOX"}
	if !reflect.DeepEqual(reindexIdx.avail, wantAvail) || !reflect.DeepEqual(reindexIdx.qtys, []int{7, 9, 0}) {
		t.Fatalf("availability = %v %v", reindexIdx.avail, reindexIdx.qtys)
	}
	if got := sum.Markets[0]; got.Read != 1 || got.Written != 1 || got.Failed != 0 || got.Skipped != 0 {
		t.Fatalf("summary = %+v", got)
	}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestReindexPagesThroughTheCatalogAndCountsEverything(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 7 {
		items = append(items, exportItem(fmt.Sprintf("AT%08d", i), fmt.Sprintf("AT%08dV", i)))
	}
	h := newHarness(items...)
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{
		Markets: []string{"us"}, Batch: 3, Statuses: []string{"LIVE", "PAUSED"}, Since: reindexNow.Add(-time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	if sum.Batches != 3 || sum.ResumeAfter != "" || sum.FailedBatches != 0 {
		t.Fatalf("summary = %+v", sum)
	}
	if m := sum.Markets[0]; m.Read != 7 || m.Written != 7 || m.Failed != 0 {
		t.Fatalf("market = %+v", m)
	}
	if got := len(rec.types()); got != 7 {
		t.Fatalf("handled %d events, want 7 product events", got)
	}
	wantAfter := []string{"", "AT00000002", "AT00000005"}
	for i, q := range h.src.queries {
		if q.After != wantAfter[i] || q.Size != 3 || !reflect.DeepEqual(q.Statuses, []string{"LIVE", "PAUSED"}) || !q.Since.Equal(reindexNow.Add(-time.Hour)) {
			t.Fatalf("query %d = %+v", i, q)
		}
	}
	if len(h.ensured) != 1 || h.ensured[0] != "us" {
		t.Fatalf("ensured = %v", h.ensured)
	}
}

func TestDryRunCountsAndWritesNothing(t *testing.T) {
	h := newHarness(exportItem("AT00000001", "V1"), exportItem("AT00000002", "V2"), domain.ProductExportItem{})
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, DryRun: true})
	if err != nil {
		t.Fatal(err)
	}
	if len(rec.types()) != 0 || len(h.ensured) != 0 || len(h.boxes.calls) != 0 || len(h.stock.calls) != 0 {
		t.Fatalf("dry run touched the index or the stock sources: events %v ensured %v", rec.types(), h.ensured)
	}
	if m := sum.Markets[0]; !sum.DryRun || m.Read != 3 || m.Planned != 2 || m.Skipped != 1 || m.Written != 0 {
		t.Fatalf("summary = %+v", sum)
	}
}

func TestEnsuresTheIndexBeforeAnyWriteAndStopsWhenThatFails(t *testing.T) {
	h := newHarness(exportItem("AT00000001", "V1"))
	h.ensureFn = func(string) error { return errors.New("meilisearch unreachable") }
	rec := &recorder{}

	_, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}})
	if err == nil || len(rec.types()) != 0 || h.src.calls != 0 {
		t.Fatalf("err = %v, events %v, catalog calls %d", err, rec.types(), h.src.calls)
	}
}

func TestEveryMarketGetsEveryProductFromOneRead(t *testing.T) {
	h := newHarness(exportItem("AT00000001", "V1"), exportItem("AT00000002", "V2"))
	us, ng := &recorder{}, &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": us, "ng": ng}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us", "ng"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(us.types()) != 2 || len(ng.types()) != 2 || h.src.calls != 1 {
		t.Fatalf("us %v ng %v catalog calls %d", us.types(), ng.types(), h.src.calls)
	}
	for _, m := range sum.Markets {
		if m.Written != 2 {
			t.Fatalf("market %+v", m)
		}
	}
	if !reflect.DeepEqual(h.ensured, []string{"us", "ng"}) {
		t.Fatalf("ensured = %v", h.ensured)
	}
}

func TestUnknownOrMissingMarketIsRefused(t *testing.T) {
	h := newHarness()
	r := h.reindexer(map[string]events.Handler{"us": &recorder{}})
	if _, err := r.Run(t.Context(), app.ReindexOptions{Markets: []string{"zz"}}); err == nil {
		t.Fatal("unknown market accepted")
	}
	if _, err := r.Run(t.Context(), app.ReindexOptions{}); err == nil {
		t.Fatal("no market accepted")
	}
}

func TestOneFailingProductDoesNotStopTheRun(t *testing.T) {
	h := newHarness(exportItem("AT00000001", "V1"), exportItem("AT00000002", "V2"), exportItem("AT00000003", "V3"))
	rec := &recorder{failOn: map[string]bool{"AT00000002": true}}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Concurrency: 1})
	if err != nil {
		t.Fatalf("a failed product must not abort the run: %v", err)
	}
	if m := sum.Markets[0]; m.Written != 2 || m.Failed != 1 || sum.Failures() != 1 {
		t.Fatalf("summary = %+v", sum)
	}
}

func TestStockReadFailureFailsTheBatchAfterRetriesAndTheRunGoesOn(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 4 {
		items = append(items, exportItem(fmt.Sprintf("AT0000000%d", i), fmt.Sprintf("V%d", i)))
	}
	h := newHarness(items...)
	h.boxes.fail = 3 // the first page exhausts its three attempts; the second page reads fine
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Batch: 2})
	if err != nil {
		t.Fatal(err)
	}
	if sum.FailedBatches != 1 || sum.Markets[0].Failed != 2 || sum.Markets[0].Written != 2 {
		t.Fatalf("summary = %+v", sum)
	}
	if len(h.boxes.calls) != 4 { // 3 attempts for page one, 1 for page two
		t.Fatalf("buy box calls = %d", len(h.boxes.calls))
	}
	if sum.Failures() != 2 {
		t.Fatalf("failures = %d", sum.Failures())
	}
}

func TestStockSourceThatStaysDownEndsTheRunAfterThreeFailedBatches(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 10 {
		items = append(items, exportItem(fmt.Sprintf("AT0000000%d", i), fmt.Sprintf("V%d", i)))
	}
	h := newHarness(items...)
	h.stock.err = errors.New("inventory down")
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Batch: 2, After: "AT00000000"})
	if err == nil {
		t.Fatal("a run whose stock source is down must end with an error")
	}
	if sum.FailedBatches != 3 || sum.Batches != 3 || sum.ResumeAfter != "AT00000000" || len(rec.types()) != 0 {
		t.Fatalf("summary = %+v, events %v", sum, rec.types())
	}
}

func TestTransientSourceFailureIsRetried(t *testing.T) {
	h := newHarness(exportItem("AT00000001", "V1"))
	h.boxes.fail = 2
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}})
	if err != nil || sum.Failures() != 0 || sum.Markets[0].Written != 1 {
		t.Fatalf("err = %v, summary = %+v", err, sum)
	}
}

func TestCatalogFailureEndsTheRunWithTheCursorToResumeFrom(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 6 {
		items = append(items, exportItem(fmt.Sprintf("AT0000000%d", i), fmt.Sprintf("V%d", i)))
	}
	h := newHarness(items...)
	h.src.failFrom = 2 // page one reads, page two never does
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Batch: 3})
	if err == nil {
		t.Fatal("expected the catalog failure")
	}
	if sum.ResumeAfter != "AT00000002" || sum.Markets[0].Written != 3 {
		t.Fatalf("summary = %+v", sum)
	}

	// Resuming from that cursor covers the rest.
	h.src.failFrom = 0
	rec2 := &recorder{}
	sum2, err := h.reindexer(map[string]events.Handler{"us": rec2}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Batch: 3, After: sum.ResumeAfter})
	if err != nil || sum2.Markets[0].Written != 3 || len(rec2.types()) != 3 {
		t.Fatalf("resume: err = %v, summary = %+v", err, sum2)
	}
}

func TestInterruptionReportsWhereToResume(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 4 {
		items = append(items, exportItem(fmt.Sprintf("AT0000000%d", i), fmt.Sprintf("V%d", i)))
	}
	h := newHarness(items...)
	ctx, cancel := context.WithCancel(t.Context())
	rec := &cancelOnFirst{cancel: cancel}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(ctx, app.ReindexOptions{Markets: []string{"us"}, Batch: 2, Concurrency: 1})
	if !errors.Is(err, context.Canceled) || sum.ResumeAfter != "AT00000001" {
		t.Fatalf("err = %v, resume = %q", err, sum.ResumeAfter)
	}
}

type cancelOnFirst struct{ cancel context.CancelFunc }

func (c *cancelOnFirst) Handle(context.Context, events.Envelope) error {
	c.cancel()
	return nil
}

func TestACatalogThatDoesNotAdvanceIsAnError(t *testing.T) {
	h := newHarness()
	h.src = &pagedSource{}
	r := app.NewReindexer(app.ReindexDeps{Products: stuckSource{}, BuyBoxes: h.boxes, Stock: h.stock,
		EnsureIndex: func(context.Context, string) error { return nil }, Handlers: map[string]events.Handler{"us": &recorder{}},
		Log: quiet, RetryBackoff: time.Millisecond})
	if _, err := r.Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, After: "AT1"}); err == nil {
		t.Fatal("a repeated cursor must end the run")
	}
}

type stuckSource struct{}

func (stuckSource) Products(context.Context, domain.ProductExportQuery) (domain.ProductExportPage, error) {
	return domain.ProductExportPage{Items: []domain.ProductExportItem{exportItem("AT1", "V1")}, NextAfter: "AT1"}, nil
}

func TestEventsAreDatedSoLiveEventsWin(t *testing.T) {
	item := exportItem("AT00000001", "V1")
	item.Rating = &domain.ProductExportRating{Average: 3, Count: 2, UpdatedAt: reindexNow.Add(-2 * time.Hour)}
	h := newHarness(item)
	h.boxes.boxes["V1"] = domain.VariantBuyBox{ATIN: "V1", WinnerOfferID: "o1", AvailableQty: 1}
	h.stock.nodes["V1"] = []domain.NodeStock{{SKU: "V1", Node: "dc1", Available: 2}}
	rec := &recorder{}

	if _, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}}); err != nil {
		t.Fatal(err)
	}
	at := map[string]time.Time{}
	for _, e := range rec.got {
		at[e.Type] = e.OccurredAt
	}
	snapshot := reindexNow.Add(-30 * time.Second)
	if !at[domain.ProductRestored].Equal(item.Version) {
		t.Fatalf("product dated %v, want its change time %v", at[domain.ProductRestored], item.Version)
	}
	if !at[domain.RatingChanged].Equal(item.Rating.UpdatedAt) {
		t.Fatalf("rating dated %v", at[domain.RatingChanged])
	}
	if !at[domain.BuyBoxChanged].Equal(snapshot) || !at[domain.AvailabilityChanged].Equal(snapshot) {
		t.Fatalf("buy box %v / stock %v, want %v", at[domain.BuyBoxChanged], at[domain.AvailabilityChanged], snapshot)
	}
	order := rec.types()
	if order[0] != domain.ProductRestored {
		t.Fatalf("the product must be written before its quantities: %v", order)
	}
}

// guardIndex applies the version guard of the real adapters to the fake index.
type guardIndex struct {
	*fakeIndex
	mu       sync.Mutex
	versions map[string]int64
	written  map[string]int64
}

func (g *guardIndex) UpsertProduct(ctx context.Context, market string, d searchindex.ProductDocument, version int64, o port.UpsertOptions) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.versions[d.ATIN] > version {
		return nil // a newer document is already indexed
	}
	g.versions[d.ATIN] = version
	g.written[d.ATIN] = version
	return g.fakeIndex.UpsertProduct(ctx, market, d, version, o)
}

func TestARerunReappliesTheSameStateAndANewerLiveDocumentSurvives(t *testing.T) {
	item1, item2 := exportItem("AT00000001", "V1"), exportItem("AT00000002", "V2")
	h := newHarness(item1, item2)
	live := reindexNow.Add(time.Minute) // the live indexer wrote AT00000002 after the export's version
	idx := &guardIndex{fakeIndex: &fakeIndex{}, versions: map[string]int64{"AT00000002": domain.VersionOf(live)}, written: map[string]int64{}}
	projector := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet)
	r := h.reindexer(map[string]events.Handler{"us": projector})

	for range 2 { // run twice
		if _, err := r.Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Concurrency: 1}); err != nil {
			t.Fatal(err)
		}
	}
	if v := idx.written["AT00000001"]; v != domain.VersionOf(item1.Version) {
		t.Fatalf("AT00000001 written with version %d, want %d", v, domain.VersionOf(item1.Version))
	}
	if _, overwritten := idx.written["AT00000002"]; overwritten {
		t.Fatal("the reindex overwrote a document the live indexer had written later")
	}
	if len(idx.docs) != 2 || !reflect.DeepEqual(idx.docs[0], idx.docs[1]) {
		t.Fatalf("a re-run must write the same document: %+v", idx.docs)
	}
}

func TestProductsWithoutATINAreSkippedNotWritten(t *testing.T) {
	h := newHarness(exportItem("AT00000001", "V1"), domain.ProductExportItem{Product: domain.ProductEvent{Title: "broken"}})
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}})
	if err != nil {
		t.Fatal(err)
	}
	if m := sum.Markets[0]; m.Read != 2 || m.Skipped != 1 || m.Written != 1 || len(rec.types()) != 1 {
		t.Fatalf("summary = %+v events %v", sum, rec.types())
	}
}

func TestSeveralWorkersWriteEveryProductOnce(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 40 {
		items = append(items, exportItem(fmt.Sprintf("AT%08d", i), fmt.Sprintf("V%d", i)))
	}
	h := newHarness(items...)
	rec := &recorder{}

	sum, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Batch: 15, Concurrency: 6})
	if err != nil || sum.Markets[0].Written != 40 || len(rec.types()) != 40 {
		t.Fatalf("err = %v summary = %+v events %d", err, sum, len(rec.types()))
	}
}

func TestStockIsRequestedInTheSourcesBatchSizes(t *testing.T) {
	var items []domain.ProductExportItem
	for i := range 120 {
		items = append(items, exportItem(fmt.Sprintf("AT%08d", i), fmt.Sprintf("V%d", i)))
	}
	h := newHarness(items...)
	rec := &recorder{}

	if _, err := h.reindexer(map[string]events.Handler{"us": rec}).Run(t.Context(), app.ReindexOptions{Markets: []string{"us"}, Batch: 120}); err != nil {
		t.Fatal(err)
	}
	sizes := func(calls [][]string) (out []int) {
		for _, c := range calls {
			out = append(out, len(c))
		}
		return out
	}
	if got := sizes(h.boxes.calls); !reflect.DeepEqual(got, []int{50, 50, 20}) {
		t.Fatalf("buy box call sizes = %v", got)
	}
	if got := sizes(h.stock.calls); !reflect.DeepEqual(got, []int{100, 20}) {
		t.Fatalf("stock call sizes = %v", got)
	}
}
