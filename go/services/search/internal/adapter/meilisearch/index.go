// Package meilisearch implements the search service's ports (port.Index, port.IndexAdmin, port.AnalyticsStore) on
// Meilisearch, selected with SEARCH_PROVIDER=meilisearch. The query translation mirrors the Elasticsearch adapter: the
// same filters, facets, sorts and hybrid (lexical + vector, fused by reciprocal rank) retrieval; the ranking signals
// Elasticsearch multiplies into a score are precomputed by the indexer and applied as ranking rules (searchindex.MeiliSettings).
package meilisearch

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

type object = map[string]any

type indexImpl struct{ c *meili.Client }

var _ port.Index = (*indexImpl)(nil)

// NewIndex returns an Index over a Meilisearch client; it queries the market's products index.
func NewIndex(c *meili.Client) port.Index { return &indexImpl{c: c} }

var retrieve = []string{"atin", "title", "brand", "department", "category_path", "seller_id", "seller_name", "seller_kind",
	"fulfilment", "price_minor", "currency", "rating", "review_count", "in_stock", "available_qty", "delivery_days",
	"image_url", "offer_count", "variant_atins"}

type hitDoc struct {
	ATIN         string   `json:"atin"`
	Title        string   `json:"title"`
	Brand        string   `json:"brand"`
	Department   string   `json:"department"`
	CategoryPath string   `json:"category_path"`
	SellerID     string   `json:"seller_id"`
	SellerName   string   `json:"seller_name"`
	SellerKind   string   `json:"seller_kind"`
	Fulfilment   string   `json:"fulfilment"`
	PriceMinor   int64    `json:"price_minor"`
	Currency     string   `json:"currency"`
	Rating       float64  `json:"rating"`
	ReviewCount  int      `json:"review_count"`
	InStock      bool     `json:"in_stock"`
	AvailableQty int      `json:"available_qty"`
	DeliveryDays int      `json:"delivery_days"`
	ImageURL     string   `json:"image_url"`
	OfferCount   int      `json:"offer_count"`
	VariantATINs []string `json:"variant_atins"`
	Score        float64  `json:"_rankingScore"`
}

type searchResult struct {
	Hits               []hitDoc                  `json:"hits"`
	EstimatedTotalHits int64                     `json:"estimatedTotalHits"`
	TotalHits          int64                     `json:"totalHits"`
	FacetDistribution  map[string]map[string]int `json:"facetDistribution"`
	ProcessingTimeMs   int64                     `json:"processingTimeMs"`
}

func (r searchResult) total() int64 {
	if r.TotalHits > 0 {
		return r.TotalHits
	}
	return r.EstimatedTotalHits
}

func (h hitDoc) toHit() domain.Hit {
	return domain.Hit{ATIN: h.ATIN, Title: h.Title, Brand: h.Brand, CategoryPath: h.CategoryPath, SellerName: h.SellerName,
		SellerKind: h.SellerKind, Fulfilment: h.Fulfilment, PriceMinor: h.PriceMinor, Currency: h.Currency, Rating: h.Rating,
		ReviewCount: h.ReviewCount, InStock: h.InStock, AvailableQty: h.AvailableQty, DeliveryDays: h.DeliveryDays,
		ImageURL: h.ImageURL, OfferCount: h.OfferCount, Score: h.Score, SellerID: h.SellerID, Department: h.Department,
		VariantATINs: h.VariantATINs}
}

// buildFilter is the shopper's filters as a Meilisearch filter expression. Only live products are searchable.
func buildFilter(q domain.Query) string {
	f := []string{"status = " + meili.Quote(searchindex.StatusLive)}
	if q.Department != "" {
		f = append(f, "department = "+meili.Quote(q.Department))
	}
	if len(q.Communities) > 0 {
		in := make([]string, len(q.Communities))
		for i, c := range q.Communities {
			in[i] = meili.Quote(c)
		}
		f = append(f, "communities IN ["+strings.Join(in, ", ")+"]")
	}
	if q.Fulfilment != "" {
		f = append(f, "fulfilment = "+meili.Quote(q.Fulfilment))
	}
	if q.SellerKind != "" {
		f = append(f, "seller_kind = "+meili.Quote(q.SellerKind))
	}
	if q.SellerID != "" {
		f = append(f, "seller_id = "+meili.Quote(q.SellerID))
	}
	if q.MinRating > 0 {
		f = append(f, "rating >= "+strconv.FormatFloat(q.MinRating, 'f', -1, 64))
	}
	if q.MinPrice > 0 {
		f = append(f, "price_minor >= "+strconv.FormatInt(q.MinPrice, 10))
	}
	if q.MaxPrice > 0 {
		f = append(f, "price_minor <= "+strconv.FormatInt(q.MaxPrice, 10))
	}
	if q.MaxDays > 0 {
		f = append(f, "delivery_days <= "+strconv.Itoa(q.MaxDays))
	}
	if q.InStockOnly {
		f = append(f, "in_stock = true")
	}
	if q.WithinMiles > 0 {
		f = append(f, fmt.Sprintf("_geoRadius(%s, %s, %d)", strconv.FormatFloat(q.NearLat, 'f', -1, 64),
			strconv.FormatFloat(q.NearLon, 'f', -1, 64), int(float64(q.WithinMiles)*1609.344)))
	}
	return strings.Join(f, " AND ")
}

func sortOf(s domain.Sort) []string {
	switch s {
	case domain.SortPriceAsc:
		return []string{"price_minor:asc"}
	case domain.SortPriceDesc:
		return []string{"price_minor:desc"}
	case domain.SortRating:
		return []string{"rating:desc", "review_count:desc"}
	case domain.SortNewest:
		return []string{"updated_ts:desc"}
	}
	return nil
}

// bucket is a range facet: Elasticsearch's range aggregation buckets, counted here with one extra filter each.
type bucket struct{ key, cond string }

var rangeFacets = []struct {
	name    string
	buckets []bucket
}{
	{"rating", []bucket{{"4+", "rating >= 4"}, {"3+", "rating >= 3"}}},
	{"price", []bucket{{"under-10", "price_minor < 1000"}, {"10-25", "price_minor >= 1000 AND price_minor < 2500"},
		{"25-50", "price_minor >= 2500 AND price_minor < 5000"}, {"50-plus", "price_minor >= 5000"}}},
	{"delivery", []bucket{{"tomorrow", "delivery_days < 2"}, {"2-days", "delivery_days < 3"}}},
}

var termFacets = []struct{ name, attr string }{
	{"department", "department"}, {"communities", "communities"}, {"fulfilment", "fulfilment"}, {"seller_kind", "seller_kind"},
}

// lexical is the text query for one page (1-based) of per results; page/hitsPerPage make Meilisearch count exactly.
func (i *indexImpl) lexical(q domain.Query, page, per int) object {
	body := object{"indexUid": searchindex.MeiliIndexUID(q.Market), "q": q.Text, "filter": buildFilter(q), "page": page, "hitsPerPage": per,
		"matchingStrategy": "all", "showRankingScore": true, "attributesToRetrieve": retrieve,
		"facets": []string{"department", "communities", "fulfilment", "seller_kind"}}
	if s := sortOf(q.Sort); s != nil {
		body["sort"] = s
	}
	return body
}

func (i *indexImpl) semantic(q domain.Query, vec []float32, limit int) object {
	return object{"indexUid": searchindex.MeiliIndexUID(q.Market), "vector": vec, "filter": buildFilter(q), "limit": limit,
		"hybrid": object{"embedder": searchindex.MeiliEmbedder, "semanticRatio": 1.0}, "showRankingScore": true,
		"attributesToRetrieve": retrieve}
}

func (i *indexImpl) rangeCounts(q domain.Query) []object {
	base := buildFilter(q)
	var out []object
	for _, f := range rangeFacets {
		for _, b := range f.buckets {
			out = append(out, object{"indexUid": searchindex.MeiliIndexUID(q.Market), "q": q.Text, "matchingStrategy": "all",
				"filter": base + " AND " + b.cond, "page": 1, "hitsPerPage": 1})
		}
	}
	return out
}

func (i *indexImpl) multi(ctx context.Context, queries []object) ([]searchResult, error) {
	var out struct {
		Results []searchResult `json:"results"`
	}
	if err := i.c.Do(ctx, http.MethodPost, "/multi-search", object{"queries": queries}, &out); err != nil {
		return nil, fmt.Errorf("meilisearch search: %w", err)
	}
	if len(out.Results) != len(queries) {
		return nil, fmt.Errorf("meilisearch search: %d results for %d queries", len(out.Results), len(queries))
	}
	return out.Results, nil
}

// facetsOf assembles the sidebar: term facets from the lexical result, range buckets from the per-bucket counts.
func facetsOf(lex searchResult, counts []searchResult) map[string][]domain.FacetBucket {
	facets := map[string][]domain.FacetBucket{}
	for _, f := range termFacets {
		dist := lex.FacetDistribution[f.attr]
		buckets := make([]domain.FacetBucket, 0, len(dist))
		for v, n := range dist {
			if n > 0 {
				buckets = append(buckets, domain.FacetBucket{Value: v, Count: int64(n)})
			}
		}
		sortBuckets(buckets)
		facets[f.name] = buckets
	}
	n := 0
	for _, f := range rangeFacets {
		buckets := make([]domain.FacetBucket, 0, len(f.buckets))
		for _, b := range f.buckets {
			if n < len(counts) && counts[n].total() > 0 {
				buckets = append(buckets, domain.FacetBucket{Value: b.key, Count: counts[n].total()})
			}
			n++
		}
		facets[f.name] = buckets
	}
	return facets
}

func sortBuckets(b []domain.FacetBucket) {
	for i := 1; i < len(b); i++ {
		for j := i; j > 0 && (b[j].Count > b[j-1].Count || (b[j].Count == b[j-1].Count && b[j].Value < b[j-1].Value)); j-- {
			b[j], b[j-1] = b[j-1], b[j]
		}
	}
}

// Search runs the lexical query, or with a query embedding a hybrid search: the lexical window and a vector search in
// one multi-search, merged by reciprocal rank fusion exactly as the Elasticsearch adapter does. If the vector side
// fails the page is lexical.
func (i *indexImpl) Search(ctx context.Context, q domain.Query, vec []float32) (domain.Page, error) {
	if vec != nil {
		page, err := i.hybrid(ctx, q, vec)
		if err == nil {
			return page, nil
		}
		page, lerr := i.plain(ctx, q)
		if lerr != nil {
			return domain.Page{}, err
		}
		page.SemanticSkipped = domain.SkipUnavailable
		return page, nil
	}
	return i.plain(ctx, q)
}

func (i *indexImpl) plain(ctx context.Context, q domain.Query) (domain.Page, error) {
	queries := append([]object{i.lexical(q, q.Page+1, q.Size)}, i.rangeCounts(q)...)
	res, err := i.multi(ctx, queries)
	if err != nil {
		return domain.Page{}, err
	}
	lex := res[0]
	page := domain.Page{Total: lex.total(), Page: q.Page, Size: q.Size, TookMs: lex.ProcessingTimeMs,
		Hits: make([]domain.Hit, 0, len(lex.Hits)), Retrieval: domain.RetrievalLexical, Facets: facetsOf(lex, res[1:])}
	for _, h := range lex.Hits {
		page.Hits = append(page.Hits, h.toHit())
	}
	return page, nil
}

func (i *indexImpl) hybrid(ctx context.Context, q domain.Query, vec []float32) (domain.Page, error) {
	window := max(domain.HybridWindow, (q.Page+1)*q.Size)
	queries := append([]object{i.lexical(q, 1, window), i.semantic(q, vec, window)}, i.rangeCounts(q)...)
	res, err := i.multi(ctx, queries)
	if err != nil {
		return domain.Page{}, err
	}
	lex, sem := res[0], res[1]
	sources := map[string]hitDoc{}
	ids := func(r searchResult) []string {
		out := make([]string, 0, len(r.Hits))
		for _, h := range r.Hits {
			out = append(out, h.ATIN)
			if _, ok := sources[h.ATIN]; !ok {
				sources[h.ATIN] = h
			}
		}
		return out
	}
	fused := domain.FuseRRF(ids(lex), ids(sem), domain.RRFK)
	semanticOnly := 0
	for _, f := range fused {
		if f.Lexical == 0 {
			semanticOnly++
		}
	}
	page := domain.Page{Page: q.Page, Size: q.Size, TookMs: lex.ProcessingTimeMs, Facets: facetsOf(lex, res[2:]), Hits: []domain.Hit{},
		Retrieval: domain.RetrievalHybrid, Total: lex.total() + int64(semanticOnly)}
	from := min(q.Page*q.Size, len(fused))
	for _, f := range fused[from:min(from+q.Size, len(fused))] {
		hit := sources[f.ID].toHit()
		hit.Score = f.Score
		page.Hits = append(page.Hits, hit)
	}
	return page, nil
}

func (i *indexImpl) Suggest(ctx context.Context, market, prefix string, limit int) (domain.Suggestions, error) {
	res, err := i.multi(ctx, []object{{"indexUid": searchindex.MeiliIndexUID(market), "q": prefix, "limit": limit * 4,
		"filter": "status = " + meili.Quote(searchindex.StatusLive), "matchingStrategy": "all",
		"attributesToSearchOn": []string{"title"}, "attributesToRetrieve": retrieve}})
	if err != nil {
		return domain.Suggestions{}, err
	}
	out := domain.Suggestions{Phrases: []domain.Suggestion{}, Products: []domain.Hit{}}
	seen := map[string]bool{}
	words := len(strings.Fields(prefix))
	for idx, h := range res[0].Hits {
		if len(out.Products) < limit {
			out.Products = append(out.Products, h.toHit())
		}
		phrase := domain.CompletePhrase(h.Title, words+1)
		if phrase == "" || seen[phrase] || len(out.Phrases) >= limit {
			continue
		}
		seen[phrase] = true
		s := domain.Suggestion{Text: phrase}
		if idx == 0 {
			s.Department = h.Department
		}
		out.Phrases = append(out.Phrases, s)
	}
	return out, nil
}

func (i *indexImpl) AdCandidates(ctx context.Context, q domain.Query, atins []string) ([]domain.Hit, error) {
	if len(atins) == 0 {
		return []domain.Hit{}, nil
	}
	quoted := make([]string, len(atins))
	for n, a := range atins {
		quoted[n] = meili.Quote(a)
	}
	filter := buildFilter(q) + " AND atin IN [" + strings.Join(quoted, ", ") + "] AND in_stock = true"
	res, err := i.multi(ctx, []object{{"indexUid": searchindex.MeiliIndexUID(q.Market), "q": q.Text, "filter": filter, "limit": len(atins),
		"matchingStrategy": "all", "showRankingScore": true, "attributesToRetrieve": retrieve}})
	if err != nil {
		return nil, err
	}
	out := make([]domain.Hit, 0, len(res[0].Hits))
	for _, h := range res[0].Hits {
		out = append(out, h.toHit())
	}
	return out, nil
}

func (i *indexImpl) Ping(ctx context.Context) error { return i.c.Health(ctx) }
