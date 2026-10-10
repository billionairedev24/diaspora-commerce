// Package port declares the outbound interfaces the application depends on.
package port

import (
	"context"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

// Index is the search engine (implemented by the Elasticsearch adapter).
type Index interface {
	// Search runs a lexical query, blended with kNN when vec is non-nil.
	Search(ctx context.Context, q domain.Query, vec []float32) (domain.Page, error)
	// Suggest returns autocomplete phrases and products for a prefix.
	Suggest(ctx context.Context, market, prefix string, limit int) (domain.Suggestions, error)
	// AdCandidates scores the given products against the shopper's query and filters with the organic ranking, keeping
	// only those eligible to be advertised: live (seller not hidden) and in stock. Products that do not match the query
	// text are not returned.
	AdCandidates(ctx context.Context, q domain.Query, atins []string) ([]domain.Hit, error)
	// Ping checks connectivity for readiness probes.
	Ping(ctx context.Context) error
}

// IndexAdmin manages the diaspora synonyms set and the product indices that reference it.
type IndexAdmin interface {
	// SeedSynonyms writes rules to the synonyms set when it does not exist (or always with overwrite) and reports
	// whether it wrote.
	SeedSynonyms(ctx context.Context, rules []domain.SynonymRule, overwrite bool) (bool, error)
	// Synonyms lists the set's rules.
	Synonyms(ctx context.Context) ([]domain.SynonymRule, error)
	// PutSynonym creates or replaces one rule; search analyzers reload without reindexing.
	PutSynonym(ctx context.Context, rule domain.SynonymRule) error
	// DeleteSynonym removes one rule and reports whether it existed.
	DeleteSynonym(ctx context.Context, id string) (bool, error)
	// EnsureIndex creates the market's index (settings referencing the synonyms set) and alias when missing.
	EnsureIndex(ctx context.Context, market string) error
	// UsesSynonymsSet reports whether the market's index already references the synonyms set.
	UsesSynonymsSet(ctx context.Context, market string) (bool, error)
	// MigrateToSynonymsSet switches an index created with inline synonyms to the set (close, update, reopen).
	MigrateToSynonymsSet(ctx context.Context, market string) error
}

// Embedder turns text into a vector (implemented by the Spring AI gateway client).
type Embedder interface {
	Embed(ctx context.Context, text string) ([]float32, error)
}

// AnalyticsStore keeps the daily per-query aggregates (searches, zero results, clicks).
type AnalyticsStore interface {
	// Add applies increments to the daily aggregates.
	Add(ctx context.Context, deltas []domain.AnalyticsDelta) error
	TopQueries(ctx context.Context, r domain.AnalyticsRange) ([]domain.QueryStat, error)
	ZeroResultQueries(ctx context.Context, r domain.AnalyticsRange) ([]domain.QueryStat, error)
	DailyCTR(ctx context.Context, r domain.AnalyticsRange) ([]domain.DailyCTR, error)
	// Purge deletes the market's aggregates of days before the given time (retention).
	Purge(ctx context.Context, market string, before time.Time) error
}

// CampaignSource returns the sponsored-products campaigns eligible to serve in a market (content-service).
type CampaignSource interface {
	Eligible(ctx context.Context, market string) ([]domain.Campaign, error)
}

// AdTokens signs and verifies the opaque click token carried by each served ad.
type AdTokens interface {
	Sign(t domain.AdToken) (string, error)
	// Verify checks the signature and returns the token; expiry is checked by the caller.
	Verify(token string) (domain.AdToken, error)
}

// EventPublisher publishes analytics and ads events without blocking the request path.
type EventPublisher interface {
	Publish(ctx context.Context, e events.Envelope)
}
