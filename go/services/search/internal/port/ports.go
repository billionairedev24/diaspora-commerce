// Package port declares the outbound interfaces the application depends on.
package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// Index is the search engine (implemented by the Elasticsearch adapter).
type Index interface {
	// Search runs a lexical query, blended with kNN when vec is non-nil.
	Search(ctx context.Context, q domain.Query, vec []float32) (domain.Page, error)
	// Suggest returns autocomplete phrases and products for a prefix.
	Suggest(ctx context.Context, market, prefix string, limit int) (domain.Suggestions, error)
	// Ping checks connectivity for readiness probes.
	Ping(ctx context.Context) error
}

// Embedder turns text into a vector (implemented by the Spring AI gateway client).
type Embedder interface {
	Embed(ctx context.Context, text string) ([]float32, error)
}
