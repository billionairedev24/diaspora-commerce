package main

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/meilisearch"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// Search engines the service can run on (SEARCH_PROVIDER).
const (
	providerElasticsearch = "elasticsearch"
	providerMeilisearch   = "meilisearch"
)

// backend is the search engine behind the ports: one implementation per provider, chosen once at start.
type backend struct {
	name  string
	index port.Index
	admin port.IndexAdmin
	// analytics returns the daily-aggregate store.
	analytics func() port.AnalyticsStore
	// ensureAnalytics creates a market's analytics index.
	ensureAnalytics func(ctx context.Context, market string) error
}

func newBackend(cfg config.Config, synonymsSet string) (backend, error) {
	switch p := strings.ToLower(strings.TrimSpace(cfg.SearchProvider)); p {
	case "", providerElasticsearch:
		es, err := newElasticsearch(cfg)
		if err != nil {
			return backend{}, err
		}
		return backend{name: providerElasticsearch, index: elastic.NewIndex(es), admin: elastic.NewAdmin(es, synonymsSet),
			analytics:       func() port.AnalyticsStore { return elastic.NewAnalyticsStore(es) },
			ensureAnalytics: func(ctx context.Context, m string) error { return elastic.EnsureAnalyticsIndex(ctx, es, m) }}, nil
	case providerMeilisearch:
		if cfg.Meilisearch.URL == "" {
			return backend{}, fmt.Errorf("SEARCH_PROVIDER=meilisearch needs MEILISEARCH_URL")
		}
		c := meili.New(cfg.Meilisearch.URL, cfg.Meilisearch.APIKey, &http.Client{Timeout: 10 * time.Second})
		return backend{name: providerMeilisearch, index: meilisearch.NewIndex(c), admin: meilisearch.NewAdmin(c, cfg.Markets),
			analytics:       func() port.AnalyticsStore { return meilisearch.NewAnalyticsStore(c) },
			ensureAnalytics: func(ctx context.Context, m string) error { return meilisearch.EnsureAnalyticsIndex(ctx, c, m) }}, nil
	default:
		return backend{}, fmt.Errorf("unknown SEARCH_PROVIDER %q (use %s or %s)", p, providerElasticsearch, providerMeilisearch)
	}
}
