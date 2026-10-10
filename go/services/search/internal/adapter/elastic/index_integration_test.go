package elastic_test

import (
	"context"
	"testing"
	"time"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

func TestElasticsearchSearch(t *testing.T) {
	ctx := context.Background()
	es := sharedES(t)
	if err := searchindex.NewAdmin(es).EnsureIndex(ctx, "us"); err != nil {
		t.Fatal(err)
	}
	seed(t, es)
	idx := elastic.NewIndex(es)

	cases := []struct {
		name  string
		q     domain.Query
		want  string
		total int64
	}{
		{"exact words", domain.Query{Text: "jollof seasoning"}, "AT00000001", 1},
		{"typos within edit distance", domain.Query{Text: "jolof seasonng"}, "AT00000001", 1},
		{"long word two typos", domain.Query{Text: "basmti"}, "AT00000002", 1},
		{"partial word", domain.Query{Text: "basm"}, "AT00000002", 1},
		{"synonym zobo finds bissap", domain.Query{Text: "zobo"}, "AT00000003", 1},
		{"synonym garri finds gari", domain.Query{Text: "garri"}, "AT00000004", 1},
		{"every word must match", domain.Query{Text: "jollof shea"}, "", 0},
		{"fulfilment filter", domain.Query{Fulfilment: "SELLER"}, "AT00000003", 1},
		{"price sort", domain.Query{Sort: domain.SortPriceDesc}, "AT00000002", 4},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			q, err := tc.q.Normalize()
			if err != nil {
				t.Fatal(err)
			}
			page, err := idx.Search(ctx, q, nil)
			if err != nil {
				t.Fatal(err)
			}
			if page.Total != tc.total {
				t.Fatalf("total = %d, want %d (%+v)", page.Total, tc.total, page.Hits)
			}
			if tc.want != "" && page.Hits[0].ATIN != tc.want {
				t.Fatalf("top hit = %s, want %s", page.Hits[0].ATIN, tc.want)
			}
		})
	}

	t.Run("facets", func(t *testing.T) {
		q, _ := domain.Query{}.Normalize()
		page, err := idx.Search(ctx, q, nil)
		if err != nil {
			t.Fatal(err)
		}
		if len(page.Facets["department"]) == 0 || len(page.Facets["fulfilment"]) != 2 {
			t.Fatalf("facets missing: %+v", page.Facets)
		}
	})

	t.Run("suggest", func(t *testing.T) {
		out, err := idx.Suggest(ctx, "us", "jol", 5)
		if err != nil {
			t.Fatal(err)
		}
		if len(out.Products) == 0 || out.Products[0].ATIN != "AT00000001" {
			t.Fatalf("products = %+v", out.Products)
		}
		if len(out.Phrases) == 0 || out.Phrases[0].Text != "jollof seasoning" || out.Phrases[0].Department != "Groceries" {
			t.Fatalf("phrases = %+v", out.Phrases)
		}
	})
}

func seed(t *testing.T, es *elasticsearch.Client) {
	t.Helper()
	now := time.Now().UTC()
	docs := []searchindex.ProductDocument{
		{ATIN: "AT00000001", Title: "Jollof Seasoning Blend, 8 oz", Brand: "AfroTransact", Department: "Groceries",
			CategoryPath: "Groceries › Pantry › Spices & seasonings", Communities: []string{"Nigerian", "Ghanaian"},
			SellerID: "org_afrotransact_retail", SellerName: "AfroTransact Retail", SellerKind: "PLATFORM",
			Fulfilment: "AFROTRANSACT", PriceMinor: 899, Currency: "USD", Rating: 4.7, ReviewCount: 210,
			AvailableQty: 3, InStock: true, DeliveryDays: 1, Status: "LIVE", UpdatedAt: now},
		{ATIN: "AT00000002", Title: "Basmati Rice, Extra Long Grain, 10 lb", Brand: "AfroTransact", Department: "Groceries",
			CategoryPath: "Groceries › Pantry › Grains & rice", SellerID: "org_afrotransact_retail",
			SellerName: "AfroTransact Retail", SellerKind: "PLATFORM", Fulfilment: "AFROTRANSACT", PriceMinor: 2499,
			Currency: "USD", Rating: 4.5, ReviewCount: 98, AvailableQty: 50, InStock: true, DeliveryDays: 2, Status: "LIVE", UpdatedAt: now},
		{ATIN: "AT00000003", Title: "Bissap Hibiscus Leaves, Dried", Brand: "Dakar Pantry", Department: "Groceries",
			CategoryPath: "Groceries › Drinks › Teas & hibiscus", Communities: []string{"Senegalese"},
			SellerID: "org_dakar", SellerName: "Dakar Pantry", SellerKind: "SELLER", Fulfilment: "SELLER",
			PriceMinor: 699, Currency: "USD", Rating: 4.2, ReviewCount: 31, AvailableQty: 12, InStock: true,
			DeliveryDays: 4, Status: "LIVE", UpdatedAt: now},
		{ATIN: "AT00000004", Title: "Gari Ijebu, Fine, 4 lb", Brand: "Mama Tee", Department: "Groceries",
			CategoryPath: "Groceries › Pantry › Grains & rice", SellerID: "org_mamatee", SellerName: "Mama Tee's Pantry",
			SellerKind: "SELLER", Fulfilment: "AFROTRANSACT", PriceMinor: 1299, Currency: "USD", Rating: 4.8,
			ReviewCount: 77, AvailableQty: 20, InStock: true, DeliveryDays: 2, Status: "LIVE", UpdatedAt: now},
	}
	bulkIndex(t, es, "us", docs)
}

var _ port.Index = elastic.NewIndex(nil)
