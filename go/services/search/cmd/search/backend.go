package main

import (
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

// Search engines the service can run on (SEARCH_PROVIDER). Meilisearch is the default when it is unset.
const (
	providerElasticsearch = "elasticsearch"
	providerMeilisearch   = "meilisearch"
)

// backend is the search engine behind the ports: one implementation per provider, chosen once at start.
type backend struct {
	name  string
	index port.Index
	admin port.IndexAdmin
}

func newBackend(cfg config.Config, synonymsSet string) (backend, error) {
	switch p := strings.ToLower(strings.TrimSpace(cfg.SearchProvider)); p {
	case providerElasticsearch:
		es, err := newElasticsearch(cfg)
		if err != nil {
			return backend{}, err
		}
		return backend{name: providerElasticsearch, index: elastic.NewIndex(es), admin: elastic.NewAdmin(es, synonymsSet)}, nil
	case "", providerMeilisearch:
		if cfg.Meilisearch.URL == "" {
			return backend{}, fmt.Errorf("SEARCH_PROVIDER=meilisearch needs MEILISEARCH_URL")
		}
		c := meili.New(cfg.Meilisearch.URL, cfg.Meilisearch.APIKey, &http.Client{Timeout: 10 * time.Second})
		return backend{name: providerMeilisearch, index: meilisearch.NewIndex(c), admin: meilisearch.NewAdmin(c, cfg.Markets)}, nil
	default:
		return backend{}, fmt.Errorf("unknown SEARCH_PROVIDER %q (use %s or %s)", p, providerElasticsearch, providerMeilisearch)
	}
}
