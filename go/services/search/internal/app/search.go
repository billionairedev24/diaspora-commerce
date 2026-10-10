// Package app holds the search use cases.
package app

import (
	"context"
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

type productSearcherImpl struct {
	index            port.Index
	embedder         port.Embedder
	embedTimeout     time.Duration
	minSemanticWords int
	log              *slog.Logger
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
func NewProductSearcher(index port.Index, embedder port.Embedder, log *slog.Logger) ProductSearcher {
	return &productSearcherImpl{index: index, embedder: embedder, embedTimeout: 150 * time.Millisecond,
		minSemanticWords: 3, log: log}
}

// NewSuggester returns the typeahead use case.
func NewSuggester(index port.Index) Suggester { return &suggesterImpl{index: index} }

func (s *productSearcherImpl) Search(ctx context.Context, q domain.Query) (domain.Page, error) {
	q, err := q.Normalize()
	if err != nil {
		return domain.Page{}, fmt.Errorf("normalize: %w", err)
	}
	var vec []float32
	if s.embedder != nil && len(strings.Fields(q.Text)) >= s.minSemanticWords {
		ectx, cancel := context.WithTimeout(ctx, s.embedTimeout)
		vec, err = s.embedder.Embed(ectx, q.Text)
		cancel()
		if err != nil {
			s.log.WarnContext(ctx, "embedding failed; lexical only", "error", err)
			vec = nil
		}
	}
	page, err := s.index.Search(ctx, q, vec)
	if err != nil {
		return domain.Page{}, fmt.Errorf("search %q: %w", q.Text, err)
	}
	page.Semantic = vec != nil
	for i := range page.Hits {
		h := &page.Hits[i]
		h.LowStock = h.InStock && h.AvailableQty > 0 && h.AvailableQty <= domain.LowStockThreshold
	}
	return page, nil
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
	out, err := s.index.Suggest(ctx, market, prefix, 5)
	if err != nil {
		return domain.Suggestions{}, fmt.Errorf("suggest %q: %w", prefix, err)
	}
	return out, nil
}
