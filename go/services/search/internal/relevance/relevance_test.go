package relevance_test

import (
	"errors"
	"math"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/relevance"
)

func TestNDCG(t *testing.T) {
	j := map[string]int{"a": 3, "b": 2, "c": 1}
	perfect, err := relevance.NDCG([]string{"a", "b", "c", "x"}, j, 10)
	if err != nil || math.Abs(perfect-1) > 1e-9 {
		t.Fatalf("perfect order = %v, %v", perfect, err)
	}
	swapped, _ := relevance.NDCG([]string{"c", "b", "a"}, j, 10)
	if swapped >= perfect || swapped <= 0 {
		t.Fatalf("swapped order = %v", swapped)
	}
	missing, _ := relevance.NDCG([]string{"x", "y"}, j, 10)
	if missing != 0 {
		t.Fatalf("no relevant results = %v", missing)
	}
	if _, err := relevance.NDCG([]string{"a"}, map[string]int{"a": 0}, 10); !errors.Is(err, relevance.ErrNoJudgements) {
		t.Fatalf("expected ErrNoJudgements, got %v", err)
	}
}

func TestPrecisionIsCappedByRelevantCount(t *testing.T) {
	j := map[string]int{"a": 3, "b": 1}
	p, err := relevance.Precision([]string{"a", "b", "x", "y", "z"}, j, 5)
	if err != nil || p != 1 {
		t.Fatalf("precision = %v, %v", p, err)
	}
	p, _ = relevance.Precision([]string{"x", "a"}, j, 5)
	if p != 0.5 {
		t.Fatalf("precision = %v", p)
	}
}

func TestRepositorySuiteLoads(t *testing.T) {
	suite, err := relevance.LoadSuite("../../relevance/judgements.yaml")
	if err != nil {
		t.Fatal(err)
	}
	products, err := relevance.LoadProducts("../../relevance/products.yaml")
	if err != nil {
		t.Fatal(err)
	}
	known := map[string]bool{}
	for _, p := range products {
		if known[p.ATIN] {
			t.Fatalf("duplicate fixture %s", p.ATIN)
		}
		known[p.ATIN] = true
	}
	for _, q := range suite.Queries {
		for atin := range q.Judgements {
			if !known[atin] {
				t.Fatalf("query %q judges unknown ATIN %s", q.Q, atin)
			}
		}
	}
	if suite.Thresholds.NDCG10 <= 0 || suite.Thresholds.Precision5 <= 0 {
		t.Fatal("thresholds must be set")
	}
}
