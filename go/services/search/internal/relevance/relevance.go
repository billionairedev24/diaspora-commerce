// Package relevance holds the offline relevance harness (S039): the judgement list and fixtures under
// go/services/search/relevance, and the NDCG@k / precision@k metrics the Elasticsearch integration test reports.
package relevance

import (
	"errors"
	"fmt"
	"math"
	"os"
	"sort"

	"gopkg.in/yaml.v3"
)

// Product is one fixture document (relevance/products.yaml).
type Product struct {
	ATIN        string  `yaml:"atin"`
	Title       string  `yaml:"title"`
	Brand       string  `yaml:"brand"`
	Description string  `yaml:"description"`
	Path        string  `yaml:"path"`
	Fulfilment  string  `yaml:"fulfilment"`
	InStock     bool    `yaml:"inStock"`
	Rating      float64 `yaml:"rating"`
	Reviews     int     `yaml:"reviews"`
	PriceMinor  int64   `yaml:"price"`
	Days        int     `yaml:"days"`
}

// Query is one judged query.
type Query struct {
	Q          string         `yaml:"q"`
	Why        string         `yaml:"why"`
	Judgements map[string]int `yaml:"judgements"`
}

// Thresholds are the minimum mean metrics the suite must reach.
type Thresholds struct {
	NDCG10     float64 `yaml:"ndcg10"`
	Precision5 float64 `yaml:"precision5"`
}

// Suite is the judgement list.
type Suite struct {
	Thresholds Thresholds `yaml:"thresholds"`
	Queries    []Query    `yaml:"queries"`
}

// LoadProducts reads the fixtures file.
func LoadProducts(path string) ([]Product, error) {
	var doc struct {
		Products []Product `yaml:"products"`
	}
	if err := readYAML(path, &doc); err != nil {
		return nil, err
	}
	if len(doc.Products) == 0 {
		return nil, fmt.Errorf("%s: no products", path)
	}
	return doc.Products, nil
}

// LoadSuite reads the judgement list.
func LoadSuite(path string) (Suite, error) {
	var s Suite
	if err := readYAML(path, &s); err != nil {
		return Suite{}, err
	}
	if len(s.Queries) == 0 {
		return Suite{}, fmt.Errorf("%s: no queries", path)
	}
	for _, q := range s.Queries {
		if q.Q == "" || len(q.Judgements) == 0 {
			return Suite{}, fmt.Errorf("%s: query %q needs text and judgements", path, q.Q)
		}
	}
	return s, nil
}

func readYAML(path string, out any) error {
	raw, err := os.ReadFile(path) //nolint:gosec // test fixtures from the repository
	if err != nil {
		return fmt.Errorf("read %s: %w", path, err)
	}
	if err := yaml.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("parse %s: %w", path, err)
	}
	return nil
}

// ErrNoJudgements is returned when a query has no positive judgement (NDCG would be undefined).
var ErrNoJudgements = errors.New("no positive judgements")

// NDCG returns NDCG@k of ranked ATINs against graded judgements (gain 2^grade − 1, log2 discount).
func NDCG(ranked []string, judgements map[string]int, k int) (float64, error) {
	ideal := make([]int, 0, len(judgements))
	for _, g := range judgements {
		if g > 0 {
			ideal = append(ideal, g)
		}
	}
	if len(ideal) == 0 {
		return 0, ErrNoJudgements
	}
	sort.Sort(sort.Reverse(sort.IntSlice(ideal)))
	var dcg, idcg float64
	for i := 0; i < k && i < len(ranked); i++ {
		dcg += gain(judgements[ranked[i]]) / math.Log2(float64(i+2))
	}
	for i := 0; i < k && i < len(ideal); i++ {
		idcg += gain(ideal[i]) / math.Log2(float64(i+2))
	}
	return dcg / idcg, nil
}

// Precision returns the share of the top min(k, |relevant|) results that are relevant (grade ≥ 1), so a query
// with two relevant products can still reach 1.0.
func Precision(ranked []string, judgements map[string]int, k int) (float64, error) {
	relevant := 0
	for _, g := range judgements {
		if g > 0 {
			relevant++
		}
	}
	if relevant == 0 {
		return 0, ErrNoJudgements
	}
	n := min(k, relevant)
	hits := 0
	for i := 0; i < n && i < len(ranked); i++ {
		if judgements[ranked[i]] > 0 {
			hits++
		}
	}
	return float64(hits) / float64(n), nil
}

func gain(grade int) float64 {
	if grade <= 0 {
		return 0
	}
	return math.Pow(2, float64(grade)) - 1
}
