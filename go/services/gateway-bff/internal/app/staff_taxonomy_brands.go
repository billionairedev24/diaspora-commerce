package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Brands tab of Catalog → Categories: the review queue of brands sellers typed in and the brand registry, over
// catalog's /v1/taxonomy/brands API (permission catalog:taxonomy). The seller who typed a brand is named, never shown
// as an id; every change carries a reason and the version it was loaded at (If-Match).

var (
	brandStatuses = []string{"APPROVED", "PENDING", "REJECTED", "MERGED_INTO", "RETIRED"}
	brandActions  = []string{"approve", "reject", "retire", "merge"}
	brandSplitRe  = regexp.MustCompile(`[\r\n,;]+`)
)

func brandPath(id string, rest ...string) (string, error) {
	if !taxonomyIDRe.MatchString(id) {
		return "", domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such brand.")
	}
	return "/v1/taxonomy/brands/" + id + strings.Join(rest, ""), nil
}

// Brands lists brands by status (comma-separated), name and scope node, with the seller organisation named.
func (s *staffTaxonomyImpl) Brands(ctx context.Context, c *domain.Caller, status, q, scope string, limit, offset int) (any, error) {
	errs := map[string]string{}
	var statuses []string
	for _, st := range strings.Split(status, ",") {
		if st = strings.ToUpper(strings.TrimSpace(st)); st != "" {
			if !slices.Contains(brandStatuses, st) {
				errs["status"] = "PENDING, APPROVED, REJECTED, MERGED_INTO or RETIRED"
			}
			statuses = append(statuses, st)
		}
	}
	if utf8.RuneCountInString(q) > 80 {
		errs["q"] = "At most 80 characters"
	}
	if scope = strings.TrimSpace(scope); scope != "" && !taxonomyIDRe.MatchString(scope) {
		errs["scope"] = "Choose one"
	}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	sc, canEdit, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	v := url.Values{"limit": {strconv.Itoa(limit)}, "offset": {strconv.Itoa(max(offset, 0))}}
	if len(statuses) > 0 {
		v.Set("status", strings.Join(statuses, ","))
	}
	if q = strings.TrimSpace(q); q != "" {
		v.Set("q", q)
	}
	if scope != "" {
		v.Set("scope", scope)
	}
	out, err := s.catalog(ctx, sc, http.MethodGet, "/v1/taxonomy/brands", "", v, nil)
	if err != nil {
		return nil, err
	}
	items, _ := out["brands"].([]any)
	if items == nil {
		items = []any{}
	}
	s.nameSellers(ctx, sc, items)
	out["brands"] = items
	if out["counts"] == nil {
		out["counts"] = jobj{}
	}
	out["canEdit"] = canEdit
	return out, nil
}

// nameSellers adds createdByName to each brand (null for brands staff listed).
func (s *staffTaxonomyImpl) nameSellers(ctx context.Context, sc staffCtx, items []any) {
	ids := []string{}
	for _, it := range items {
		if m, ok := it.(map[string]any); ok {
			ids = append(ids, jstr(m, "createdByOrg"))
		}
	}
	names := sc.names.resolve(ctx, sc, nameOrg, uniqueStrings(ids))
	for _, it := range items {
		m, ok := it.(map[string]any)
		if !ok {
			continue
		}
		org := jstr(m, "createdByOrg")
		delete(m, "createdByOrg")
		switch {
		case org == "":
			m["createdByName"] = nil
		case names[org] != "":
			m["createdByName"] = names[org]
		default:
			m["createdByName"] = "Unknown seller"
		}
	}
}

func scopeOf(body map[string]any, errs map[string]string) []string {
	ids := vlist(body, "scope")
	if len(ids) == 0 || len(ids) > 50 {
		errs["scope"] = "Choose where the brand applies"
	}
	for _, id := range ids {
		if !taxonomyIDRe.MatchString(id) {
			errs["scope"] = "Choose from the list"
			break
		}
	}
	return ids
}

func brandNameOf(v any, key string, errs map[string]string) string {
	n := tidy(v)
	if l := utf8.RuneCountInString(n); l < 2 || l > 120 {
		errs[key] = "2 to 120 characters"
	}
	return n
}

// CreateBrand adds one approved brand.
func (s *staffTaxonomyImpl) CreateBrand(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	errs := map[string]string{}
	b := jobj{"name": brandNameOf(body["name"], "name", errs), "scope": scopeOf(body, errs), "reason": taxReasonOf(body, errs)}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	out, err := s.catalog(ctx, sc, http.MethodPost, "/v1/taxonomy/brands", "", nil, b)
	if err == nil {
		s.nameSellers(ctx, sc, []any{out})
	}
	return out, err
}

// CreateBrands adds a pasted list: "names" as a list, or "text" split on new lines, commas and semicolons.
func (s *staffTaxonomyImpl) CreateBrands(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	errs := map[string]string{}
	names := []string{}
	raw := vlist(body, "names")
	if text := vs(body, "text"); text != "" {
		raw = append(raw, brandSplitRe.Split(text, -1)...)
	}
	for _, n := range raw {
		if n = tidy(n); n != "" {
			names = append(names, n)
		}
	}
	if len(names) == 0 || len(names) > 500 {
		errs["names"] = "Paste between 1 and 500 brands, one per line"
	}
	b := jobj{"names": names, "scope": scopeOf(body, errs), "reason": taxReasonOf(body, errs)}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	return s.catalog(ctx, sc, http.MethodPost, "/v1/taxonomy/brands/bulk", "", nil, b)
}

// UpdateBrand changes the name and/or the scope; catalog refuses a change that changes nothing.
func (s *staffTaxonomyImpl) UpdateBrand(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	path, err := brandPath(id)
	if err != nil {
		return nil, err
	}
	errs := map[string]string{}
	b := jobj{"reason": taxReasonOf(body, errs)}
	if _, ok := body["name"]; ok {
		b["name"] = brandNameOf(body["name"], "name", errs)
	}
	if _, ok := body["scope"]; ok {
		b["scope"] = scopeOf(body, errs)
	}
	if b["name"] == nil && b["scope"] == nil {
		errs["name"] = "Nothing to change"
	}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if err := checkIfMatch(ifMatch); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	out, err := s.catalog(ctx, sc, http.MethodPatch, path, ifMatch, nil, b)
	if err == nil {
		s.nameSellers(ctx, sc, []any{out})
	}
	return out, err
}

// DecideBrand approves, rejects, retires or merges (targetId) a brand.
func (s *staffTaxonomyImpl) DecideBrand(ctx context.Context, c *domain.Caller, id, action, ifMatch string, body map[string]any) (any, error) {
	if !slices.Contains(brandActions, action) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such action.")
	}
	path, err := brandPath(id, "/"+action)
	if err != nil {
		return nil, err
	}
	errs := map[string]string{}
	b := jobj{"reason": taxReasonOf(body, errs)}
	if action == "merge" {
		target := uuidOf(body, "targetId", true, errs)
		if target == id {
			errs["targetId"] = "Choose another brand"
		}
		b["targetId"] = target
	}
	if err := problemOf(errs); err != nil {
		return nil, err
	}
	if err := checkIfMatch(ifMatch); err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	out, err := s.catalog(ctx, sc, http.MethodPost, path, ifMatch, nil, b)
	if err == nil {
		s.nameSellers(ctx, sc, []any{out})
	}
	return out, err
}
