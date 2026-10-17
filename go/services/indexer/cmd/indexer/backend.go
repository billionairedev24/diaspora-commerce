package main

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/elastic"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/meilisearch"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// Search engines the service can write to (SEARCH_PROVIDER); search-service reads the same one.
const (
	providerElasticsearch = "elasticsearch"
	providerMeilisearch   = "meilisearch"
)

// backend is the search engine behind the ProductIndex port, chosen once at start.
type backend struct {
	name  string
	index port.ProductIndex
	// ensureIndex creates the market's index when it does not exist.
	ensureIndex func(ctx context.Context, market string) error
}

func newBackend(cfg config.Config) (backend, error) {
	switch p := strings.ToLower(strings.TrimSpace(cfg.SearchProvider)); p {
	case "", providerElasticsearch:
		opts := []elasticsearch.Option{elasticsearch.WithAddresses(cfg.Elasticsearch.URLs...), elasticsearch.WithRetry(3)}
		switch {
		case cfg.Elasticsearch.APIKey != "":
			opts = append(opts, elasticsearch.WithAPIKey(cfg.Elasticsearch.APIKey))
		case cfg.Elasticsearch.Username != "":
			opts = append(opts, elasticsearch.WithBasicAuth(cfg.Elasticsearch.Username, cfg.Elasticsearch.Password))
		}
		es, err := elasticsearch.New(opts...)
		if err != nil {
			return backend{}, fmt.Errorf("elasticsearch client: %w", err)
		}
		admin := elastic.NewIndexAdmin(es, cfg.SynonymsSet)
		return backend{name: providerElasticsearch, index: elastic.NewIndex(es, cfg.Elasticsearch.Refresh), ensureIndex: admin.EnsureIndex}, nil
	case providerMeilisearch:
		if cfg.Meilisearch.URL == "" {
			return backend{}, fmt.Errorf("SEARCH_PROVIDER=meilisearch needs MEILISEARCH_URL")
		}
		c := meili.New(cfg.Meilisearch.URL, cfg.Meilisearch.APIKey, &http.Client{Timeout: 30 * time.Second})
		return backend{name: providerMeilisearch, index: meilisearch.NewIndex(c), ensureIndex: meilisearch.NewIndexAdmin(c).EnsureIndex}, nil
	default:
		return backend{}, fmt.Errorf("unknown SEARCH_PROVIDER %q (use %s or %s)", p, providerElasticsearch, providerMeilisearch)
	}
}
