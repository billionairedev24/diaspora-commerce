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
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// synonymFilter is the analysis filter of the shared mapping that holds the diaspora synonyms.
const synonymFilter = "diaspora_synonyms"

type adminImpl struct {
	es  *elasticsearch.Client
	set string
}

var _ port.IndexAdmin = (*adminImpl)(nil)

// NewAdmin manages the synonyms set named set and the product indices that reference it.
func NewAdmin(es *elasticsearch.Client, set string) port.IndexAdmin {
	return &adminImpl{es: es, set: set}
}

// IndexSettings returns the shared mapping (pkg/searchindex) with the inline synonym list replaced by a reference to
// the synonyms set: synonym_graph, updateable, so set edits reload the product_search analyzer without reindexing.
// product_search is only ever a search_analyzer, which updateable filters require.
func IndexSettings(set string) ([]byte, error) {
	raw, err := searchindex.Mapping()
	if err != nil {
		return nil, fmt.Errorf("mapping: %w", err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("decode mapping: %w", err)
	}
	filters, ok := dig(doc, "settings", "analysis", "filter")
	if !ok {
		return nil, errors.New("mapping has no analysis filters")
	}
	filters[synonymFilter] = synonymFilterSettings(set)
	out, err := json.Marshal(doc)
	if err != nil {
		return nil, fmt.Errorf("encode settings: %w", err)
	}
	return out, nil
}

func synonymFilterSettings(set string) map[string]any {
	return map[string]any{"type": "synonym_graph", "synonyms_set": set, "updateable": true, "lenient": true}
}

func dig(doc map[string]any, keys ...string) (map[string]any, bool) {
	cur := doc
	for _, k := range keys {
		next, ok := cur[k].(map[string]any)
		if !ok {
			return nil, false
		}
		cur = next
	}
	return cur, true
}

func (a *adminImpl) SeedSynonyms(ctx context.Context, rules []domain.SynonymRule, overwrite bool) (bool, error) {
	status, body, err := a.call(ctx, http.MethodGet, "/_synonyms/"+url.PathEscape(a.set)+"?size=1", nil)
	if err != nil {
		return false, err
	}
	if status == http.StatusOK && !overwrite {
		// The indexer creates the set empty when it creates an index first; an empty set is seeded.
		var existing struct {
			Count int `json:"count"`
		}
		if err := json.Unmarshal(body, &existing); err != nil {
			return false, fmt.Errorf("decode synonyms set %s: %w", a.set, err)
		}
		if existing.Count > 0 {
			return false, nil
		}
	}
	if status != http.StatusOK && status != http.StatusNotFound {
		return false, fmt.Errorf("get synonyms set %s: status %d", a.set, status)
	}
	set := map[string]any{"synonyms_set": toESRules(rules)}
	if err := a.mustOK(ctx, http.MethodPut, "/_synonyms/"+url.PathEscape(a.set), set); err != nil {
		return false, fmt.Errorf("put synonyms set %s: %w", a.set, err)
	}
	return true, nil
}

func toESRules(rules []domain.SynonymRule) []map[string]string {
	out := make([]map[string]string, 0, len(rules))
	for _, r := range rules {
		out = append(out, map[string]string{"id": r.ID, "synonyms": r.Synonyms})
	}
	return out
}

func (a *adminImpl) Synonyms(ctx context.Context) ([]domain.SynonymRule, error) {
	status, body, err := a.call(ctx, http.MethodGet, "/_synonyms/"+url.PathEscape(a.set)+"?size=10000", nil)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return []domain.SynonymRule{}, nil
	}
	if status != http.StatusOK {
		return nil, fmt.Errorf("get synonyms set %s: status %d: %s", a.set, status, body)
	}
	var res struct {
		Rules []domain.SynonymRule `json:"synonyms_set"`
	}
	if err := json.Unmarshal(body, &res); err != nil {
		return nil, fmt.Errorf("decode synonyms set: %w", err)
	}
	return res.Rules, nil
}

func (a *adminImpl) PutSynonym(ctx context.Context, rule domain.SynonymRule) error {
	path := "/_synonyms/" + url.PathEscape(a.set) + "/" + url.PathEscape(rule.ID)
	if err := a.mustOK(ctx, http.MethodPut, path, map[string]string{"synonyms": rule.Synonyms}); err != nil {
		return fmt.Errorf("put synonym %s: %w", rule.ID, err)
	}
	return nil
}

func (a *adminImpl) DeleteSynonym(ctx context.Context, id string) (bool, error) {
	status, body, err := a.call(ctx, http.MethodDelete, "/_synonyms/"+url.PathEscape(a.set)+"/"+url.PathEscape(id), nil)
	if err != nil {
		return false, err
	}
	switch status {
	case http.StatusOK:
		return true, nil
	case http.StatusNotFound:
		return false, nil
	default:
		return false, fmt.Errorf("delete synonym %s: status %d: %s", id, status, body)
	}
}

func (a *adminImpl) EnsureIndex(ctx context.Context, market string) error {
	name := searchindex.IndexName(market)
	status, _, err := a.call(ctx, http.MethodHead, "/"+name, nil)
	if err != nil {
		return err
	}
	if status == http.StatusNotFound {
		settings, err := IndexSettings(a.set)
		if err != nil {
			return err
		}
		status, body, err := a.callRaw(ctx, http.MethodPut, "/"+name, settings)
		if err != nil {
			return err
		}
		if status >= 300 && !strings.Contains(string(body), "resource_already_exists_exception") {
			return fmt.Errorf("create %s: status %d: %s", name, status, body)
		}
	}
	alias := map[string]any{"actions": []any{map[string]any{"add": map[string]string{
		"index": name, "alias": searchindex.Alias(market)}}}}
	if err := a.mustOK(ctx, http.MethodPost, "/_aliases", alias); err != nil {
		return fmt.Errorf("alias %s: %w", name, err)
	}
	return nil
}

func (a *adminImpl) UsesSynonymsSet(ctx context.Context, market string) (bool, error) {
	name := searchindex.IndexName(market)
	status, body, err := a.call(ctx, http.MethodGet, "/"+name+"/_settings/index.analysis.filter."+synonymFilter+".*", nil)
	if err != nil {
		return false, err
	}
	if status != http.StatusOK {
		return false, fmt.Errorf("settings %s: status %d: %s", name, status, body)
	}
	return strings.Contains(string(body), `"synonyms_set":"`+a.set+`"`), nil
}

func (a *adminImpl) MigrateToSynonymsSet(ctx context.Context, market string) error {
	name := searchindex.IndexName(market)
	if err := a.mustOK(ctx, http.MethodPost, "/"+name+"/_close?wait_for_active_shards=0", nil); err != nil {
		return fmt.Errorf("close %s: %w", name, err)
	}
	filter := synonymFilterSettings(a.set)
	filter["synonyms"] = nil // settings updates merge: drop the inline list, which would otherwise win
	settings := map[string]any{"analysis": map[string]any{"filter": map[string]any{synonymFilter: filter}}}
	updateErr := a.mustOK(ctx, http.MethodPut, "/"+name+"/_settings", settings)
	// Always reopen, even when the update failed, so the index never stays closed.
	if err := a.mustOK(ctx, http.MethodPost, "/"+name+"/_open?wait_for_active_shards=1", nil); err != nil {
		return errors.Join(updateErr, fmt.Errorf("reopen %s: %w", name, err))
	}
	if updateErr != nil {
		return fmt.Errorf("update analysis of %s: %w", name, updateErr)
	}
	return nil
}

func (a *adminImpl) mustOK(ctx context.Context, method, path string, body any) error {
	status, resp, err := a.call(ctx, method, path, body)
	if err != nil {
		return err
	}
	if status >= 300 {
		return fmt.Errorf("%s %s: status %d: %s", method, path, status, resp)
	}
	return nil
}

func (a *adminImpl) call(ctx context.Context, method, path string, body any) (int, []byte, error) {
	var raw []byte
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, fmt.Errorf("encode %s: %w", path, err)
		}
		raw = b
	}
	return a.callRaw(ctx, method, path, raw)
}

func (a *adminImpl) callRaw(ctx context.Context, method, path string, raw []byte) (int, []byte, error) {
	var rd io.Reader
	if raw != nil {
		rd = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, path, rd)
	if err != nil {
		return 0, nil, fmt.Errorf("request %s: %w", path, err)
	}
	if raw != nil {
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
