package meilisearch

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// rulesIndex holds the synonym rules (id → "a, b, c"). Meilisearch keeps synonyms as one map in each index's settings,
// with no rule identity, so the rules live here and are pushed to every market index whenever one changes.
const rulesIndex = "synonym-rules"

type adminImpl struct {
	c       *meili.Client
	markets []string
}

var _ port.IndexAdmin = (*adminImpl)(nil)

// NewAdmin manages the synonym rules and the market indexes that carry them.
func NewAdmin(c *meili.Client, markets []string) port.IndexAdmin {
	return &adminImpl{c: c, markets: markets}
}

func (a *adminImpl) ensureRulesIndex(ctx context.Context) error {
	err := a.c.Do(ctx, http.MethodGet, "/indexes/"+rulesIndex, nil, nil)
	if err == nil {
		return nil
	}
	if !meili.IsNotFound(err) {
		return err
	}
	if err := a.c.Task(ctx, http.MethodPost, "/indexes", object{"uid": rulesIndex, "primaryKey": "id"}); err != nil && !meili.IsCode(err, "index_already_exists") {
		return fmt.Errorf("create %s: %w", rulesIndex, err)
	}
	return nil
}

func (a *adminImpl) Synonyms(ctx context.Context) ([]domain.SynonymRule, error) {
	docs, err := a.c.Fetch(ctx, rulesIndex, meili.FetchRequest{Limit: 10000})
	if err != nil {
		if meili.IsNotFound(err) {
			return []domain.SynonymRule{}, nil
		}
		return nil, fmt.Errorf("synonym rules: %w", err)
	}
	rules := make([]domain.SynonymRule, 0, len(docs))
	for _, d := range docs {
		rules = append(rules, domain.SynonymRule{ID: searchindex.MeiliStr(d["id"]), Synonyms: searchindex.MeiliStr(d["synonyms"])})
	}
	slices.SortFunc(rules, func(x, y domain.SynonymRule) int { return strings.Compare(x.ID, y.ID) })
	return rules, nil
}

func (a *adminImpl) SeedSynonyms(ctx context.Context, rules []domain.SynonymRule, overwrite bool) (bool, error) {
	if err := a.ensureRulesIndex(ctx); err != nil {
		return false, err
	}
	current, err := a.Synonyms(ctx)
	if err != nil {
		return false, err
	}
	if len(current) > 0 && !overwrite {
		return false, a.apply(ctx, current)
	}
	if overwrite && len(current) > 0 {
		if err := a.c.Task(ctx, http.MethodDelete, "/indexes/"+rulesIndex+"/documents", nil); err != nil {
			return false, fmt.Errorf("clear synonym rules: %w", err)
		}
	}
	docs := make([]map[string]any, 0, len(rules))
	for _, r := range rules {
		docs = append(docs, object{"id": r.ID, "synonyms": r.Synonyms})
	}
	if err := a.c.Put(ctx, rulesIndex, docs); err != nil {
		return false, fmt.Errorf("write synonym rules: %w", err)
	}
	return true, a.apply(ctx, rules)
}

func (a *adminImpl) PutSynonym(ctx context.Context, rule domain.SynonymRule) error {
	if err := a.ensureRulesIndex(ctx); err != nil {
		return err
	}
	if err := a.c.Put(ctx, rulesIndex, []map[string]any{{"id": rule.ID, "synonyms": rule.Synonyms}}); err != nil {
		return fmt.Errorf("put synonym %s: %w", rule.ID, err)
	}
	return a.applyAll(ctx)
}

func (a *adminImpl) DeleteSynonym(ctx context.Context, id string) (bool, error) {
	doc, err := a.c.Get(ctx, rulesIndex, id)
	if err != nil || doc == nil {
		return false, err
	}
	if err := a.c.Task(ctx, http.MethodDelete, "/indexes/"+rulesIndex+"/documents/"+url.PathEscape(id), nil); err != nil {
		return false, fmt.Errorf("delete synonym %s: %w", id, err)
	}
	return true, a.applyAll(ctx)
}

func (a *adminImpl) applyAll(ctx context.Context) error {
	rules, err := a.Synonyms(ctx)
	if err != nil {
		return err
	}
	return a.apply(ctx, rules)
}

// apply pushes the rules to every market index that exists (the settings update re-indexes nothing: synonyms apply at query time).
func (a *adminImpl) apply(ctx context.Context, rules []domain.SynonymRule) error {
	set := SynonymMap(rules)
	for _, m := range a.markets {
		uid := searchindex.MeiliIndexUID(m)
		err := a.c.Task(ctx, http.MethodPut, "/indexes/"+url.PathEscape(uid)+"/settings/synonyms", set)
		if err != nil && !meili.IsNotFound(err) {
			return fmt.Errorf("synonyms for %s: %w", uid, err)
		}
	}
	return nil
}

// SynonymMap turns equivalence rules ("gari, garri, cassava flakes") into Meilisearch's term → alternatives map.
func SynonymMap(rules []domain.SynonymRule) map[string][]string {
	m := map[string][]string{}
	for _, r := range rules {
		terms := []string{}
		for _, t := range strings.Split(r.Synonyms, ",") {
			if t = strings.Join(strings.Fields(strings.ToLower(t)), " "); t != "" && !slices.Contains(terms, t) {
				terms = append(terms, t)
			}
		}
		for _, t := range terms {
			for _, other := range terms {
				if other != t && !slices.Contains(m[t], other) {
					m[t] = append(m[t], other)
				}
			}
		}
	}
	for _, v := range m {
		slices.Sort(v)
	}
	return m
}

func (a *adminImpl) EnsureIndex(ctx context.Context, market string) error {
	rules, err := a.Synonyms(ctx)
	if err != nil {
		return err
	}
	return searchindex.MeiliEnsureIndex(ctx, a.c, market, SynonymMap(rules))
}

// UsesSynonymsSet is always true: there is no inline-synonym index layout to migrate from.
func (a *adminImpl) UsesSynonymsSet(context.Context, string) (bool, error) { return true, nil }

// MigrateToSynonymsSet does nothing on Meilisearch.
func (a *adminImpl) MigrateToSynonymsSet(context.Context, string) error { return nil }
