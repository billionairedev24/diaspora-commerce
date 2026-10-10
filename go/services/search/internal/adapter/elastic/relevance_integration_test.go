package elastic_test

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/relevance"
)

// TestRelevanceJudgements indexes relevance/products.yaml with the production index settings and queries, runs every
// judged query keyword-only (BM25) and hybrid (BM25 + kNN fused by reciprocal rank, with the fake deterministic
// embeddings of relevance/concepts.yaml) and fails when:
//   - the keyword ranking drops below the core thresholds (S039),
//   - hybrid search, used as production uses it (only for 3+ word queries), drops below them (no regression),
//   - hybrid search misses the natural-language thresholds or does not beat keyword-only on those queries (S036).
//
// Run it alone with: go test ./internal/adapter/elastic -run TestRelevanceJudgements -v
func TestRelevanceJudgements(t *testing.T) {
	ctx := context.Background()
	es := sharedES(t)
	const market = "rl"
	if err := bootstrapIndex(ctx, es, market); err != nil {
		t.Fatal(err)
	}
	products, err := relevance.LoadProducts("../../../relevance/products.yaml")
	if err != nil {
		t.Fatal(err)
	}
	suite, err := relevance.LoadSuite("../../../relevance/judgements.yaml")
	if err != nil {
		t.Fatal(err)
	}
	fake, err := relevance.LoadFakeEmbedder("../../../relevance/concepts.yaml", searchindex.EmbeddingDimensions)
	if err != nil {
		t.Fatal(err)
	}
	bulkIndex(t, es, market, fixtureDocs(t, products, fake))
	idx := elastic.NewIndex(es)

	var report strings.Builder
	run := func(title string, queries []relevance.Query, hybridOnlyForLongQueries bool) (kw, hy metrics) {
		fmt.Fprintf(&report, "\n%s\n%-34s %8s %8s %8s %8s  hybrid top 5\n", title, "query", "kw ndcg", "kw p@5",
			"hy ndcg", "hy p@5")
		for _, jq := range queries {
			q, err := domain.Query{Market: market, Text: jq.Q, Size: 10}.Normalize()
			if err != nil {
				t.Fatal(err)
			}
			keyword := ranked(t, idx, q, nil)
			var vec []float32
			if !hybridOnlyForLongQueries || len(strings.Fields(jq.Q)) >= 3 {
				vec, _ = fake.Embed(ctx, jq.Q)
			}
			hybrid := keyword
			if vec != nil {
				hybrid = ranked(t, idx, q, vec)
			}
			k, h := score(t, jq, keyword), score(t, jq, hybrid)
			kw.add(k)
			hy.add(h)
			fmt.Fprintf(&report, "%-34s %8.3f %8.3f %8.3f %8.3f  %s\n", jq.Q, k.ndcg, k.p5, h.ndcg, h.p5,
				strings.Join(hybrid[:min(5, len(hybrid))], " "))
		}
		fmt.Fprintf(&report, "%-34s %8.3f %8.3f %8.3f %8.3f  (%d queries)\n", "MEAN", kw.meanNDCG(), kw.meanP5(),
			hy.meanNDCG(), hy.meanP5(), len(queries))
		return kw, hy
	}
	coreKW, coreHY := run("core queries (hybrid only for 3+ words, as in production)", suite.Queries, true)
	semKW, semHY := run("natural-language queries (3+ words)", suite.SemanticQueries, false)
	allKW, allHY := coreKW.merge(semKW), coreHY.merge(semHY)
	fmt.Fprintf(&report, "\n%-34s %8.3f %8.3f %8.3f %8.3f  (%d queries)\n", "ALL", allKW.meanNDCG(), allKW.meanP5(),
		allHY.meanNDCG(), allHY.meanP5(), allKW.n)
	t.Log(report.String())

	th, sth := suite.Thresholds, suite.SemanticThresholds
	if coreKW.meanNDCG() < th.NDCG10 || coreKW.meanP5() < th.Precision5 {
		t.Errorf("keyword: core NDCG@10 %.3f / P@5 %.3f below %.2f / %.2f", coreKW.meanNDCG(), coreKW.meanP5(), th.NDCG10, th.Precision5)
	}
	if coreHY.meanNDCG() < th.NDCG10 || coreHY.meanP5() < th.Precision5 {
		t.Errorf("hybrid: core NDCG@10 %.3f / P@5 %.3f below %.2f / %.2f", coreHY.meanNDCG(), coreHY.meanP5(), th.NDCG10, th.Precision5)
	}
	if semHY.meanNDCG() < sth.NDCG10 || semHY.meanP5() < sth.Precision5 {
		t.Errorf("hybrid: natural-language NDCG@10 %.3f / P@5 %.3f below %.2f / %.2f", semHY.meanNDCG(), semHY.meanP5(),
			sth.NDCG10, sth.Precision5)
	}
	if semHY.meanNDCG() <= semKW.meanNDCG() {
		t.Errorf("hybrid (%.3f) must beat keyword-only (%.3f) on natural-language queries", semHY.meanNDCG(), semKW.meanNDCG())
	}
}

type queryScore struct{ ndcg, p5 float64 }

type metrics struct {
	sumNDCG, sumP5 float64
	n              int
}

func (m *metrics) add(s queryScore) { m.sumNDCG, m.sumP5, m.n = m.sumNDCG+s.ndcg, m.sumP5+s.p5, m.n+1 }
func (m metrics) merge(o metrics) metrics {
	return metrics{sumNDCG: m.sumNDCG + o.sumNDCG, sumP5: m.sumP5 + o.sumP5, n: m.n + o.n}
}
func (m metrics) meanNDCG() float64 { return m.sumNDCG / float64(max(1, m.n)) }
func (m metrics) meanP5() float64   { return m.sumP5 / float64(max(1, m.n)) }

func ranked(t *testing.T, idx port.Index, q domain.Query, vec []float32) []string {
	t.Helper()
	page, err := idx.Search(context.Background(), q, vec)
	if err != nil {
		t.Fatalf("%q: %v", q.Text, err)
	}
	if vec != nil && page.Retrieval != domain.RetrievalHybrid {
		t.Fatalf("%q: expected a hybrid page, got %s (%s)", q.Text, page.Retrieval, page.SemanticSkipped)
	}
	out := make([]string, 0, len(page.Hits))
	for _, h := range page.Hits {
		out = append(out, h.ATIN)
	}
	return out
}

func score(t *testing.T, jq relevance.Query, ranked []string) queryScore {
	t.Helper()
	ndcg, err := relevance.NDCG(ranked, jq.Judgements, 10)
	if err != nil {
		t.Fatalf("%q: %v", jq.Q, err)
	}
	p5, err := relevance.Precision(ranked, jq.Judgements, 5)
	if err != nil {
		t.Fatalf("%q: %v", jq.Q, err)
	}
	return queryScore{ndcg: ndcg, p5: p5}
}

// bootstrapIndex creates the market index the way the service does: the repository's synonym set
// (config/synonyms.yaml) loaded into Elasticsearch and referenced by the index settings.
func bootstrapIndex(ctx context.Context, es *elasticsearch.Client, market string) error {
	set, err := config.LoadSynonyms(nil, "../../../config/synonyms.yaml")
	if err != nil {
		return fmt.Errorf("synonyms: %w", err)
	}
	admin := elastic.NewAdmin(es, set.Name)
	if _, err := admin.SeedSynonyms(ctx, set.Rules, true); err != nil {
		return fmt.Errorf("seed synonyms: %w", err)
	}
	if err := admin.EnsureIndex(ctx, market); err != nil {
		return fmt.Errorf("ensure index: %w", err)
	}
	return nil
}

func fixtureDocs(t *testing.T, products []relevance.Product, embedder *relevance.FakeEmbedder) []searchindex.ProductDocument {
	t.Helper()
	now := time.Now().UTC()
	docs := make([]searchindex.ProductDocument, 0, len(products))
	for _, p := range products {
		dept, _, _ := strings.Cut(p.Path, " › ")
		kind, seller := searchindex.SellerMarket, "org_"+strings.ToLower(strings.ReplaceAll(p.Brand, " ", "_"))
		if p.Brand == "AfroTransact" {
			kind, seller = searchindex.SellerPlatform, "org_afrotransact_retail"
		}
		qty := 0
		if p.InStock {
			qty = 25
		}
		docs = append(docs, searchindex.ProductDocument{
			ATIN: p.ATIN, Title: p.Title, Brand: p.Brand, Description: p.Description, Department: dept,
			CategoryPath: p.Path, SellerID: seller, SellerName: p.Brand, SellerKind: kind, Fulfilment: p.Fulfilment,
			PriceMinor: p.PriceMinor, Currency: "USD", Rating: p.Rating, ReviewCount: p.Reviews, AvailableQty: qty,
			InStock: p.InStock, DeliveryDays: p.Days, OfferCount: 1, VariantATINs: []string{p.ATIN + "1"},
			Status: searchindex.StatusLive, Version: 1, UpdatedAt: now,
		})
		if embedder != nil {
			// What the indexer stores: the embedding of the shared document text (searchindex.EmbeddingText).
			vec, err := embedder.Embed(context.Background(), searchindex.EmbeddingText(p.Title, p.Brand, p.Path, p.Description))
			if err != nil {
				t.Fatal(err)
			}
			docs[len(docs)-1].Embedding = vec
		}
	}
	return docs
}
