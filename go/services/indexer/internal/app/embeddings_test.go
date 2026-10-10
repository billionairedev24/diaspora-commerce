package app_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
)

// fakeEmbedder plays ai-gateway (never a real model): a vector whose first component is the text length.
type fakeEmbedder struct {
	texts     []string
	err       error
	failAfter int
}

func (f *fakeEmbedder) Embed(_ context.Context, text string) ([]float32, error) {
	f.texts = append(f.texts, text)
	if f.err != nil || (f.failAfter > 0 && len(f.texts) > f.failAfter) {
		return nil, errors.New("ai-gateway: status 503")
	}
	return []float32{float32(len(text)), 1}, nil
}

func TestProductDocumentsCarryAnEmbeddingOfTheirText(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{}
	p := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet, app.WithEmbedder(emb, time.Second))
	if err := p.Handle(context.Background(), productEnvelope(t)); err != nil {
		t.Fatal(err)
	}
	if len(emb.texts) != 1 || emb.texts[0] != "Jollof Seasoning Blend. Groceries / Pantry / Spices & seasonings" {
		t.Fatalf("embedded text %q", emb.texts)
	}
	if len(idx.docs) != 1 || len(idx.docs[0].Embedding) != 2 {
		t.Fatalf("document must carry the vector: %+v", idx.docs)
	}
}

func TestEmbeddingFailureNeverBlocksIndexing(t *testing.T) {
	idx, emb := &fakeIndex{}, &fakeEmbedder{err: errors.New("down")}
	p := app.NewProjector(idx, fakeCatalog{}, fakeSellers{}, "us", quiet, app.WithEmbedder(emb, time.Second))
	if err := p.Handle(context.Background(), productEnvelope(t)); err != nil {
		t.Fatalf("an AI outage must not fail the event: %v", err)
	}
	if len(idx.docs) != 1 || idx.docs[0].Embedding != nil {
		t.Fatalf("indexed without a vector (the previous one is kept by the upsert): %+v", idx.docs)
	}
}

func TestBackfillEmbedsDocumentsWithoutOneAndResumesAfterAnOutage(t *testing.T) {
	missing := []domain.EmbeddingCandidate{
		{ATIN: "AT1", Title: "Gari Ijebu", CategoryPath: "Groceries › Pantry", Version: 1},
		{ATIN: "AT2", Title: "Kente Stole", CategoryPath: "Fashion › Accessories", Version: 4},
		{ATIN: "AT3", Title: "Shea Butter", CategoryPath: "Beauty › Skin care", Version: 2},
	}
	idx := &fakeIndex{missing: missing, changed: map[string]bool{"AT2": true}}
	flaky := &fakeEmbedder{failAfter: 1}
	b := app.NewEmbeddingBackfill(idx, flaky, "us", 2, time.Second, quiet)
	n, err := b.RunOnce(context.Background())
	if err == nil || n != 1 || len(idx.vectors) != 1 {
		t.Fatalf("an outage stops the pass after AT1: stored %d, err %v", n, err)
	}
	// Next pass: AT1 now has one (the fake list still names it, the store accepts it again), AT2 changed meanwhile.
	idx.missing = missing[1:]
	n, err = app.NewEmbeddingBackfill(idx, &fakeEmbedder{}, "us", 2, time.Second, quiet).RunOnce(context.Background())
	if err != nil || n != 1 || idx.vectors["AT3"] == nil || idx.vectors["AT2"] != nil {
		t.Fatalf("second pass: stored %d (%v), vectors %v", n, err, idx.vectors)
	}
}
