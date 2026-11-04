package meilisearch_test

import (
	"context"
	"fmt"
	"math/rand/v2"
	"net/http"
	"os"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/meilisearch"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// These tests run against a real Meilisearch: MEILISEARCH_TEST_URL=http://127.0.0.1:7700 (MEILISEARCH_TEST_KEY).
func client(t *testing.T) *meili.Client {
	t.Helper()
	url := os.Getenv("MEILISEARCH_TEST_URL")
	if url == "" {
		t.Skip("set MEILISEARCH_TEST_URL to run against Meilisearch")
	}
	return meili.New(url, os.Getenv("MEILISEARCH_TEST_KEY"), &http.Client{Timeout: 20 * time.Second})
}

type env struct {
	c      *meili.Client
	market string
	ctx    context.Context
}

func setup(t *testing.T, rules ...domain.SynonymRule) env {
	t.Helper()
	c := client(t)
	ctx := t.Context()
	market := fmt.Sprintf("t%d", rand.IntN(1_000_000))
	admin := meilisearch.NewAdmin(c, []string{market})
	if len(rules) > 0 {
		if _, err := admin.SeedSynonyms(ctx, rules, true); err != nil {
			t.Fatal(err)
		}
	}
	if err := admin.EnsureIndex(ctx, market); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		bg := context.WithoutCancel(ctx)
		_ = c.Task(bg, http.MethodDelete, "/indexes/"+searchindex.MeiliIndexUID(market), nil)
		_ = c.Task(bg, http.MethodDelete, "/indexes/"+meilisearch.AnalyticsIndex(market), nil)
	})
	return env{c: c, market: market, ctx: ctx}
}

func (e env) put(t *testing.T, docs ...searchindex.ProductDocument) {
	t.Helper()
	var batch []map[string]any
	for _, d := range docs {
		if d.Status == "" {
			d.Status = searchindex.StatusLive
		}
		m, err := searchindex.MeiliDocument(d)
		if err != nil {
			t.Fatal(err)
		}
		batch = append(batch, m)
	}
	if err := e.c.Put(e.ctx, searchindex.MeiliIndexUID(e.market), batch); err != nil {
		t.Fatal(err)
	}
}

func prod(atin, title string) searchindex.ProductDocument {
	return searchindex.ProductDocument{ATIN: atin, Title: title, Department: "Groceries", CategoryPath: "Groceries › Pantry", SellerID: "s1", SellerName: "Mama Put",
		SellerKind: searchindex.SellerMarket, Fulfilment: searchindex.ShipsFromSeller, PriceMinor: 1500, Currency: "USD", Rating: 4.5, ReviewCount: 20,
		AvailableQty: 10, InStock: true, DeliveryDays: 2, OfferCount: 1, Version: 1}
}

func (e env) search(t *testing.T, q domain.Query, vec []float32) domain.Page {
	t.Helper()
	q.Market = e.market
	q, err := q.Normalize()
	if err != nil {
		t.Fatal(err)
	}
	page, err := meilisearch.NewIndex(e.c).Search(e.ctx, q, vec)
	if err != nil {
		t.Fatal(err)
	}
	return page
}

func atins(p domain.Page) []string {
	out := []string{}
	for _, h := range p.Hits {
		out = append(out, h.ATIN)
	}
	return out
}

func TestSearchMatchesEveryWordToleratesTyposAndPrefixes(t *testing.T) {
	e := setup(t)
	e.put(t, prod("A1", "Shito Black Pepper Sauce"), prod("A2", "Palm Oil 1L"), prod("A3", "Black Soap Bar"))
	if got := atins(e.search(t, domain.Query{Text: "black pepper"}, nil)); len(got) != 1 || got[0] != "A1" {
		t.Fatalf("every word must match: %v", got)
	}
	if got := atins(e.search(t, domain.Query{Text: "shitto"}, nil)); len(got) != 1 || got[0] != "A1" {
		t.Fatalf("one typo tolerated: %v", got)
	}
	if got := atins(e.search(t, domain.Query{Text: "pal"}, nil)); len(got) != 1 || got[0] != "A2" {
		t.Fatalf("search as you type: %v", got)
	}
}

func TestSearchAppliesFiltersAndLiveOnly(t *testing.T) {
	e := setup(t)
	cheap := prod("B1", "Gari Cassava Flakes")
	cheap.PriceMinor = 800
	pricey := prod("B2", "Gari Premium Flakes")
	pricey.PriceMinor = 3000
	pricey.Fulfilment = searchindex.FulfilledByAfroTransact
	hidden := prod("B3", "Gari Hidden")
	hidden.Status = "SELLER_HIDDEN"
	oos := prod("B4", "Gari Sold Out")
	oos.InStock, oos.AvailableQty = false, 0
	e.put(t, cheap, pricey, hidden, oos)
	all := atins(e.search(t, domain.Query{Text: "gari"}, nil))
	if len(all) != 3 || contains(all, "B3") {
		t.Fatalf("live products only: %v", all)
	}
	if got := atins(e.search(t, domain.Query{Text: "gari", MaxPrice: 1000}, nil)); len(got) != 1 || got[0] != "B1" {
		t.Fatalf("max price: %v", got)
	}
	if got := atins(e.search(t, domain.Query{Text: "gari", Fulfilment: "AFROTRANSACT"}, nil)); len(got) != 1 || got[0] != "B2" {
		t.Fatalf("fulfilment: %v", got)
	}
	if got := atins(e.search(t, domain.Query{Text: "gari", InStockOnly: true}, nil)); contains(got, "B4") {
		t.Fatalf("in stock only: %v", got)
	}
}

func TestSearchRanksInStockAndBoostedFirstAndSortsExplicitly(t *testing.T) {
	e := setup(t)
	oos := prod("C1", "Jollof Seasoning")
	oos.InStock, oos.AvailableQty = false, 0
	plain := prod("C2", "Jollof Seasoning")
	plain.Rating, plain.ReviewCount = 0, 0
	boosted := prod("C3", "Jollof Seasoning")
	boosted.Fulfilment = searchindex.FulfilledByAfroTransact
	e.put(t, oos, plain, boosted)
	got := atins(e.search(t, domain.Query{Text: "jollof seasoning"}, nil))
	if len(got) != 3 || got[2] != "C1" || got[0] != "C3" {
		t.Fatalf("in stock and fulfilled first, out of stock last: %v", got)
	}
	cheap, pricey := prod("C4", "Egusi"), prod("C5", "Egusi")
	cheap.PriceMinor, pricey.PriceMinor = 500, 900
	e.put(t, cheap, pricey)
	if got := atins(e.search(t, domain.Query{Text: "egusi", Sort: domain.SortPriceDesc}, nil)); len(got) != 2 || got[0] != "C5" {
		t.Fatalf("price desc: %v", got)
	}
	if got := atins(e.search(t, domain.Query{Text: "egusi", Sort: domain.SortPriceAsc}, nil)); got[0] != "C4" {
		t.Fatalf("price asc: %v", got)
	}
}

func TestSearchFacetsCountTermsAndRanges(t *testing.T) {
	e := setup(t)
	a, b, c := prod("D1", "Tea One"), prod("D2", "Tea Two"), prod("D3", "Tea Three")
	a.PriceMinor, b.PriceMinor, c.PriceMinor = 500, 1800, 6000
	b.Department, c.Rating = "Beverages", 3.2
	e.put(t, a, b, c)
	p := e.search(t, domain.Query{Text: "tea"}, nil)
	if p.Total != 3 {
		t.Fatalf("total %d", p.Total)
	}
	count := func(facet, value string) int64 {
		for _, bk := range p.Facets[facet] {
			if bk.Value == value {
				return bk.Count
			}
		}
		return 0
	}
	if count("department", "Groceries") != 2 || count("department", "Beverages") != 1 {
		t.Fatalf("department facet: %v", p.Facets["department"])
	}
	if count("price", "under-10") != 1 || count("price", "10-25") != 1 || count("price", "50-plus") != 1 || count("price", "25-50") != 0 {
		t.Fatalf("price facet: %v", p.Facets["price"])
	}
	if count("rating", "4+") != 2 || count("rating", "3+") != 3 {
		t.Fatalf("rating facet: %v", p.Facets["rating"])
	}
}

func TestSearchSynonymsAndGeoDistance(t *testing.T) {
	e := setup(t, domain.SynonymRule{ID: "gari", Synonyms: "gari, garri, cassava flakes"})
	near, far := prod("E1", "Garri Fine"), prod("E2", "Garri Coarse")
	near.ShipsFrom = &searchindex.GeoPoint{Lat: 29.76, Lon: -95.37} // Houston
	far.ShipsFrom = &searchindex.GeoPoint{Lat: 40.71, Lon: -74.0}   // New York
	e.put(t, near, far)
	if got := atins(e.search(t, domain.Query{Text: "gari"}, nil)); len(got) != 2 {
		t.Fatalf("synonym garri ~ gari: %v", got)
	}
	got := atins(e.search(t, domain.Query{Text: "garri", NearLat: 29.7, NearLon: -95.4, WithinMiles: 50}, nil))
	if len(got) != 1 || got[0] != "E1" {
		t.Fatalf("within 50 miles of Houston: %v", got)
	}
}

func TestSuggestCompletesPhrasesAndProducts(t *testing.T) {
	e := setup(t)
	e.put(t, prod("F1", "Jollof Seasoning Blend, 8 oz"), prod("F2", "Jollof Rice Tray"))
	s, err := meilisearch.NewIndex(e.c).Suggest(e.ctx, e.market, "jol", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(s.Products) != 2 || len(s.Phrases) == 0 || s.Phrases[0].Text != "jollof seasoning" || s.Phrases[0].Department != "Groceries" {
		t.Fatalf("suggest: %+v", s)
	}
}

func TestAdCandidatesOnlyInStockAndMatching(t *testing.T) {
	e := setup(t)
	oos := prod("G2", "Suya Spice")
	oos.InStock, oos.AvailableQty = false, 0
	e.put(t, prod("G1", "Suya Spice"), oos, prod("G3", "Palm Oil"))
	q, _ := domain.Query{Market: e.market, Text: "suya"}.Normalize()
	hits, err := meilisearch.NewIndex(e.c).AdCandidates(e.ctx, q, []string{"G1", "G2", "G3"})
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) != 1 || hits[0].ATIN != "G1" || hits[0].Score <= 0 {
		t.Fatalf("ad candidates: %+v", hits)
	}
}

func TestHybridSearchFusesLexicalAndVectorResults(t *testing.T) {
	e := setup(t)
	vec := func(i int) []float32 {
		v := make([]float32, searchindex.EmbeddingDimensions)
		v[i] = 1
		return v
	}
	spicy, other := prod("H1", "Hot pepper paste"), prod("H2", "Plain rice")
	spicy.Embedding, other.Embedding = vec(0), vec(1)
	e.put(t, spicy, other)
	// "fiery sauce" matches nothing as text; its embedding is closest to the pepper paste.
	p := e.search(t, domain.Query{Text: "fiery sauce"}, vec(0))
	if p.Retrieval != domain.RetrievalHybrid || len(p.Hits) == 0 || p.Hits[0].ATIN != "H1" {
		t.Fatalf("hybrid: retrieval %s hits %v", p.Retrieval, atins(p))
	}
}

func TestSynonymRulesRoundTrip(t *testing.T) {
	e := setup(t)
	admin := meilisearch.NewAdmin(e.c, []string{e.market})
	if _, err := admin.SeedSynonyms(e.ctx, []domain.SynonymRule{{ID: "egusi", Synonyms: "egusi, melon seeds"}}, true); err != nil {
		t.Fatal(err)
	}
	if err := admin.PutSynonym(e.ctx, domain.SynonymRule{ID: "ugu", Synonyms: "ugu, pumpkin leaf"}); err != nil {
		t.Fatal(err)
	}
	rules, err := admin.Synonyms(e.ctx)
	if err != nil || len(rules) != 2 || rules[0].ID != "egusi" {
		t.Fatalf("rules %v %v", rules, err)
	}
	e.put(t, prod("I1", "Pumpkin Leaf Fresh"))
	if got := atins(e.search(t, domain.Query{Text: "ugu"}, nil)); len(got) != 1 {
		t.Fatalf("a new rule applies to the index without reindexing: %v", got)
	}
	if ok, err := admin.DeleteSynonym(e.ctx, "ugu"); err != nil || !ok {
		t.Fatalf("delete: %v %v", ok, err)
	}
	if ok, _ := admin.DeleteSynonym(e.ctx, "ugu"); ok {
		t.Fatal("deleting twice reports it was gone")
	}
	if got := atins(e.search(t, domain.Query{Text: "ugu"}, nil)); len(got) != 0 {
		t.Fatalf("a deleted rule stops applying: %v", got)
	}
}

func TestAnalyticsAccumulatesAndReports(t *testing.T) {
	e := setup(t)
	if err := meilisearch.EnsureAnalyticsIndex(e.ctx, e.c, e.market); err != nil {
		t.Fatal(err)
	}
	store := meilisearch.NewAnalyticsStore(e.c)
	today := time.Now().UTC()
	day := domain.Day(today)
	add := func(q string, s, z, c int64) domain.AnalyticsDelta {
		return domain.AnalyticsDelta{Market: e.market, Day: day, Query: q, Searches: s, ZeroResults: z, Clicks: c}
	}
	if err := store.Add(e.ctx, []domain.AnalyticsDelta{add("gari", 3, 0, 1), add("unicorn", 2, 2, 0)}); err != nil {
		t.Fatal(err)
	}
	if err := store.Add(e.ctx, []domain.AnalyticsDelta{add("gari", 2, 0, 1)}); err != nil {
		t.Fatal(err)
	}
	r := domain.AnalyticsRange{Market: e.market, From: today.AddDate(0, 0, -1), To: today, Limit: 10}
	top, err := store.TopQueries(e.ctx, r)
	if err != nil || len(top) != 2 || top[0].Query != "gari" || top[0].Searches != 5 || top[0].Clicks != 2 {
		t.Fatalf("top %v %v", top, err)
	}
	zero, err := store.ZeroResultQueries(e.ctx, r)
	if err != nil || len(zero) != 1 || zero[0].Query != "unicorn" {
		t.Fatalf("zero %v %v", zero, err)
	}
	days, err := store.DailyCTR(e.ctx, r)
	if err != nil || len(days) != 2 || days[1].Searches != 7 || days[0].Searches != 0 {
		t.Fatalf("days %v %v", days, err)
	}
	if err := store.Purge(e.ctx, e.market, today.AddDate(0, 0, 1)); err != nil {
		t.Fatal(err)
	}
	if top, _ := store.TopQueries(e.ctx, r); len(top) != 0 {
		t.Fatalf("purged: %v", top)
	}
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}
