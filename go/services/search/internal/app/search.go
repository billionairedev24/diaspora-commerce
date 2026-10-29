// Package app holds the search use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// ProductSearcher is the use case behind the results page, the storefront API and Lola's search tool.
type ProductSearcher interface {
	Search(ctx context.Context, q domain.Query) (domain.Page, error)
}

// Suggester powers the search box's typeahead.
type Suggester interface {
	Suggest(ctx context.Context, market, prefix string) (domain.Suggestions, error)
}

// SearcherOption wires optional collaborators into the searcher.
type SearcherOption func(*productSearcherImpl)

// WithMarkets restricts searches to the configured markets (market filter); others are rejected.
func WithMarkets(markets []string) SearcherOption {
	return func(s *productSearcherImpl) {
		s.markets = map[string]bool{}
		for _, m := range markets {
			s.markets[strings.ToLower(strings.TrimSpace(m))] = true
		}
	}
}

// WithEmbedTimeout replaces the 150 ms embedding budget.
func WithEmbedTimeout(d time.Duration) SearcherOption {
	return func(s *productSearcherImpl) {
		if d > 0 {
			s.embedTimeout = d
		}
	}
}

// WithMinSemanticWords replaces the 3-word minimum for semantic retrieval.
func WithMinSemanticWords(n int) SearcherOption {
	return func(s *productSearcherImpl) {
		if n > 0 {
			s.minSemanticWords = n
		}
	}
}

// WithAds blends sponsored products into pages that ask for them.
func WithAds(ads AdServer) SearcherOption { return func(s *productSearcherImpl) { s.ads = ads } }

// WithAnalytics records tracked searches (aggregates) and publishes search.query.performed.
func WithAnalytics(rec AnalyticsRecorder, events port.EventPublisher) SearcherOption {
	return func(s *productSearcherImpl) { s.analytics, s.events = rec, events }
}

type productSearcherImpl struct {
	index            port.Index
	embedder         port.Embedder
	embedTimeout     time.Duration
	minSemanticWords int
	log              *slog.Logger
	markets          map[string]bool
	ads              AdServer
	analytics        AnalyticsRecorder
	events           port.EventPublisher
	now              func() time.Time
}

type suggesterImpl struct {
	index port.Index
}

var (
	_ ProductSearcher = (*productSearcherImpl)(nil)
	_ Suggester       = (*suggesterImpl)(nil)
)

// NewProductSearcher returns a searcher that blends semantic retrieval for natural-language queries
// ("something for a Diwali dinner") and degrades to lexical search when the embedder is slow or down.
func NewProductSearcher(index port.Index, embedder port.Embedder, log *slog.Logger, opts ...SearcherOption) ProductSearcher {
	s := &productSearcherImpl{index: index, embedder: embedder, embedTimeout: 150 * time.Millisecond,
		minSemanticWords: 3, log: log, now: time.Now}
	for _, o := range opts {
		o(s)
	}
	return s
}

// NewSuggester returns the typeahead use case.
func NewSuggester(index port.Index) Suggester { return &suggesterImpl{index: index} }

func (s *productSearcherImpl) Search(ctx context.Context, q domain.Query) (domain.Page, error) {
	q, err := q.Normalize()
	if err != nil {
		return domain.Page{}, fmt.Errorf("normalize: %w", err)
	}
	if s.markets != nil && !s.markets[q.Market] {
		return domain.Page{}, errors.Join(domain.ErrUnknownMarket, fmt.Errorf("market %q is not served", q.Market))
	}
	vec, skipped := s.embed(ctx, q)
	page, err := s.index.Search(ctx, q, vec)
	if err != nil {
		return domain.Page{}, fmt.Errorf("search %q: %w", q.Text, err)
	}
	if page.Retrieval == "" {
		page.Retrieval = domain.RetrievalLexical
	}
	if page.Retrieval == domain.RetrievalLexical && page.SemanticSkipped == "" {
		page.SemanticSkipped = skipped
	}
	page.Semantic = page.Retrieval == domain.RetrievalHybrid
	page.SearchID = newID()
	for i := range page.Hits {
		page.Hits[i].ApplyStockSignal()
	}
	if q.Ads && s.ads != nil {
		page = s.ads.Inject(ctx, q, page)
	}
	if q.Track && q.Text != "" && s.analytics != nil {
		s.track(ctx, q, page)
	}
	return page, nil
}

// embed asks the AI gateway for the query embedding when semantic retrieval applies: natural-language queries
// (3+ words) on the relevance ranking within the fused window. The embedder gets a 150 ms budget; when it fails or
// times out the search is lexical and the reason is reported.
func (s *productSearcherImpl) embed(ctx context.Context, q domain.Query) ([]float32, string) {
	switch {
	case len(strings.Fields(q.Text)) < s.minSemanticWords:
		return nil, domain.SkipShortQuery
	case s.embedder == nil:
		return nil, domain.SkipNotConfigured
	case q.Sort != domain.SortRelevance:
		return nil, domain.SkipSorted
	case (q.Page+1)*q.Size > domain.HybridWindow:
		return nil, domain.SkipDeepPage
	}
	ectx, cancel := context.WithTimeout(ctx, s.embedTimeout)
	defer cancel()
	vec, err := s.embedder.Embed(ectx, q.Text)
	if err != nil {
		reason := domain.SkipUnavailable
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(ectx.Err(), context.DeadlineExceeded) {
			reason = domain.SkipTimeout
		}
		s.log.WarnContext(ctx, "embedding failed; lexical only", "reason", reason, "error", err)
		return nil, reason
	}
	return vec, ""
}

// track counts the search for analytics and publishes search.query.performed.
func (s *productSearcherImpl) track(ctx context.Context, q domain.Query, page domain.Page) {
	now := s.now().UTC()
	zero := page.Total == 0
	if q.Page == 0 {
		s.analytics.Searched(q.Market, q.Text, zero, now)
	}
	if s.events == nil {
		return
	}
	ev := domain.QueryPerformed{SearchID: page.SearchID, Market: q.Market, Query: q.Text,
		Normalized: domain.AnalyticsKey(q.Text), Department: q.Department, Filters: filterNames(q), Sort: q.Sort,
		Page: q.Page, Total: page.Total, ZeroResults: zero, Semantic: page.Semantic, TookMs: page.TookMs,
		TopATINs: []string{}, Sponsored: []string{}, CustomerID: q.CustomerID, OccurredAt: now}
	for _, h := range page.Hits {
		switch {
		case h.Sponsored != nil:
			ev.Sponsored = append(ev.Sponsored, h.ATIN)
		case len(ev.TopATINs) < 10:
			ev.TopATINs = append(ev.TopATINs, h.ATIN)
		}
	}
	e, err := envelope(page.SearchID, domain.EventQueryPerformed, domain.AggregateSearch, page.SearchID, now, ev)
	if err != nil {
		s.log.WarnContext(ctx, "search event not published", "error", err)
		return
	}
	s.events.Publish(ctx, e)
}

func filterNames(q domain.Query) []string {
	out := []string{}
	add := func(on bool, name string) {
		if on {
			out = append(out, name)
		}
	}
	add(q.Department != "", "department")
	add(len(q.Communities) > 0, "community")
	add(q.Fulfilment != "", "fulfilment")
	add(q.SellerKind != "", "sellerKind")
	add(q.SellerID != "", "sellerId")
	add(q.MinRating > 0, "minRating")
	add(q.MinPrice > 0 || q.MaxPrice > 0, "price")
	add(q.MaxDays > 0, "maxDays")
	add(q.InStockOnly, "inStock")
	add(q.WithinMiles > 0, "distance")
	return out
}

func (s *suggesterImpl) Suggest(ctx context.Context, market, prefix string) (domain.Suggestions, error) {
	prefix = strings.Join(strings.Fields(prefix), " ")
	if len(prefix) < 1 {
		return domain.Suggestions{Phrases: []domain.Suggestion{}, Products: []domain.Hit{}}, nil
	}
	if len(prefix) > 100 {
		prefix = prefix[:100]
	}
	if market == "" {
		market = "us"
	}
	out, err := s.index.Suggest(ctx, strings.ToLower(market), prefix, 5)
	if err != nil {
		return domain.Suggestions{}, fmt.Errorf("suggest %q: %w", prefix, err)
	}
	for i := range out.Products {
		out.Products[i].ApplyStockSignal()
	}
	return out, nil
}
