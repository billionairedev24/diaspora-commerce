package app

import (
	"context"
	"net/http"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SearchService proxies the search service in the contract's shapes (facet labels, delivery promises,
// stock flags, deliver-to label).
type SearchService interface {
	Search(ctx context.Context, c *domain.Caller, q domain.SearchQuery) (domain.SearchPage, error)
	Suggest(ctx context.Context, c *domain.Caller, q string) (domain.Suggestions, error)
}

type searchServiceImpl struct {
	search    port.SearchClient
	offers    port.StorefrontOffersClient
	location  LocationService
	estimator domain.Estimator
	market    string
	clock     Clock
}

var _ SearchService = (*searchServiceImpl)(nil)

// NewSearchService returns the SearchService. offers is optional: with it, cards carry the offer count, the lowest
// price across sellers and the buy box winner (one batch call per page).
func NewSearchService(search port.SearchClient, offers port.StorefrontOffersClient, location LocationService, estimator domain.Estimator, market string, clock Clock) SearchService {
	return &searchServiceImpl{search: search, offers: offers, location: location, estimator: estimator, market: market, clock: clock}
}

// toParams translates BFF filters to search-service parameters (price bucket → min/max, fastest → relevance
// with a local re-sort, maxDays ≥ 1).
func toParams(q domain.SearchQuery, market string) (port.SearchParams, error) {
	p := port.SearchParams{Q: strings.TrimSpace(q.Q), Market: market, Department: q.Department, Community: q.Community,
		Fulfilment: q.Fulfilment, SellerKind: q.SellerKind, MinRating: q.MinRating, Sort: q.Sort, Page: q.Page, Size: q.Size}
	if q.Price != "" {
		b, ok := domain.PriceBucketByID(q.Price)
		if !ok {
			return p, domain.NewProblem(http.StatusBadRequest, domain.CodeInvalidQuery, "Invalid search query", "Unknown price range.")
		}
		p.MinPrice, p.MaxPrice = b.Min, b.Max
	}
	if q.MaxDays != nil {
		p.MaxDays = max(1, *q.MaxDays)
	}
	if p.Sort == domain.SortFastest {
		p.Sort = domain.SortRelevance
	}
	return p, nil
}

func (s *searchServiceImpl) Search(ctx context.Context, c *domain.Caller, q domain.SearchQuery) (domain.SearchPage, error) {
	params, err := toParams(q, s.market)
	if err != nil {
		return domain.SearchPage{}, err
	}
	loc := s.location.Current(ctx, c)
	res, err := s.search.Search(ctx, params)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusBadRequest {
			return domain.SearchPage{}, domain.NewProblem(http.StatusBadRequest, domain.CodeInvalidQuery, "Invalid search query",
				firstNonEmpty(p.Detail, "One or more search parameters are invalid.")).WithCause(err)
		}
		return domain.SearchPage{}, mapErr("search", err)
	}
	now := s.clock.now()
	hits := make([]domain.ProductSummary, 0, len(res.Hits))
	for _, h := range res.Hits {
		hits = append(hits, summaryFromHit(h, s.estimator, now))
	}
	hits = withOffers(ctx, s.offers, hits)
	if q.Sort == domain.SortFastest {
		sort.SliceStable(hits, func(i, j int) bool { return hits[i].Delivery.Days < hits[j].Delivery.Days })
	}
	size := firstPositive(res.Size, q.Size, domain.DefaultSearchSize)
	return domain.SearchPage{
		Hits: hits, Total: res.Total, Page: res.Page, Size: size, PageCount: domain.PageCount(res.Total, size),
		Facets: facetsFrom(res.Facets), DeliverToLabel: loc.Label, TookMs: res.TookMs,
	}, nil
}

func firstPositive(vals ...int) int {
	for _, v := range vals {
		if v > 0 {
			return v
		}
	}
	return 0
}

// upstream facet names → BFF facet dimensions.
var facetNames = map[string]string{
	"department": "department", "communities": "community", "community": "community", "fulfilment": "fulfilment",
	"seller_kind": "sellerKind", "sellerKind": "sellerKind", "rating": "rating", "price": "price", "delivery": "delivery",
}

func facetsFrom(in map[string][]port.SearchFacetBucket) domain.Facets {
	out := map[string][]domain.FacetBucket{}
	for name, buckets := range in {
		dim, ok := facetNames[name]
		if !ok {
			continue
		}
		for _, b := range buckets {
			value, label, ok := domain.FacetBucketFor(dim, b.Value)
			if !ok {
				continue
			}
			out[dim] = append(out[dim], domain.FacetBucket{Value: value, Label: label, Count: max(0, b.Count)})
		}
	}
	get := func(k string) []domain.FacetBucket {
		if v := out[k]; v != nil {
			return v
		}
		return []domain.FacetBucket{}
	}
	return domain.Facets{Department: get("department"), Community: get("community"), Fulfilment: get("fulfilment"),
		SellerKind: get("sellerKind"), Rating: get("rating"), Price: get("price"), Delivery: get("delivery")}
}

func (s *searchServiceImpl) Suggest(ctx context.Context, _ *domain.Caller, q string) (domain.Suggestions, error) {
	q = strings.TrimSpace(q)
	if len(q) > 100 {
		q = q[:100]
	}
	if q == "" {
		return domain.Suggestions{Query: q, Phrases: []domain.Phrase{}, Products: []domain.ProductSummary{}}, nil
	}
	res, err := s.search.Suggest(ctx, q, s.market)
	if err != nil {
		return domain.Suggestions{}, mapErr("search", err)
	}
	now := s.clock.now()
	out := domain.Suggestions{Query: q, Phrases: make([]domain.Phrase, 0, len(res.Phrases)), Products: make([]domain.ProductSummary, 0, len(res.Products))}
	for _, p := range res.Phrases {
		out.Phrases = append(out.Phrases, domain.Phrase{Text: p.Text, Department: strPtr(p.Department)})
	}
	for i, h := range res.Products {
		if i == 5 {
			break
		}
		out.Products = append(out.Products, summaryFromHit(h, s.estimator, now))
	}
	out.Products = withOffers(ctx, s.offers, out.Products)
	return out, nil
}
