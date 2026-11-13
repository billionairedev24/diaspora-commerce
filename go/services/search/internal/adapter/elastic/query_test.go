package elastic

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

func TestEligibleOnlyFiltersOnStockAndPhoto(t *testing.T) {
	plain, _ := json.Marshal(buildFilters(domain.Query{}))
	if strings.Contains(string(plain), "photo_ok") {
		t.Fatalf("an ordinary search must not filter on photo_ok: %s", plain)
	}
	eligible, _ := json.Marshal(buildFilters(domain.Query{EligibleOnly: true}))
	for _, want := range []string{`"status":"LIVE"`, `"in_stock":true`, `"photo_ok":true`} {
		if !strings.Contains(string(eligible), want) {
			t.Fatalf("eligible filters %s lack %s", eligible, want)
		}
	}
}

func TestDistinctSellersAggregationOnlyWhenEligibleOnly(t *testing.T) {
	if _, ok := facets(domain.Query{})["distinct_sellers"]; ok {
		t.Fatal("distinct_sellers asked for an ordinary search")
	}
	agg, ok := facets(domain.Query{EligibleOnly: true})["distinct_sellers"]
	if !ok {
		t.Fatal("distinct_sellers missing for eligibleOnly")
	}
	raw, _ := json.Marshal(agg)
	if !strings.Contains(string(raw), "cardinality") || !strings.Contains(string(raw), "seller_id") {
		t.Fatalf("unexpected aggregation %s", raw)
	}
}

func TestFacetsOfLeavesCardinalityOutOfFacets(t *testing.T) {
	v := 7.0
	res := response{}
	res.Aggregations = map[string]struct {
		Buckets []bucket `json:"buckets"`
		Value   *float64 `json:"value"`
	}{"distinct_sellers": {Value: &v}, "department": {Buckets: []bucket{{Key: "Groceries", DocCount: 3}}}}
	f := facetsOf(res)
	if _, ok := f["distinct_sellers"]; ok || len(f["department"]) != 1 {
		t.Fatalf("facets: %+v", f)
	}
	if distinctSellers(res) != 7 {
		t.Fatalf("distinctSellers = %d", distinctSellers(res))
	}
}
