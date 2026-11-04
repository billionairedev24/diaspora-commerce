// Package catalog implements port.CatalogReader over catalog-service's public category tree.
package catalog

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

type clientImpl struct {
	baseURL string
	http    *http.Client
	ttl     time.Duration

	mu      sync.RWMutex
	byID    map[string]domain.Category
	fetched time.Time
}

var _ port.CatalogReader = (*clientImpl)(nil)

// NewClient caches GET {baseURL}/v1/categories?includeRetired=true and refreshes on TTL expiry or unknown ids.
// Retired nodes are included: they still name the products that keep them.
func NewClient(baseURL string, httpClient *http.Client) port.CatalogReader {
	return &clientImpl{baseURL: strings.TrimSuffix(baseURL, "/"), http: httpClient, ttl: 5 * time.Minute,
		byID: map[string]domain.Category{}}
}

func (c *clientImpl) Category(ctx context.Context, id string) (domain.Category, error) {
	c.mu.RLock()
	cat, ok := c.byID[id]
	fresh := time.Since(c.fetched) < c.ttl
	c.mu.RUnlock()
	if ok && fresh {
		return cat, nil
	}
	if err := c.refresh(ctx); err != nil {
		if ok {
			return cat, nil
		}
		return domain.Category{}, err
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if cat, ok := c.byID[id]; ok {
		return cat, nil
	}
	return domain.Category{}, fmt.Errorf("unknown category %s", id)
}

type node struct {
	ID   string `json:"id"`
	Path string `json:"path"`
}

func (c *clientImpl) refresh(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/v1/categories?includeRetired=true", nil)
	if err != nil {
		return fmt.Errorf("categories request: %w", err)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("categories: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("categories: status %d", res.StatusCode)
	}
	var nodes []node
	if err := json.NewDecoder(res.Body).Decode(&nodes); err != nil {
		return fmt.Errorf("categories decode: %w", err)
	}
	m := make(map[string]domain.Category, len(nodes))
	for _, n := range nodes {
		dept, _, _ := strings.Cut(n.Path, " › ")
		m[n.ID] = domain.Category{Department: dept, Path: n.Path}
	}
	c.mu.Lock()
	c.byID, c.fetched = m, time.Now()
	c.mu.Unlock()
	return nil
}
