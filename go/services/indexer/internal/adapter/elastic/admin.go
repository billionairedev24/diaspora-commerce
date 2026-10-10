package elastic

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
)

// IndexAdmin creates the market's product index.
type IndexAdmin interface {
	// EnsureIndex creates searchindex.IndexName(market) and points the alias at it when missing.
	EnsureIndex(ctx context.Context, market string) error
}

type indexAdminImpl struct {
	es  *elasticsearch.Client
	set string
}

var _ IndexAdmin = (*indexAdminImpl)(nil)

// NewIndexAdmin creates indices whose search analyzer reads the diaspora synonyms from the Elasticsearch synonyms
// set named set (owned and seeded by search-service; S039), so curated synonyms apply without reindexing. When the set
// does not exist yet it is created empty and search seeds it on start. An empty set name keeps the shared mapping's
// inline synonyms.
func NewIndexAdmin(es *elasticsearch.Client, set string) IndexAdmin {
	return &indexAdminImpl{es: es, set: set}
}

func (a *indexAdminImpl) EnsureIndex(ctx context.Context, market string) error {
	if a.set == "" {
		if err := searchindex.NewAdmin(a.es).EnsureIndex(ctx, market); err != nil {
			return fmt.Errorf("ensure index: %w", err)
		}
		return nil
	}
	name := searchindex.IndexName(market)
	status, _, err := a.call(ctx, http.MethodHead, "/"+name, nil)
	if err != nil {
		return err
	}
	if status == http.StatusNotFound {
		if err := a.ensureSet(ctx); err != nil {
			return err
		}
		settings, err := settingsWithSet(a.set)
		if err != nil {
			return err
		}
		status, body, err := a.call(ctx, http.MethodPut, "/"+name, settings)
		if err != nil {
			return err
		}
		if status >= 300 && !strings.Contains(string(body), "resource_already_exists_exception") {
			return fmt.Errorf("create %s: status %d: %s", name, status, body)
		}
	}
	alias, err := json.Marshal(map[string]any{"actions": []any{map[string]any{"add": map[string]string{
		"index": name, "alias": searchindex.Alias(market)}}}})
	if err != nil {
		return fmt.Errorf("encode alias: %w", err)
	}
	status, body, err := a.call(ctx, http.MethodPost, "/_aliases", alias)
	if err != nil {
		return err
	}
	if status >= 300 {
		return fmt.Errorf("alias %s: status %d: %s", name, status, body)
	}
	return nil
}

// ensureSet creates an empty synonyms set when missing (an index cannot reference a set that does not exist).
func (a *indexAdminImpl) ensureSet(ctx context.Context) error {
	path := "/_synonyms/" + url.PathEscape(a.set)
	status, body, err := a.call(ctx, http.MethodGet, path+"?size=1", nil)
	if err != nil {
		return err
	}
	switch status {
	case http.StatusOK:
		return nil
	case http.StatusNotFound:
		status, body, err = a.call(ctx, http.MethodPut, path, []byte(`{"synonyms_set":[]}`))
		if err != nil {
			return err
		}
		if status >= 300 {
			return fmt.Errorf("create synonyms set %s: status %d: %s", a.set, status, body)
		}
		return nil
	default:
		return fmt.Errorf("synonyms set %s: status %d: %s", a.set, status, body)
	}
}

// settingsWithSet is the shared mapping with the inline synonym list replaced by the synonyms set (synonym_graph,
// updateable: product_search is only ever a search analyzer).
func settingsWithSet(set string) ([]byte, error) {
	raw, err := searchindex.Mapping()
	if err != nil {
		return nil, fmt.Errorf("mapping: %w", err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("decode mapping: %w", err)
	}
	settings, _ := doc["settings"].(map[string]any)
	analysis, _ := settings["analysis"].(map[string]any)
	filters, ok := analysis["filter"].(map[string]any)
	if !ok {
		return nil, errors.New("mapping has no analysis filters")
	}
	filters["diaspora_synonyms"] = map[string]any{"type": "synonym_graph", "synonyms_set": set, "updateable": true,
		"lenient": true}
	out, err := json.Marshal(doc)
	if err != nil {
		return nil, fmt.Errorf("encode settings: %w", err)
	}
	return out, nil
}

func (a *indexAdminImpl) call(ctx context.Context, method, path string, body []byte) (int, []byte, error) {
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, path, rd)
	if err != nil {
		return 0, nil, fmt.Errorf("request %s: %w", path, err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := a.es.Perform(req)
	if err != nil {
		return 0, nil, fmt.Errorf("elasticsearch %s %s: %w", method, path, err)
	}
	defer func() { _ = res.Body.Close() }()
	out, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return 0, nil, fmt.Errorf("read %s: %w", path, err)
	}
	return res.StatusCode, out, nil
}
