package elastic

import (
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type object = map[string]any

// Ranking holds the relevance tuning (S039). Text relevance (BM25 over boosted fields) is multiplied by commercial
// boosts that never zero a score, so an unrated new listing still ranks by how well it matches.
type Ranking struct {
	// Field boosts: title, brand, the seller's keywords, category path, and attribute text (description and attribute values).
	TitleBoost, BrandBoost, KeywordBoost, CategoryBoost, AttributeBoost float64
	// ExactBoost rewards documents whose title or brand contains the query words without typos.
	ExactBoost float64
	// PhraseBoost rewards the query appearing as a phrase in the title.
	PhraseBoost float64
	// InStock multiplies in-stock items; Fulfilled multiplies "Fulfilled by AfroTransact" items.
	InStock, Fulfilled float64
	// RatingWeight scales 1 + w × rating × confidence, confidence = min(1, log10(1 + reviews) / 2).
	RatingWeight float64
	// FastDelivery multiplies items promised within FastDeliveryDays.
	FastDelivery     float64
	FastDeliveryDays int
}

// DefaultRanking is the tuned configuration measured by the relevance harness (relevance/judgements.yaml).
func DefaultRanking() Ranking {
	return Ranking{
		TitleBoost: 5, BrandBoost: 3, KeywordBoost: 3, CategoryBoost: 2, AttributeBoost: 1,
		ExactBoost: 1.5, PhraseBoost: 2,
		InStock: 3, Fulfilled: 1.2, RatingWeight: 0.08, FastDelivery: 1.05, FastDeliveryDays: 2,
	}
}

// ratingScript is the rating × review-volume boost; it is ≥ 1 so unrated products are never zeroed.
const ratingScript = `double r = doc['rating'].size() == 0 ? 0 : doc['rating'].value;
long n = doc['review_count'].size() == 0 ? 0 : doc['review_count'].value;
double confidence = Math.min(1.0, Math.log10(1 + n) / 2.0);
return 1 + params.w * r * confidence;`

var sourceFields = []string{"atin", "title", "brand", "department", "category_path", "seller_id", "seller_name",
	"seller_kind", "fulfilment", "price_minor", "currency", "rating", "review_count", "in_stock", "available_qty",
	"delivery_days", "image_url", "offer_count", "variant_atins"}

func (r Ranking) textFields() []string {
	return []string{"title^" + num(r.TitleBoost), "brand^" + num(r.BrandBoost), "keywords^" + num(r.KeywordBoost),
		"category_path.text^" + num(r.CategoryBoost), "description^" + num(r.AttributeBoost)}
}

// textQuery matches when every typed word matches a field (operator and) with typos tolerated — words of 3–5
// letters may be one edit off, 6+ letters two (fuzziness AUTO:3,6), first letter fixed — or when the words are
// prefixes of the title (search-as-you-type). Diaspora synonyms expand through the product_search analyzer.
func (r Ranking) textQuery(text string) object {
	return object{"bool": object{
		"should": []any{
			object{"multi_match": object{
				"query": text, "fields": r.textFields(), "type": "best_fields", "tie_breaker": 0.3,
				"operator": "and", "fuzziness": "AUTO:3,6", "prefix_length": 1,
			}},
			object{"multi_match": object{
				"query": text, "type": "bool_prefix", "operator": "and",
				"fields": []string{"title.suggest", "title.suggest._2gram", "title.suggest._3gram"},
			}},
		},
		"minimum_should_match": 1,
	}}
}

// boosts are optional clauses that only add score, on fields analysed without synonyms so a literal match beats a
// synonym-only one: every query word in the title (no typos), the query as a title phrase, and an exact brand.
func (r Ranking) boosts(text string) []any {
	return []any{
		object{"match": object{"title.suggest": object{"query": text, "operator": "and", "boost": r.ExactBoost}}},
		object{"match_phrase": object{"title.suggest": object{"query": text, "slop": 1, "boost": r.PhraseBoost}}},
		object{"term": object{"brand.raw": object{"value": strings.ToLower(text), "boost": r.ExactBoost}}},
	}
}

func (r Ranking) functions() []any {
	return []any{
		object{"filter": object{"term": object{"in_stock": true}}, "weight": r.InStock},
		object{"filter": object{"term": object{"fulfilment": "AFROTRANSACT"}}, "weight": r.Fulfilled},
		object{"script_score": object{"script": object{"source": ratingScript, "params": object{"w": r.RatingWeight}}}},
		object{"filter": object{"range": object{"delivery_days": object{"gte": 1, "lte": r.FastDeliveryDays}}},
			"weight": r.FastDelivery},
	}
}

func (r Ranking) scored(q domain.Query, filters []any) object {
	b := object{"filter": filters}
	if q.Text != "" {
		b["must"] = []any{r.textQuery(q.Text)}
		b["should"] = r.boosts(q.Text)
	} else {
		b["must"] = []any{object{"match_all": object{}}}
	}
	return object{"function_score": object{
		"query":      object{"bool": b},
		"functions":  r.functions(),
		"score_mode": "multiply",
		"boost_mode": "multiply",
	}}
}

// buildSearch translates a normalized domain query into an Elasticsearch request body.
func (r Ranking) buildSearch(q domain.Query) object {
	filters := buildFilters(q)
	body := object{
		"query":            r.scored(q, filters),
		"from":             q.Page * q.Size,
		"size":             q.Size,
		"track_total_hits": true,
		"track_scores":     true,
		"aggs":             facets(),
		"_source":          sourceFields,
	}
	if s := sortClause(q.Sort); s != nil {
		body["sort"] = s
	}
	return body
}

// buildLexicalWindow is the BM25 side of a hybrid search: the first window results (with facets and total), which
// reciprocal rank fusion merges with the semantic side.
func (r Ranking) buildLexicalWindow(q domain.Query, window int) object {
	body := r.buildSearch(q)
	body["from"], body["size"] = 0, window
	return body
}

// buildSemantic is the kNN side of a hybrid search: approximate nearest neighbours of the query embedding among the
// filtered (live, matching filters) products, multiplied by the same commercial boosts as the lexical side so an
// out-of-stock twin does not outrank the in-stock product.
func (r Ranking) buildSemantic(q domain.Query, vec []float32, window int) object {
	return object{
		"query": object{"function_score": object{
			"query": object{"knn": object{
				"field": "embedding", "query_vector": vec, "k": window, "num_candidates": max(100, 2*window),
				"filter": buildFilters(q),
			}},
			"functions":  r.functions(),
			"score_mode": "multiply",
			"boost_mode": "multiply",
		}},
		"size":    window,
		"_source": sourceFields,
	}
}

// buildAdCandidates scores advertised products with the organic query, restricted to products that may be advertised
// (live, in stock) and to the shopper's filters.
func (r Ranking) buildAdCandidates(q domain.Query, atins []string) object {
	filters := append(buildFilters(q),
		object{"terms": object{"atin": atins}},
		object{"term": object{"in_stock": true}},
	)
	return object{
		"query":   r.scored(q, filters),
		"size":    len(atins),
		"_source": sourceFields,
	}
}

func buildFilters(q domain.Query) []any {
	// Only complete, live product pages are searchable (availability updates can arrive before the product; hidden
	// sellers' products are indexed SELLER_HIDDEN).
	f := []any{object{"term": object{"status": "LIVE"}}}
	if q.Department != "" {
		f = append(f, object{"term": object{"department": q.Department}})
	}
	if len(q.Communities) > 0 {
		f = append(f, object{"terms": object{"communities": q.Communities}})
	}
	if q.Fulfilment != "" {
		f = append(f, object{"term": object{"fulfilment": q.Fulfilment}})
	}
	if q.SellerKind != "" {
		f = append(f, object{"term": object{"seller_kind": q.SellerKind}})
	}
	if q.MinRating > 0 {
		f = append(f, object{"range": object{"rating": object{"gte": q.MinRating}}})
	}
	if q.MinPrice > 0 || q.MaxPrice > 0 {
		r := object{}
		if q.MinPrice > 0 {
			r["gte"] = q.MinPrice
		}
		if q.MaxPrice > 0 {
			r["lte"] = q.MaxPrice
		}
		f = append(f, object{"range": object{"price_minor": r}})
	}
	if q.MaxDays > 0 {
		f = append(f, object{"range": object{"delivery_days": object{"lte": q.MaxDays}}})
	}
	if q.InStockOnly {
		f = append(f, object{"term": object{"in_stock": true}})
	}
	if q.WithinMiles > 0 {
		f = append(f, object{"geo_distance": object{
			"distance": strconv.Itoa(q.WithinMiles) + "mi", "ships_from": object{"lat": q.NearLat, "lon": q.NearLon},
		}})
	}
	return f
}

func sortClause(s domain.Sort) []any {
	switch s {
	case domain.SortPriceAsc:
		return []any{object{"price_minor": "asc"}, "_score"}
	case domain.SortPriceDesc:
		return []any{object{"price_minor": "desc"}, "_score"}
	case domain.SortRating:
		return []any{object{"rating": "desc"}, object{"review_count": "desc"}}
	case domain.SortNewest:
		return []any{object{"updated_at": "desc"}}
	default:
		return nil
	}
}

func facets() object {
	return object{
		"department":  object{"terms": object{"field": "department", "size": 20}},
		"communities": object{"terms": object{"field": "communities", "size": 20}},
		"fulfilment":  object{"terms": object{"field": "fulfilment", "size": 2}},
		"seller_kind": object{"terms": object{"field": "seller_kind", "size": 2}},
		"rating": object{"range": object{"field": "rating", "keyed": false, "ranges": []any{
			object{"key": "4+", "from": 4}, object{"key": "3+", "from": 3},
		}}},
		"price": object{"range": object{"field": "price_minor", "keyed": false, "ranges": []any{
			object{"key": "under-10", "to": 1000}, object{"key": "10-25", "from": 1000, "to": 2500},
			object{"key": "25-50", "from": 2500, "to": 5000}, object{"key": "50-plus", "from": 5000},
		}}},
		"delivery": object{"range": object{"field": "delivery_days", "keyed": false, "ranges": []any{
			object{"key": "tomorrow", "to": 2}, object{"key": "2-days", "to": 3},
		}}},
	}
}

func buildSuggest(prefix string, limit int) object {
	return object{
		"size": limit * 4,
		"query": object{"bool": object{
			"must": []any{object{"multi_match": object{
				"query": prefix, "type": "bool_prefix", "operator": "and", "fuzziness": "AUTO:4,7",
				"fields": []string{"title.suggest", "title.suggest._2gram", "title.suggest._3gram"},
			}}},
			"filter": []any{object{"term": object{"status": "LIVE"}}},
			"should": []any{object{"term": object{"in_stock": object{"value": true, "boost": 2}}}},
		}},
		"_source": sourceFields,
	}
}

func num(f float64) string { return strconv.FormatFloat(f, 'f', -1, 64) }
