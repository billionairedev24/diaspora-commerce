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
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/relevance"
)

// TestRelevanceJudgements indexes relevance/products.yaml with the production index settings and query, runs every
// query of relevance/judgements.yaml and fails when mean NDCG@10 or precision@5 drops below the thresholds.
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
	bulkIndex(t, es, market, fixtureDocs(products))
	idx := elastic.NewIndex(es)

	var sumNDCG, sumP float64
	var report strings.Builder
	fmt.Fprintf(&report, "\n%-24s %8s %8s  top 5\n", "query", "ndcg@10", "p@5")
	for _, jq := range suite.Queries {
		q, err := domain.Query{Market: market, Text: jq.Q, Size: 10}.Normalize()
		if err != nil {
			t.Fatal(err)
		}
		page, err := idx.Search(ctx, q, nil)
		if err != nil {
			t.Fatalf("%q: %v", jq.Q, err)
		}
		ranked := make([]string, 0, len(page.Hits))
		for _, h := range page.Hits {
			ranked = append(ranked, h.ATIN)
		}
		ndcg, err := relevance.NDCG(ranked, jq.Judgements, 10)
		if err != nil {
			t.Fatalf("%q: %v", jq.Q, err)
		}
		p5, err := relevance.Precision(ranked, jq.Judgements, 5)
		if err != nil {
			t.Fatalf("%q: %v", jq.Q, err)
		}
		sumNDCG += ndcg
		sumP += p5
		fmt.Fprintf(&report, "%-24s %8.3f %8.3f  %s\n", jq.Q, ndcg, p5, strings.Join(ranked[:min(5, len(ranked))], " "))
	}
	n := float64(len(suite.Queries))
	meanNDCG, meanP := sumNDCG/n, sumP/n
	fmt.Fprintf(&report, "%-24s %8.3f %8.3f  (%d queries; thresholds %.2f / %.2f)\n", "MEAN", meanNDCG, meanP,
		len(suite.Queries), suite.Thresholds.NDCG10, suite.Thresholds.Precision5)
	t.Log(report.String())
	if meanNDCG < suite.Thresholds.NDCG10 {
		t.Errorf("mean NDCG@10 %.3f below threshold %.2f", meanNDCG, suite.Thresholds.NDCG10)
	}
	if meanP < suite.Thresholds.Precision5 {
		t.Errorf("mean precision@5 %.3f below threshold %.2f", meanP, suite.Thresholds.Precision5)
	}
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

func fixtureDocs(products []relevance.Product) []searchindex.ProductDocument {
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
	}
	return docs
}
