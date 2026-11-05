package elastic_test

import (
	"context"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// TestSynonymCurationReloadsWithoutReindex: a staff edit to the synonyms set changes results immediately.
func TestSynonymCurationReloadsWithoutReindex(t *testing.T) {
	ctx := context.Background()
	es := sharedES(t)
	admin := elastic.NewAdmin(es, "curation-test")
	// The indexer may have created the set empty first: an empty set is still seeded.
	putEmptySet(t, es, "curation-test")
	if wrote, err := admin.SeedSynonyms(ctx, []domain.SynonymRule{{ID: "gari", Synonyms: "gari, garri"}}, false); err != nil || !wrote {
		t.Fatalf("seed an empty set: %v %v", wrote, err)
	}
	if err := admin.EnsureIndex(ctx, "cu"); err != nil {
		t.Fatal(err)
	}
	uses, err := admin.UsesSynonymsSet(ctx, "cu")
	if err != nil || !uses {
		t.Fatalf("new index references the set: %v %v", uses, err)
	}
	bulkIndex(t, es, "cu", []searchindex.ProductDocument{{ATIN: "AT1OGB0001", Title: "Ogbono Seeds, Ground",
		Department: "Groceries", Status: "LIVE", InStock: true, Fulfilment: "SELLER", UpdatedAt: time.Now().UTC()}})
	idx := elastic.NewIndex(es)
	search := func(text string) int64 {
		q, _ := domain.Query{Market: "cu", Text: text}.Normalize()
		page, err := idx.Search(ctx, q, nil)
		if err != nil {
			t.Fatal(err)
		}
		return page.Total
	}
	if n := search("bush mango"); n != 0 {
		t.Fatalf("bush mango before curation = %d", n)
	}
	if err := admin.PutSynonym(ctx, domain.SynonymRule{ID: "ogbono", Synonyms: "ogbono, bush mango"}); err != nil {
		t.Fatal(err)
	}
	if n := search("bush mango"); n != 1 {
		t.Fatalf("bush mango after curation = %d", n)
	}
	rules, err := admin.Synonyms(ctx)
	if err != nil || len(rules) != 2 {
		t.Fatalf("rules = %+v %v", rules, err)
	}
	if found, err := admin.DeleteSynonym(ctx, "ogbono"); err != nil || !found {
		t.Fatalf("delete: %v %v", found, err)
	}
	if found, _ := admin.DeleteSynonym(ctx, "ogbono"); found {
		t.Fatal("second delete finds nothing")
	}
	// Seeding again without overwrite keeps staff edits.
	if wrote, err := admin.SeedSynonyms(ctx, []domain.SynonymRule{{ID: "x", Synonyms: "a, b"}}, false); err != nil || wrote {
		t.Fatalf("seed over an existing set: %v %v", wrote, err)
	}
}

// TestInlineSynonymIndexMigratesToTheSet: an index created by the shared mapping (inline synonyms) is switched.
func TestInlineSynonymIndexMigratesToTheSet(t *testing.T) {
	ctx := context.Background()
	es := sharedES(t)
	if err := searchindex.NewAdmin(es).EnsureIndex(ctx, "mg"); err != nil {
		t.Fatal(err)
	}
	admin := elastic.NewAdmin(es, "migration-test")
	if _, err := admin.SeedSynonyms(ctx, []domain.SynonymRule{{ID: "kpekere", Synonyms: "kpekere, plantain chips"}}, true); err != nil {
		t.Fatal(err)
	}
	if uses, _ := admin.UsesSynonymsSet(ctx, "mg"); uses {
		t.Fatal("inline index reported as migrated")
	}
	if err := admin.MigrateToSynonymsSet(ctx, "mg"); err != nil {
		t.Fatal(err)
	}
	if uses, err := admin.UsesSynonymsSet(ctx, "mg"); err != nil || !uses {
		t.Fatalf("after migration: %v %v", uses, err)
	}
	bulkIndex(t, es, "mg", []searchindex.ProductDocument{{ATIN: "AT1PLA0009", Title: "Plantain Chips, Salted",
		Status: "LIVE", InStock: true, Fulfilment: "SELLER", UpdatedAt: time.Now().UTC()}})
	q, _ := domain.Query{Market: "mg", Text: "kpekere"}.Normalize()
	page, err := elastic.NewIndex(es).Search(ctx, q, nil)
	if err != nil || page.Total != 1 {
		t.Fatalf("kpekere after migration: %+v %v", page.Total, err)
	}
}

func TestAdCandidatesOnlyLiveInStockMatches(t *testing.T) {
	ctx := context.Background()
	es := sharedES(t)
	admin := elastic.NewAdmin(es, "ads-test")
	if _, err := admin.SeedSynonyms(ctx, []domain.SynonymRule{{ID: "egusi", Synonyms: "egusi, agushi"}}, true); err != nil {
		t.Fatal(err)
	}
	if err := admin.EnsureIndex(ctx, "ad"); err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	doc := func(atin, title, status string, inStock bool) searchindex.ProductDocument {
		return searchindex.ProductDocument{ATIN: atin, Title: title, Status: status, InStock: inStock, SellerID: "org_1",
			Fulfilment: "SELLER", Department: "Groceries", VariantATINs: []string{atin + "V"}, UpdatedAt: now}
	}
	bulkIndex(t, es, "ad", []searchindex.ProductDocument{
		doc("AT1ADS0001", "Ground Egusi", "LIVE", true),
		doc("AT1ADS0002", "Agushi Seeds", "LIVE", true),
		doc("AT1ADS0003", "Egusi Out of Stock", "LIVE", false),
		doc("AT1ADS0004", "Egusi From A Hidden Seller", "SELLER_HIDDEN", true),
		doc("AT1ADS0005", "Shea Butter", "LIVE", true),
	})
	q, _ := domain.Query{Market: "ad", Text: "egusi"}.Normalize()
	hits, err := elastic.NewIndex(es).AdCandidates(ctx, q,
		[]string{"AT1ADS0001", "AT1ADS0002", "AT1ADS0003", "AT1ADS0004", "AT1ADS0005"})
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]domain.Hit{}
	for _, h := range hits {
		got[h.ATIN] = h
	}
	if len(got) != 2 || got["AT1ADS0001"].Score <= 0 || got["AT1ADS0002"].SellerID != "org_1" ||
		got["AT1ADS0001"].VariantATINs[0] != "AT1ADS0001V" {
		t.Fatalf("candidates = %+v", hits)
	}
}

func day(s string) time.Time {
	t, _ := time.Parse(time.DateOnly, s)
	return t
}
