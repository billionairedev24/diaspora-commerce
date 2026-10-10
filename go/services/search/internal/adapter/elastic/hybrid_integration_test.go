package elastic_test

import (
	"context"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/relevance"
)

// TestHybridKeepsFiltersAndFallsBackWhenKNNFails: the kNN side obeys the shopper's filters (and the LIVE status), a
// semantic-only product counts in the total, and a kNN failure serves the lexical page instead of an error.
func TestHybridKeepsFiltersAndFallsBackWhenKNNFails(t *testing.T) {
	ctx := context.Background()
	es := sharedES(t)
	const market = "hy"
	if err := bootstrapIndex(ctx, es, market); err != nil {
		t.Fatal(err)
	}
	products, err := relevance.LoadProducts("../../../relevance/products.yaml")
	if err != nil {
		t.Fatal(err)
	}
	fake, err := relevance.LoadFakeEmbedder("../../../relevance/concepts.yaml", searchindex.EmbeddingDimensions)
	if err != nil {
		t.Fatal(err)
	}
	bulkIndex(t, es, market, fixtureDocs(t, products, fake))
	idx := elastic.NewIndex(es)
	search := func(q domain.Query, vec []float32) domain.Page {
		t.Helper()
		q, err := q.Normalize()
		if err != nil {
			t.Fatal(err)
		}
		page, err := idx.Search(ctx, q, vec)
		if err != nil {
			t.Fatal(err)
		}
		return page
	}

	text := "graduation gift from ghana"
	vec, _ := fake.Embed(ctx, text)
	all := search(domain.Query{Market: market, Text: text}, vec)
	if all.Retrieval != domain.RetrievalHybrid || len(all.Hits) == 0 || all.Hits[0].ATIN != "AT1KEN0001" || all.Total == 0 {
		t.Fatalf("hybrid page %+v", all)
	}
	inStock := search(domain.Query{Market: market, Text: text, InStockOnly: true}, vec)
	for _, h := range inStock.Hits {
		if !h.InStock || h.ATIN == "AT1KEN0002" {
			t.Fatalf("in-stock filter must apply to the kNN side: %+v", h)
		}
	}
	beauty := search(domain.Query{Market: market, Text: "drink made from hibiscus flowers", Department: "Beauty"},
		func() []float32 { v, _ := fake.Embed(ctx, "drink made from hibiscus flowers"); return v }())
	for _, h := range beauty.Hits {
		if !strings.HasPrefix(h.CategoryPath, "Beauty") {
			t.Fatalf("department filter must apply to the kNN side: %s %s", h.ATIN, h.CategoryPath)
		}
	}
	for _, f := range beauty.Facets["department"] {
		if f.Value != "Beauty" {
			t.Fatalf("facets come from the filtered lexical side: %+v", beauty.Facets["department"])
		}
	}

	// A vector of the wrong size makes the kNN side fail: the lexical page is served and the reason reported.
	broken := search(domain.Query{Market: market, Text: "jollof rice seasoning"}, []float32{0.1, 0.2, 0.3})
	lexical := search(domain.Query{Market: market, Text: "jollof rice seasoning"}, nil)
	if broken.Retrieval != domain.RetrievalLexical || broken.SemanticSkipped != domain.SkipUnavailable ||
		broken.Total != lexical.Total || len(broken.Hits) != len(lexical.Hits) || broken.Hits[0].ATIN != lexical.Hits[0].ATIN {
		t.Fatalf("lexical fallback %+v, lexical %+v", broken, lexical)
	}
}
