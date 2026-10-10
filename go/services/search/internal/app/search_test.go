package app_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type fakeIndex struct {
	gotVec   []float32
	gotQuery domain.Query
	page     domain.Page
	adHits   []domain.Hit
	adATINs  []string
}

func (f *fakeIndex) AdCandidates(_ context.Context, _ domain.Query, atins []string) ([]domain.Hit, error) {
	f.adATINs = atins
	want := map[string]bool{}
	for _, a := range atins {
		want[a] = true
	}
	out := []domain.Hit{}
	for _, h := range f.adHits {
		if want[h.ATIN] {
			out = append(out, h)
		}
	}
	return out, nil
}

func (f *fakeIndex) Search(_ context.Context, q domain.Query, vec []float32) (domain.Page, error) {
	f.gotQuery, f.gotVec = q, vec
	page := f.page
	if vec != nil {
		page.Retrieval = domain.RetrievalHybrid // as the Elasticsearch adapter reports a fused page
	}
	return page, nil
}

func (f *fakeIndex) Suggest(context.Context, string, string, int) (domain.Suggestions, error) {
	return domain.Suggestions{Phrases: []domain.Suggestion{}, Products: []domain.Hit{
		{ATIN: "AT1", InStock: true, AvailableQty: 2}, {ATIN: "AT2", InStock: false},
	}}, nil
}

func (f *fakeIndex) Ping(context.Context) error { return nil }

type fakeEmbedder struct {
	calls int
	err   error
	delay time.Duration
}

func (f *fakeEmbedder) Embed(ctx context.Context, _ string) ([]float32, error) {
	f.calls++
	if f.delay > 0 {
		select {
		case <-time.After(f.delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if f.err != nil {
		return nil, f.err
	}
	return []float32{0.1, 0.2}, nil
}

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestShortQueriesStayLexical(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{}
	page, err := app.NewProductSearcher(idx, emb, quiet).Search(context.Background(), domain.Query{Text: "  jollof   rice "})
	if err != nil {
		t.Fatal(err)
	}
	if emb.calls != 0 || idx.gotVec != nil || page.Semantic || page.Retrieval != domain.RetrievalLexical ||
		page.SemanticSkipped != domain.SkipShortQuery {
		t.Fatalf("expected lexical-only search, embed calls=%d page %+v", emb.calls, page)
	}
	if idx.gotQuery.Text != "jollof rice" || idx.gotQuery.Size != 24 || idx.gotQuery.Market != "us" {
		t.Fatalf("query not normalized: %+v", idx.gotQuery)
	}
}

func TestNaturalLanguageQueriesBlendVectors(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{}
	page, err := app.NewProductSearcher(idx, emb, quiet).Search(context.Background(), domain.Query{Text: "something for a Diwali dinner"})
	if err != nil {
		t.Fatal(err)
	}
	if emb.calls != 1 || idx.gotVec == nil || !page.Semantic || page.Retrieval != domain.RetrievalHybrid ||
		page.SemanticSkipped != "" {
		t.Fatalf("expected semantic blending: %+v", page)
	}
}

func TestEmbedderFailureDegradesToLexical(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{err: errors.New("down")}
	page, err := app.NewProductSearcher(idx, emb, quiet).Search(context.Background(), domain.Query{Text: "jollof for twenty people"})
	if err != nil {
		t.Fatal(err)
	}
	if idx.gotVec != nil || page.Semantic || page.SemanticSkipped != domain.SkipUnavailable {
		t.Fatalf("expected degraded lexical search: %+v", page)
	}
}

func TestSlowEmbedderTimesOutWithinTheBudget(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{delay: 2 * time.Second}
	start := time.Now()
	page, err := app.NewProductSearcher(idx, emb, quiet).Search(context.Background(), domain.Query{Text: "pepper soup for a cold night"})
	if err != nil {
		t.Fatal(err)
	}
	if took := time.Since(start); took > time.Second {
		t.Fatalf("the 150 ms budget was not enforced: %s", took)
	}
	if idx.gotVec != nil || page.Semantic || page.SemanticSkipped != domain.SkipTimeout {
		t.Fatalf("expected a lexical fallback after the timeout: %+v", page)
	}
}

func TestSemanticIsSkippedForSortsDeepPagesAndWithoutAGateway(t *testing.T) {
	cases := []struct {
		q    domain.Query
		emb  bool
		want string
	}{
		{domain.Query{Text: "spices for party jollof", Sort: domain.SortPriceAsc}, true, domain.SkipSorted},
		{domain.Query{Text: "spices for party jollof", Page: 10, Size: 24}, true, domain.SkipDeepPage},
		{domain.Query{Text: "spices for party jollof"}, false, domain.SkipNotConfigured},
	}
	for _, c := range cases {
		idx, emb := &fakeIndex{}, &fakeEmbedder{}
		s := app.NewProductSearcher(idx, nil, quiet)
		if c.emb {
			s = app.NewProductSearcher(idx, emb, quiet)
		}
		page, err := s.Search(context.Background(), c.q)
		if err != nil {
			t.Fatal(err)
		}
		if emb.calls != 0 || page.Semantic || page.SemanticSkipped != c.want {
			t.Errorf("%+v: calls %d, skipped %q, want %q", c.q, emb.calls, page.SemanticSkipped, c.want)
		}
	}
}

func TestLowStockFlag(t *testing.T) {
	idx := &fakeIndex{page: domain.Page{Hits: []domain.Hit{
		{ATIN: "AT00000001", InStock: true, AvailableQty: 3},
		{ATIN: "AT00000002", InStock: true, AvailableQty: 40},
		{ATIN: "AT00000003", InStock: false, AvailableQty: 0},
	}}}
	page, err := app.NewProductSearcher(idx, nil, quiet).Search(context.Background(), domain.Query{})
	if err != nil {
		t.Fatal(err)
	}
	if !page.Hits[0].LowStock || page.Hits[1].LowStock || page.Hits[2].LowStock {
		t.Fatalf("low-stock flags wrong: %+v", page.Hits)
	}
	if page.Hits[0].Availability != domain.AvailabilityLowStock || page.Hits[1].Availability != domain.AvailabilityInStock ||
		page.Hits[2].Availability != domain.AvailabilityOutOfStock {
		t.Fatalf("availability wrong: %+v", page.Hits)
	}
	sug, err := app.NewSuggester(idx).Suggest(context.Background(), "us", "ga")
	if err != nil {
		t.Fatal(err)
	}
	if !sug.Products[0].LowStock || sug.Products[1].Availability != domain.AvailabilityOutOfStock {
		t.Fatalf("typeahead products carry the stock signal too: %+v", sug.Products)
	}
}

func TestInvalidQueriesAreRejected(t *testing.T) {
	s := app.NewProductSearcher(&fakeIndex{}, nil, quiet)
	for _, q := range []domain.Query{
		{MinPrice: 500, MaxPrice: 100},
		{Sort: "cheapest"},
		{WithinMiles: 15},
	} {
		if _, err := s.Search(context.Background(), q); !errors.Is(err, domain.ErrInvalidQuery) {
			t.Fatalf("expected ErrInvalidQuery for %+v, got %v", q, err)
		}
	}
}

func TestMarketFilterRejectsUnservedMarkets(t *testing.T) {
	s := app.NewProductSearcher(&fakeIndex{}, nil, quiet, app.WithMarkets([]string{"us"}))
	if _, err := s.Search(context.Background(), domain.Query{Market: "US"}); err != nil {
		t.Fatalf("US is served: %v", err)
	}
	if _, err := s.Search(context.Background(), domain.Query{Market: "gh"}); !errors.Is(err, domain.ErrUnknownMarket) {
		t.Fatalf("expected ErrUnknownMarket, got %v", err)
	}
}

func TestTrackedSearchesAreCountedAndPublished(t *testing.T) {
	idx := &fakeIndex{page: domain.Page{Total: 0, Hits: []domain.Hit{}}}
	rec, bus := &fakeRecorder{}, &fakeBus{}
	s := app.NewProductSearcher(idx, nil, quiet, app.WithAnalytics(rec, bus))
	page, err := s.Search(context.Background(), domain.Query{Text: "Kpekere ", Track: true})
	if err != nil {
		t.Fatal(err)
	}
	if page.SearchID == "" || len(rec.searched) != 1 || rec.searched[0] != "us|kpekere|true" {
		t.Fatalf("search not counted: %+v %q", rec.searched, page.SearchID)
	}
	if len(bus.events) != 1 || bus.events[0].Type != domain.EventQueryPerformed || bus.events[0].ID != page.SearchID ||
		bus.events[0].Topic() != "at.search.events" {
		t.Fatalf("event: %+v", bus.events)
	}
	// Rails and Lola opt out with track=false; browsing without text is not a query.
	if _, err := s.Search(context.Background(), domain.Query{Text: "rice", Track: false}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Search(context.Background(), domain.Query{Track: true}); err != nil {
		t.Fatal(err)
	}
	if len(rec.searched) != 1 || len(bus.events) != 1 {
		t.Fatalf("untracked searches recorded: %+v", rec.searched)
	}
}
