// Package elastic implements port.Index on Elasticsearch 9.
package elastic

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

type indexImpl struct {
	es      *elasticsearch.Client
	ranking Ranking
}

var _ port.Index = (*indexImpl)(nil)

// Option tunes the index adapter.
type Option func(*indexImpl)

// WithRanking replaces DefaultRanking.
func WithRanking(r Ranking) Option { return func(i *indexImpl) { i.ranking = r } }

// NewIndex returns an Index that queries the products-<market> alias.
func NewIndex(es *elasticsearch.Client, opts ...Option) port.Index {
	i := &indexImpl{es: es, ranking: DefaultRanking()}
	for _, o := range opts {
		o(i)
	}
	return i
}

type source struct {
	ATIN         string   `json:"atin"`
	Title        string   `json:"title"`
	Brand        string   `json:"brand"`
	Department   string   `json:"department"`
	CategoryPath string   `json:"category_path"`
	SellerID     string   `json:"seller_id"`
	VariantATINs []string `json:"variant_atins"`
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
}

type bucket struct {
	Key      any   `json:"key"`
	DocCount int64 `json:"doc_count"`
}

type response struct {
	Took  int64           `json:"took"`
	Error json.RawMessage `json:"error,omitempty"`
	Hits  struct {
		Total struct {
			Value int64 `json:"value"`
		} `json:"total"`
		Hits []struct {
			Score  *float64 `json:"_score"`
			Source source   `json:"_source"`
		} `json:"hits"`
	} `json:"hits"`
	Aggregations map[string]struct {
		Buckets []bucket `json:"buckets"`
	} `json:"aggregations"`
}

// Search runs BM25, or with a query embedding a hybrid search: BM25 and kNN in one _msearch, merged by reciprocal
// rank fusion (computed here, so it needs no Elasticsearch licence feature). Facets and the lexical total come from the
// BM25 side; products only the semantic side found are added to the total. If the kNN side fails the page is lexical.
func (i *indexImpl) Search(ctx context.Context, q domain.Query, vec []float32) (domain.Page, error) {
	if vec != nil {
		return i.hybrid(ctx, q, vec)
	}
	var res response
	if err := i.do(ctx, searchindex.Alias(q.Market), i.ranking.buildSearch(q), &res); err != nil {
		return domain.Page{}, err
	}
	page := domain.Page{Total: res.Hits.Total.Value, Page: q.Page, Size: q.Size, TookMs: res.Took,
		Hits: make([]domain.Hit, 0, len(res.Hits.Hits)), Retrieval: domain.RetrievalLexical}
	for _, h := range res.Hits.Hits {
		hit := toHit(h.Source)
		if h.Score != nil {
			hit.Score = *h.Score
		}
		page.Hits = append(page.Hits, hit)
	}
	page.Facets = facetsOf(res)
	return page, nil
}

func (i *indexImpl) hybrid(ctx context.Context, q domain.Query, vec []float32) (domain.Page, error) {
	window := max(domain.HybridWindow, (q.Page+1)*q.Size)
	results, took, err := i.msearch(ctx, searchindex.Alias(q.Market),
		i.ranking.buildLexicalWindow(q, window), i.ranking.buildSemantic(q, vec, window))
	if err != nil {
		return domain.Page{}, err
	}
	lex, sem := results[0], results[1]
	if len(lex.Error) > 0 {
		return domain.Page{}, fmt.Errorf("elasticsearch lexical search: %s", lex.Error)
	}
	page := domain.Page{Page: q.Page, Size: q.Size, TookMs: took, Facets: facetsOf(lex), Hits: []domain.Hit{}}
	if len(sem.Error) > 0 {
		// The kNN side failed (for example an index without embeddings yet): serve the lexical ranking.
		page.Retrieval, page.SemanticSkipped, page.Total = domain.RetrievalLexical, domain.SkipUnavailable, lex.Hits.Total.Value
		from := min(q.Page*q.Size, len(lex.Hits.Hits))
		for _, h := range lex.Hits.Hits[from:min(from+q.Size, len(lex.Hits.Hits))] {
			hit := toHit(h.Source)
			if h.Score != nil {
				hit.Score = *h.Score
			}
			page.Hits = append(page.Hits, hit)
		}
		return page, nil
	}
	sources := map[string]source{}
	ids := func(r response) []string {
		out := make([]string, 0, len(r.Hits.Hits))
		for _, h := range r.Hits.Hits {
			out = append(out, h.Source.ATIN)
			if _, ok := sources[h.Source.ATIN]; !ok {
				sources[h.Source.ATIN] = h.Source
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
	page.Retrieval, page.Total = domain.RetrievalHybrid, lex.Hits.Total.Value+int64(semanticOnly)
	from := min(q.Page*q.Size, len(fused))
	for _, f := range fused[from:min(from+q.Size, len(fused))] {
		hit := toHit(sources[f.ID])
		hit.Score = f.Score
		page.Hits = append(page.Hits, hit)
	}
	return page, nil
}

// msearch runs several searches against one index in a single round trip.
func (i *indexImpl) msearch(ctx context.Context, index string, bodies ...object) ([]response, int64, error) {
	var buf bytes.Buffer
	for _, b := range bodies {
		raw, err := json.Marshal(b)
		if err != nil {
			return nil, 0, fmt.Errorf("encode query: %w", err)
		}
		buf.WriteString("{}\n")
		buf.Write(raw)
		buf.WriteByte('\n')
	}
	res, err := i.es.Msearch(bytes.NewReader(buf.Bytes()), i.es.Msearch.WithContext(ctx), i.es.Msearch.WithIndex(index))
	if err != nil {
		return nil, 0, fmt.Errorf("elasticsearch msearch: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return nil, 0, fmt.Errorf("elasticsearch msearch: %s: %s", res.Status(), msg)
	}
	var out struct {
		Took      int64      `json:"took"`
		Responses []response `json:"responses"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, 0, fmt.Errorf("decode msearch response: %w", err)
	}
	if len(out.Responses) != len(bodies) {
		return nil, 0, fmt.Errorf("elasticsearch msearch: %d responses for %d searches", len(out.Responses), len(bodies))
	}
	return out.Responses, out.Took, nil
}

func facetsOf(res response) map[string][]domain.FacetBucket {
	facets := map[string][]domain.FacetBucket{}
	for name, agg := range res.Aggregations {
		buckets := make([]domain.FacetBucket, 0, len(agg.Buckets))
		for _, b := range agg.Buckets {
			if b.DocCount > 0 {
				buckets = append(buckets, domain.FacetBucket{Value: fmt.Sprint(b.Key), Count: b.DocCount})
			}
		}
		facets[name] = buckets
	}
	return facets
}

func (i *indexImpl) Suggest(ctx context.Context, market, prefix string, limit int) (domain.Suggestions, error) {
	var res response
	if err := i.do(ctx, searchindex.Alias(market), buildSuggest(prefix, limit), &res); err != nil {
		return domain.Suggestions{}, err
	}
	out := domain.Suggestions{Phrases: []domain.Suggestion{}, Products: []domain.Hit{}}
	seen := map[string]bool{}
	words := len(strings.Fields(prefix))
	for idx, h := range res.Hits.Hits {
		if len(out.Products) < limit {
			out.Products = append(out.Products, toHit(h.Source))
		}
		phrase := domain.CompletePhrase(h.Source.Title, words+1)
		if phrase == "" || seen[phrase] || len(out.Phrases) >= limit {
			continue
		}
		seen[phrase] = true
		s := domain.Suggestion{Text: phrase}
		if idx == 0 {
			s.Department = h.Source.Department
		}
		out.Phrases = append(out.Phrases, s)
	}
	return out, nil
}

func (i *indexImpl) AdCandidates(ctx context.Context, q domain.Query, atins []string) ([]domain.Hit, error) {
	if len(atins) == 0 {
		return []domain.Hit{}, nil
	}
	var res response
	if err := i.do(ctx, searchindex.Alias(q.Market), i.ranking.buildAdCandidates(q, atins), &res); err != nil {
		return nil, err
	}
	out := make([]domain.Hit, 0, len(res.Hits.Hits))
	for _, h := range res.Hits.Hits {
		hit := toHit(h.Source)
		if h.Score != nil {
			hit.Score = *h.Score
		}
		out = append(out, hit)
	}
	return out, nil
}

func (i *indexImpl) Ping(ctx context.Context) error {
	res, err := i.es.Ping(i.es.Ping.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("elasticsearch ping: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		return fmt.Errorf("elasticsearch ping: %s", res.Status())
	}
	return nil
}

func (i *indexImpl) do(ctx context.Context, index string, body object, out *response) error {
	buf, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encode query: %w", err)
	}
	res, err := i.es.Search(
		i.es.Search.WithContext(ctx),
		i.es.Search.WithIndex(index),
		i.es.Search.WithBody(bytes.NewReader(buf)),
	)
	if err != nil {
		return fmt.Errorf("elasticsearch search: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return fmt.Errorf("elasticsearch search: %s: %s", res.Status(), msg)
	}
	if err := json.NewDecoder(res.Body).Decode(out); err != nil {
		return fmt.Errorf("decode search response: %w", err)
	}
	return nil
}

func toHit(s source) domain.Hit {
	return domain.Hit{ATIN: s.ATIN, Title: s.Title, Brand: s.Brand, CategoryPath: s.CategoryPath,
		SellerName: s.SellerName, SellerKind: s.SellerKind, Fulfilment: s.Fulfilment, PriceMinor: s.PriceMinor,
		Currency: s.Currency, Rating: s.Rating, ReviewCount: s.ReviewCount, InStock: s.InStock,
		AvailableQty: s.AvailableQty, DeliveryDays: s.DeliveryDays, ImageURL: s.ImageURL, OfferCount: s.OfferCount,
		SellerID: s.SellerID, Department: s.Department, VariantATINs: s.VariantATINs}
}
