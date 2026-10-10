package app_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type fakeIndex struct {
	gotVec   []float32
	gotQuery domain.Query
	page     domain.Page
}

func (f *fakeIndex) Search(_ context.Context, q domain.Query, vec []float32) (domain.Page, error) {
	f.gotQuery, f.gotVec = q, vec
	return f.page, nil
}

func (f *fakeIndex) Suggest(context.Context, string, string, int) (domain.Suggestions, error) {
	return domain.Suggestions{}, nil
}

func (f *fakeIndex) Ping(context.Context) error { return nil }

type fakeEmbedder struct {
	calls int
	err   error
}

func (f *fakeEmbedder) Embed(context.Context, string) ([]float32, error) {
	f.calls++
	return []float32{0.1, 0.2}, f.err
}

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestShortQueriesStayLexical(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{}
	page, err := app.NewProductSearcher(idx, emb, quiet).Search(context.Background(), domain.Query{Text: "  jollof   rice "})
	if err != nil {
		t.Fatal(err)
	}
	if emb.calls != 0 || idx.gotVec != nil || page.Semantic {
		t.Fatalf("expected lexical-only search, embed calls=%d", emb.calls)
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
	if emb.calls != 1 || idx.gotVec == nil || !page.Semantic {
		t.Fatal("expected semantic blending")
	}
}

func TestEmbedderFailureDegradesToLexical(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{err: errors.New("down")}
	page, err := app.NewProductSearcher(idx, emb, quiet).Search(context.Background(), domain.Query{Text: "jollof for twenty people"})
	if err != nil {
		t.Fatal(err)
	}
	if idx.gotVec != nil || page.Semantic {
		t.Fatal("expected degraded lexical search")
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
