package elastic

import (
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type object = map[string]any

// textFields are searched with per-field boosts; every word must match (operator=and) with typo tolerance:
// words of 3–5 letters may be one edit off, 6+ letters two (fuzziness AUTO:3,6), first letter fixed.
var textFields = []string{"title^4", "brand^2", "category_path.text^2", "description"}

// buildSearch translates a normalized domain query into an Elasticsearch request body.
func buildSearch(q domain.Query, vec []float32) object {
	filters := buildFilters(q)
	var must []any
	if q.Text != "" {
		must = append(must, object{"bool": object{
			"should": []any{
				object{"multi_match": object{
					"query": q.Text, "fields": textFields, "type": "best_fields",
					"operator": "and", "fuzziness": "AUTO:3,6", "prefix_length": 1,
				}},
				object{"multi_match": object{
					"query": q.Text, "type": "bool_prefix", "operator": "and",
					"fields": []string{"title.suggest", "title.suggest._2gram", "title.suggest._3gram"},
				}},
			},
			"minimum_should_match": 1,
		}})
	} else {
		must = append(must, object{"match_all": object{}})
	}

	query := object{"function_score": object{
		"query": object{"bool": object{"must": must, "filter": filters}},
		"functions": []any{
			object{"field_value_factor": object{"field": "rating", "modifier": "log1p", "missing": 0, "factor": 0.6}},
			object{"filter": object{"term": object{"in_stock": true}}, "weight": 2},
			object{"filter": object{"term": object{"fulfilment": "AFROTRANSACT"}}, "weight": 1.15},
		},
		"score_mode": "multiply",
		"boost_mode": "multiply",
	}}

	body := object{
		"query":            query,
		"from":             q.Page * q.Size,
		"size":             q.Size,
		"track_total_hits": true,
		"aggs":             facets(),
		"_source": []string{"atin", "title", "brand", "category_path", "seller_name", "seller_kind", "fulfilment",
			"price_minor", "currency", "rating", "review_count", "in_stock", "available_qty", "delivery_days",
			"image_url", "offer_count"},
	}
	if s := sortClause(q.Sort); s != nil {
		body["sort"] = s
	}
	if vec != nil {
		body["knn"] = object{
			"field": "embedding", "query_vector": vec, "k": q.Size * 2, "num_candidates": 100,
			"filter": filters, "boost": 0.5,
		}
	}
	return body
}

func buildFilters(q domain.Query) []any {
	// Only complete, live product pages are searchable (availability updates can arrive before the product).
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
			"distance": itoa(q.WithinMiles) + "mi", "ships_from": object{"lat": q.NearLat, "lon": q.NearLon},
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
		"_source": []string{"atin", "title", "department", "seller_name", "seller_kind", "fulfilment",
			"price_minor", "currency", "rating", "review_count", "in_stock", "available_qty", "image_url"},
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
