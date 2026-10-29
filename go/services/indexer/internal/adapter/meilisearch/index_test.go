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
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/meilisearch"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// These tests run against a real Meilisearch: MEILISEARCH_TEST_URL=http://127.0.0.1:7700 (MEILISEARCH_TEST_KEY).
func setup(t *testing.T) (port.ProductIndex, *meili.Client, string) {
	t.Helper()
	url := os.Getenv("MEILISEARCH_TEST_URL")
	if url == "" {
		t.Skip("set MEILISEARCH_TEST_URL to run against Meilisearch")
	}
	c := meili.New(url, os.Getenv("MEILISEARCH_TEST_KEY"), &http.Client{Timeout: 20 * time.Second})
	market := fmt.Sprintf("i%d", rand.IntN(1_000_000))
	if err := meilisearch.NewIndexAdmin(c).EnsureIndex(t.Context(), market); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = c.Task(context.WithoutCancel(t.Context()), http.MethodDelete, "/indexes/"+searchindex.MeiliIndexUID(market), nil)
	})
	return meilisearch.NewIndex(c), c, market
}

func product(atin string, version int64) searchindex.ProductDocument {
	return searchindex.ProductDocument{ATIN: atin, Title: "Gari " + atin, Department: "Groceries", CategoryPath: "Groceries › Pantry", SellerID: "s1",
		SellerName: "Mama Put", SellerKind: searchindex.SellerMarket, Fulfilment: searchindex.ShipsFromSeller, PriceMinor: 900, Currency: "USD",
		Rating: 4, ReviewCount: 5, DeliveryDays: 2, OfferCount: 1, VariantATINs: []string{atin + "-V1"}, Status: searchindex.StatusLive, Version: version}
}

func get(t *testing.T, c *meili.Client, market, atin string) map[string]any {
	t.Helper()
	d, err := c.Get(t.Context(), searchindex.MeiliIndexUID(market), atin)
	if err != nil || d == nil {
		t.Fatalf("get %s: %v %v", atin, d, err)
	}
	return d
}

func TestUpsertIsVersionGuardedAndKeepsAvailability(t *testing.T) {
	idx, c, market := setup(t)
	ctx := t.Context()
	if err := idx.UpsertProduct(ctx, market, product("P1", 5), 5, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	if ok, err := idx.ApplyAvailability(ctx, market, "P1-V1", "dc1", 7, 1); err != nil || !ok {
		t.Fatalf("availability: %v %v", ok, err)
	}
	d := get(t, c, market, "P1")
	if searchindex.MeiliNum(d["available_qty"]) != 7 || d["in_stock"] != true || searchindex.MeiliNum(d["in_stock_rank"]) != 1 {
		t.Fatalf("availability applied: %v", d)
	}
	// A product update does not reset what availability set, and an older version is ignored.
	newer := product("P1", 6)
	newer.Title = "Gari Premium"
	if err := idx.UpsertProduct(ctx, market, newer, 6, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	older := product("P1", 3)
	older.Title = "Stale"
	if err := idx.UpsertProduct(ctx, market, older, 3, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	d = get(t, c, market, "P1")
	if d["title"] != "Gari Premium" || searchindex.MeiliNum(d["available_qty"]) != 7 || searchindex.MeiliNum(d["version"]) != 6 {
		t.Fatalf("version guard: %v", d)
	}
}

func TestAvailabilityHonoursBuyBoxAndOlderQuantities(t *testing.T) {
	idx, c, market := setup(t)
	ctx := t.Context()
	if err := idx.UpsertProduct(ctx, market, product("P2", 1), 1, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	for _, step := range []struct {
		node    string
		qty     int
		version int64
	}{{"dc1", 4, 1}, {"dc2", 6, 1}, {"BUYBOX", 3, 2}} {
		if _, err := idx.ApplyAvailability(ctx, market, "P2-V1", step.node, step.qty, step.version); err != nil {
			t.Fatal(err)
		}
	}
	if q := searchindex.MeiliNum(get(t, c, market, "P2")["available_qty"]); q != 3 {
		t.Fatalf("the buy box quantity is what the page sells, got %v", q)
	}
	if _, err := idx.ApplyAvailability(ctx, market, "P2-V1", "BUYBOX", 99, 1); err != nil { // older version
		t.Fatal(err)
	}
	if q := searchindex.MeiliNum(get(t, c, market, "P2")["available_qty"]); q != 3 {
		t.Fatalf("an older quantity must not win, got %v", q)
	}
	if ok, err := idx.ApplyAvailability(ctx, market, "NOPE-V1", "dc1", 1, 1); err != nil || ok {
		t.Fatalf("unknown variant reports false: %v %v", ok, err)
	}
}

func TestRenameSellerAndPreserveName(t *testing.T) {
	idx, c, market := setup(t)
	ctx := t.Context()
	for _, a := range []string{"P3", "P4"} {
		if err := idx.UpsertProduct(ctx, market, product(a, 1), 1, port.UpsertOptions{}); err != nil {
			t.Fatal(err)
		}
	}
	n, err := idx.RenameSeller(ctx, market, "s1", "Mama Put Pantry")
	if err != nil || n != 2 {
		t.Fatalf("rename: %d %v", n, err)
	}
	if n, _ := idx.RenameSeller(ctx, market, "s1", "Mama Put Pantry"); n != 0 {
		t.Fatalf("renaming to the same name changes nothing, got %d", n)
	}
	fallback := product("P3", 2)
	fallback.SellerName = "Seller 1"
	if err := idx.UpsertProduct(ctx, market, fallback, 2, port.UpsertOptions{PreserveSellerName: true}); err != nil {
		t.Fatal(err)
	}
	if got := get(t, c, market, "P3")["seller_name"]; got != "Mama Put Pantry" {
		t.Fatalf("a fallback name must not overwrite a known one: %v", got)
	}
}

func TestEmbeddingBackfillIsGuardedByVersion(t *testing.T) {
	idx, c, market := setup(t)
	ctx := t.Context()
	for _, a := range []string{"E1", "E2", "E3"} {
		if err := idx.UpsertProduct(ctx, market, product(a, 1), 1, port.UpsertOptions{}); err != nil {
			t.Fatal(err)
		}
	}
	cands, err := idx.MissingEmbeddings(ctx, market, "", 2)
	if err != nil || len(cands) != 2 || cands[0].ATIN != "E1" || cands[1].ATIN != "E2" {
		t.Fatalf("first page: %v %v", cands, err)
	}
	next, err := idx.MissingEmbeddings(ctx, market, "E2", 10)
	if err != nil || len(next) != 1 || next[0].ATIN != "E3" {
		t.Fatalf("after E2: %v %v", next, err)
	}
	vec := make([]float32, searchindex.EmbeddingDimensions)
	vec[0] = 1
	if ok, err := idx.SetEmbedding(ctx, market, "E1", vec, 1); err != nil || !ok {
		t.Fatalf("set: %v %v", ok, err)
	}
	if ok, _ := idx.SetEmbedding(ctx, market, "E2", vec, 99); ok {
		t.Fatal("a changed product must not receive an embedding computed from the old text")
	}
	left, _ := idx.MissingEmbeddings(ctx, market, "", 10)
	if len(left) != 2 {
		t.Fatalf("E1 has its vector now: %v", left)
	}
	// An update without a vector keeps the stored one.
	if err := idx.UpsertProduct(ctx, market, product("E1", 2), 2, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	if d := get(t, c, market, "E1"); d["has_embedding"] != true {
		t.Fatalf("embedding lost on update: %v", d)
	}
	if err := idx.Ping(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestRatingIsSetGuardedByVersionAndSurvivesProductUpserts(t *testing.T) {
	idx, c, market := setup(t)
	ctx := t.Context()
	fresh := product("P9", 1)
	fresh.Rating, fresh.ReviewCount = 0, 0
	if err := idx.UpsertProduct(ctx, market, fresh, 1, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	if ok, err := idx.ApplyRating(ctx, market, "P9", 4.33, 3, 10); err != nil || !ok {
		t.Fatalf("rating: %v %v", ok, err)
	}
	d := get(t, c, market, "P9")
	if searchindex.MeiliNum(d["rating"]) != 4.33 || searchindex.MeiliNum(d["review_count"]) != 3 {
		t.Fatalf("rating applied: %v", d)
	}
	boost := searchindex.MeiliNum(d["rank_boost"])
	// A product update (which carries rating 0 in its document) keeps the rating.
	again := product("P9", 11)
	again.Rating, again.ReviewCount = 0, 0
	again.Title = "Gari Premium"
	if err := idx.UpsertProduct(ctx, market, again, 11, port.UpsertOptions{}); err != nil {
		t.Fatal(err)
	}
	d = get(t, c, market, "P9")
	if searchindex.MeiliNum(d["rating"]) != 4.33 || searchindex.MeiliNum(d["review_count"]) != 3 || d["title"] != "Gari Premium" {
		t.Fatalf("an upsert must not reset the rating: %v", d)
	}
	if searchindex.MeiliNum(d["rank_boost"]) != boost {
		t.Fatalf("rank boost follows the rating: %v vs %v", d["rank_boost"], boost)
	}
	// An older rating is ignored, a newer one wins, and an unknown product reports false.
	if _, err := idx.ApplyRating(ctx, market, "P9", 1, 1, 5); err != nil {
		t.Fatal(err)
	}
	if got := searchindex.MeiliNum(get(t, c, market, "P9")["rating"]); got != 4.33 {
		t.Fatalf("an older rating must not win, got %v", got)
	}
	if _, err := idx.ApplyRating(ctx, market, "P9", 0, 0, 20); err != nil {
		t.Fatal(err)
	}
	if got := searchindex.MeiliNum(get(t, c, market, "P9")["review_count"]); got != 0 {
		t.Fatalf("a product that lost its last review has none, got %v", got)
	}
	if ok, err := idx.ApplyRating(ctx, market, "NOPE", 5, 1, 1); err != nil || ok {
		t.Fatalf("unknown product reports false: %v %v", ok, err)
	}
}
