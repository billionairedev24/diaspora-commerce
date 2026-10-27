package app

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Lookups are the searchable pickers of the staff console's forms (drivers, sellers): staff pick by name, the form sends
// the id. A lookup needs the same module as the form that uses it, at View.
const (
	lookupDrivers = "drivers"
	lookupSellers = "sellers"
	// lookupMaxHits is how many matches a picker lists at once.
	lookupMaxHits = 20
	// lookupDriverPages caps how many pages of people are read to find drivers (50 per page).
	lookupDriverPages = 4
)

// lookupModules is the module each kind of lookup is part of.
var lookupModules = map[string]string{lookupDrivers: dispatchModule, lookupSellers: "payouts"}

// LookupModule is the module a lookup kind belongs to; false for a kind there is no such lookup for.
func LookupModule(kind string) (string, bool) {
	m, ok := lookupModules[kind]
	return m, ok
}

// Lookup finds drivers or sellers by name for a picker (?q=, at most lookupMaxHits matches, 2 characters or more
// once something is typed).
func (s *staffRecordsImpl) Lookup(ctx context.Context, c *domain.Caller, kind, q string) (domain.LookupResult, error) {
	module, ok := LookupModule(kind)
	if !ok {
		return domain.LookupResult{}, domain.NewProblem(404, domain.CodeNotFound, "Not found", "There is nothing to search for there.")
	}
	q = strings.TrimSpace(q)
	if n := len([]rune(q)); n > 120 || (n == 1) {
		return domain.LookupResult{}, domain.ValidationProblem(map[string]string{"q": "Type 2 to 120 characters"})
	}
	sc, err := s.r.require(ctx, c, module, domain.LevelView)
	if err != nil {
		return domain.LookupResult{}, err
	}
	out := domain.LookupResult{Kind: kind, Items: []domain.LookupHit{}}
	needle := strings.ToLower(q)
	switch kind {
	case lookupDrivers:
		for page := range lookupDriverPages {
			var p jobj
			if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", url.Values{"q": {q}, "page": {strconv.Itoa(page)}, "size": {"50"}}, &p); err != nil {
				return out, err
			}
			items := objs(p, "items")
			for _, u := range items {
				if !strings.EqualFold(jstr(u, "principalType"), "DRIVER") || jstr(u, "id") == "" {
					continue
				}
				out.Items = append(out.Items, domain.LookupHit{ID: jstr(u, "id"), Name: firstNonEmpty(jstr(u, "name"), jstr(u, "email"), "Driver"), Detail: jstr(u, "email")})
				if len(out.Items) >= lookupMaxHits {
					return out, nil
				}
			}
			if len(items) < 50 {
				break
			}
		}
	case lookupSellers:
		for page := range orgDirectoryPages {
			var p jobj
			if err := sc.get(ctx, sc.up.Seller, "/v1/orgs/directory", url.Values{"page": {strconv.Itoa(page)}, "size": {"200"}}, &p); err != nil {
				return out, err
			}
			items := objs(p, "items")
			for _, it := range items {
				id, name := jstr(it, "id"), firstNonEmpty(jstr(it, "displayName"), jstr(it, "name"))
				if id == "" || name == "" || (needle != "" && !strings.Contains(strings.ToLower(name), needle)) {
					continue
				}
				out.Items = append(out.Items, domain.LookupHit{ID: id, Name: name, Detail: strings.ToLower(strings.ReplaceAll(jstr(it, "kind"), "_", " "))})
				if len(out.Items) >= lookupMaxHits {
					return out, nil
				}
			}
			if len(items) < 200 {
				break
			}
		}
	}
	return out, nil
}
