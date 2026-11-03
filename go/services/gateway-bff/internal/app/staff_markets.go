package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffMarketsService is Settings → Markets & locations as one location tree (contracts/openapi/gateway-bff-staff.yaml,
// tag Markets): countries → states → cities with LIVE / PLANNED / OFF, edited in place on content's draft (each edit
// carries the version it loaded as If-Match) and published as a new version. What depends on a place (open
// pre-orders, from delivery-runs) rides along so the console can guard removals.
type StaffMarketsService interface {
	Countries(ctx context.Context, c *domain.Caller) (any, error)
	Country(ctx context.Context, c *domain.Caller, code string) (any, error)
	UpdateCountry(ctx context.Context, c *domain.Caller, code, ifMatch string, body map[string]any) (any, error)
	AddRegion(ctx context.Context, c *domain.Caller, code string, body map[string]any) (any, error)
	UpdateRegion(ctx context.Context, c *domain.Caller, code, region, ifMatch string, body map[string]any) (any, error)
	AddCities(ctx context.Context, c *domain.Caller, code, region, ifMatch string, body map[string]any) (any, error)
	SetCities(ctx context.Context, c *domain.Caller, code, region, ifMatch string, body map[string]any) (any, error)
	RemoveCity(ctx context.Context, c *domain.Caller, code, region, city, ifMatch string) (any, error)
	Publish(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	// Places browses geo's gazetteer for the location pickers (Markets and Pre-order areas share it).
	Places(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
}

type staffMarketsImpl struct{ r *staffResolver }

var _ StaffMarketsService = (*staffMarketsImpl)(nil)

// NewStaffMarketsService returns the Markets & locations use case.
func NewStaffMarketsService(d StaffDeps) StaffMarketsService {
	return &staffMarketsImpl{r: newStaffResolver(d)}
}

const (
	marketsModule    = "markets"
	permMarketsEdit  = "content:markets.edit"
	marketsDraftPath = "/v1/markets/draft/countries"
	// maxCityBatch is how many cities one add or switch may name (content's limit).
	maxCityBatch = 2000
)

var (
	countryCodeRe = regexp.MustCompile(`^[A-Za-z]{2}$`)
	regionCodeRe  = regexp.MustCompile(`^[A-Za-z0-9]{1,10}$`)
	marketStatus  = []string{"LIVE", "PAUSED", "COMING_SOON", "PLANNED"}
	placeStatus   = []string{"LIVE", "PLANNED", "OFF"}
)

func notFoundPlace() error {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such country, state or province.")
}

// need resolves the staff member: View for reads, View + content:markets.edit + Edit level for writes.
func (s *staffMarketsImpl) need(ctx context.Context, c *domain.Caller, edit bool) (staffCtx, bool, error) {
	sc, err := s.r.require(ctx, c, marketsModule, domain.LevelView)
	if err != nil {
		return sc, false, err
	}
	sc.nameWho = true
	canEdit := sc.access.Can(permMarketsEdit) && sc.access.Allows(marketsModule, domain.LevelEdit)
	if edit && !canEdit {
		if !sc.access.Allows(marketsModule, domain.LevelEdit) {
			return sc, false, staffModuleDenied(marketsModule, domain.LevelEdit)
		}
		return sc, false, needsPermission(permMarketsEdit)
	}
	if sc.up.Content == nil {
		return sc, canEdit, domain.UpstreamUnavailable("content")
	}
	return sc, canEdit, nil
}

func countryPath(code string, rest ...string) (string, error) {
	code = strings.ToUpper(strings.TrimSpace(code))
	if !countryCodeRe.MatchString(code) {
		return "", notFoundPlace()
	}
	return marketsDraftPath + "/" + url.PathEscape(code) + strings.Join(rest, ""), nil
}

func regionPathOf(code, region string, rest ...string) (string, error) {
	region = strings.ToUpper(strings.TrimSpace(region))
	if !regionCodeRe.MatchString(region) {
		return "", notFoundPlace()
	}
	return countryPath(code, append([]string{"/regions/" + url.PathEscape(region)}, rest...)...)
}

func (s *staffMarketsImpl) contentCall(ctx context.Context, sc staffCtx, method, path, ifMatch string, q url.Values, body any) (jobj, error) {
	var out jobj
	err := sc.call(ctx, sc.up.Content, port.StaffCall{Method: method, Path: path, Query: q, Body: body, IfMatch: ifMatch}, &out)
	return out, err
}

func (s *staffMarketsImpl) Countries(ctx context.Context, c *domain.Caller) (any, error) {
	sc, canEdit, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	var list []any
	if err := sc.get(ctx, sc.up.Content, marketsDraftPath, nil, &list); err != nil {
		return nil, err
	}
	var changes jobj
	if err := sc.get(ctx, sc.up.Content, "/v1/markets/draft/changes", nil, &changes); err != nil {
		changes = jobj{"pending": false}
	}
	if list == nil {
		list = []any{}
	}
	return jobj{"items": list, "changes": changes, "canEdit": canEdit}, nil
}

func (s *staffMarketsImpl) Country(ctx context.Context, c *domain.Caller, code string) (any, error) {
	path, err := countryPath(code)
	if err != nil {
		return nil, err
	}
	sc, canEdit, err := s.need(ctx, c, false)
	if err != nil {
		return nil, err
	}
	out, err := s.contentCall(ctx, sc, http.MethodGet, path, "", nil, nil)
	if err != nil {
		return nil, err
	}
	return s.decorate(ctx, sc, out, canEdit), nil
}

// decorate adds what depends on each state and city: the open pre-orders selling there (delivery-runs). Best
// effort: without delivery-runs (or its read permission) the counts are left out and nothing is blocked here.
func (s *staffMarketsImpl) decorate(ctx context.Context, sc staffCtx, country jobj, canEdit bool) jobj {
	if country == nil {
		return country
	}
	country["canEdit"] = canEdit
	if sc.up.DeliveryRuns == nil {
		return country
	}
	code := jstr(country, "code")
	var page jobj
	q := url.Values{"country": {code}, "size": {"100"}}
	if err := sc.get(ctx, sc.up.DeliveryRuns, preorderAreas, q, &page); err != nil {
		return country
	}
	byRegion := map[string]int{}
	for _, row := range objs(page, "rows") {
		parts := strings.Split(jstr(row, "key"), "/")
		if len(parts) == 2 {
			byRegion[parts[1]] = int(num(row, "windows"))
		}
	}
	for _, r := range objs(country, "regions") {
		rc := jstr(r, "code")
		r["preorders"] = byRegion[rc]
		if byRegion[rc] == 0 || len(objs(r, "cities")) == 0 {
			continue
		}
		sellers := s.citySellers(ctx, sc, code, rc)
		for _, city := range objs(r, "cities") {
			if w := sellers[strings.ToLower(jstr(city, "name"))]; len(w) > 0 {
				city["preorders"] = w
			}
		}
	}
	return country
}

// citySellers maps lower-cased city names to the open pre-orders selling there (delivery-runs region view).
func (s *staffMarketsImpl) citySellers(ctx context.Context, sc staffCtx, country, region string) map[string][]string {
	out := map[string][]string{}
	var view jobj
	if err := sc.get(ctx, sc.up.DeliveryRuns, preorderAreas+"/regions/"+url.PathEscape(country)+"/"+url.PathEscape(region), nil, &view); err != nil {
		return out
	}
	for _, c := range objs(view, "cities") {
		if w := strs(c, "windows"); len(w) > 0 {
			out[strings.ToLower(jstr(c, "name"))] = w
		}
	}
	return out
}

func (s *staffMarketsImpl) write(ctx context.Context, c *domain.Caller, method, path, ifMatch string, body any) (any, error) {
	sc, canEdit, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	out, err := s.contentCall(ctx, sc, method, path, ifMatch, nil, body)
	if err != nil {
		return nil, err
	}
	return s.decorate(ctx, sc, out, canEdit), nil
}

func (s *staffMarketsImpl) UpdateCountry(ctx context.Context, c *domain.Caller, code, ifMatch string, body map[string]any) (any, error) {
	path, err := countryPath(code)
	if err != nil {
		return nil, err
	}
	b := jobj{}
	if v, ok := body["status"]; ok {
		st, _ := v.(string)
		if !slices.Contains(marketStatus, st) {
			return nil, domain.ValidationProblem(map[string]string{"status": "LIVE, PAUSED, COMING_SOON or PLANNED"})
		}
		b["status"] = st
	}
	if v, ok := body["launchNote"]; ok {
		note, _ := v.(string)
		if len(note) > 500 {
			return nil, domain.ValidationProblem(map[string]string{"launchNote": "At most 500 characters"})
		}
		b["launchNote"] = note
	}
	if len(b) == 0 {
		return nil, domain.ValidationProblem(map[string]string{"status": "Change the status or the launch note"})
	}
	return s.write(ctx, c, http.MethodPatch, path, ifMatch, b)
}

func (s *staffMarketsImpl) AddRegion(ctx context.Context, c *domain.Caller, code string, body map[string]any) (any, error) {
	path, err := countryPath(code, "/regions")
	if err != nil {
		return nil, err
	}
	rc, _ := body["code"].(string)
	name, _ := body["name"].(string)
	rc, name = strings.ToUpper(strings.TrimSpace(rc)), strings.TrimSpace(name)
	if !regionCodeRe.MatchString(rc) || name == "" || len(name) > 80 {
		return nil, domain.ValidationProblem(map[string]string{"code": "Pick a state or province (code and name)"})
	}
	return s.write(ctx, c, http.MethodPost, path, "", jobj{"code": rc, "name": name})
}

func (s *staffMarketsImpl) UpdateRegion(ctx context.Context, c *domain.Caller, code, region, ifMatch string, body map[string]any) (any, error) {
	path, err := regionPathOf(code, region)
	if err != nil {
		return nil, err
	}
	b := jobj{}
	if v, ok := body["status"]; ok {
		st, _ := v.(string)
		if !slices.Contains(placeStatus, st) {
			return nil, domain.ValidationProblem(map[string]string{"status": "LIVE, PLANNED or OFF"})
		}
		b["status"] = st
	}
	for _, k := range []string{"everyCity", "marketplace", "services", "food", "pickup", "taxRegistration"} {
		if v, ok := body[k]; ok {
			on, isBool := v.(bool)
			if !isBool {
				return nil, domain.ValidationProblem(map[string]string{k: "true or false"})
			}
			b[k] = on
		}
	}
	if len(b) == 0 {
		return nil, domain.ValidationProblem(map[string]string{"status": "Nothing to change"})
	}
	return s.write(ctx, c, http.MethodPatch, path, ifMatch, b)
}

func cityName(v any) (string, bool) {
	s, _ := v.(string)
	s = strings.TrimSpace(s)
	return s, s != "" && len(s) <= 80 && !strings.Contains(s, "/")
}

func (s *staffMarketsImpl) AddCities(ctx context.Context, c *domain.Caller, code, region, ifMatch string, body map[string]any) (any, error) {
	path, err := regionPathOf(code, region, "/cities")
	if err != nil {
		return nil, err
	}
	raw, _ := body["cities"].([]any)
	if len(raw) == 0 || len(raw) > maxCityBatch {
		return nil, domain.ValidationProblem(map[string]string{"cities": "Add 1 to 2000 cities"})
	}
	cities := make([]jobj, 0, len(raw))
	for _, v := range raw {
		m, _ := v.(map[string]any)
		name, ok := cityName(m["name"])
		st, _ := m["status"].(string)
		if st == "" {
			st = "PLANNED"
		}
		if !ok || !slices.Contains(placeStatus, st) {
			return nil, domain.ValidationProblem(map[string]string{"cities": "Each city needs a name (no '/', at most 80 characters) and LIVE, PLANNED or OFF"})
		}
		cities = append(cities, jobj{"name": name, "status": st})
	}
	return s.write(ctx, c, http.MethodPost, path, ifMatch, jobj{"cities": cities})
}

func (s *staffMarketsImpl) SetCities(ctx context.Context, c *domain.Caller, code, region, ifMatch string, body map[string]any) (any, error) {
	path, err := regionPathOf(code, region, "/cities")
	if err != nil {
		return nil, err
	}
	raw, _ := body["names"].([]any)
	st, _ := body["status"].(string)
	if len(raw) == 0 || len(raw) > maxCityBatch || !slices.Contains(placeStatus, st) {
		return nil, domain.ValidationProblem(map[string]string{"names": "Name 1 to 2000 cities and LIVE, PLANNED or OFF"})
	}
	names := make([]string, 0, len(raw))
	for _, v := range raw {
		n, ok := cityName(v)
		if !ok {
			return nil, domain.ValidationProblem(map[string]string{"names": "City names have at most 80 characters and no '/'"})
		}
		names = append(names, n)
	}
	return s.write(ctx, c, http.MethodPatch, path, ifMatch, jobj{"names": names, "status": st})
}

// RemoveCity removes one listed city, refused (409 markets.city-in-use) while an open pre-order sells there: the
// console offers switching it off instead.
func (s *staffMarketsImpl) RemoveCity(ctx context.Context, c *domain.Caller, code, region, city, ifMatch string) (any, error) {
	path, err := regionPathOf(code, region, "/cities")
	if err != nil {
		return nil, err
	}
	name, ok := cityName(city)
	if !ok {
		return nil, notFoundPlace()
	}
	sc, canEdit, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	if sc.up.DeliveryRuns != nil {
		cc, rc := strings.ToUpper(strings.TrimSpace(code)), strings.ToUpper(strings.TrimSpace(region))
		if w := s.citySellers(ctx, sc, cc, rc)[strings.ToLower(name)]; len(w) > 0 {
			return nil, domain.NewProblem(http.StatusConflict, "markets.city-in-use", "City in use",
				name+" has "+strconv.Itoa(len(w))+" open pre-order"+plural(len(w))+" ("+strings.Join(w, ", ")+"). Switch it off instead of removing it.")
		}
	}
	out, err := s.contentCall(ctx, sc, http.MethodDelete, path, ifMatch, url.Values{"name": {name}}, nil)
	if err != nil {
		return nil, err
	}
	return s.decorate(ctx, sc, out, canEdit), nil
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}

func (s *staffMarketsImpl) Publish(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	note, _ := body["note"].(string)
	if len(note) > 500 {
		return nil, domain.ValidationProblem(map[string]string{"note": "At most 500 characters"})
	}
	sc, _, err := s.need(ctx, c, true)
	if err != nil {
		return nil, err
	}
	var out jobj
	if err := sc.post(ctx, sc.up.Content, "/v1/markets/publications", omitEmpty(jobj{"note": strings.TrimSpace(note)}), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// Places: no country → countries; country → its states / provinces; country + region → its cities (q filters
// cities on geo; countries and states are short lists the console filters itself).
func (s *staffMarketsImpl) Places(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	pq, err := passQuery(q, "country", "region", "q", "limit")
	if err != nil {
		return nil, err
	}
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return nil, err
	}
	if !sc.access.Allows(marketsModule, domain.LevelView) && !sc.access.Allows(domain.PreorderModule, domain.LevelView) {
		return nil, staffModuleDenied(marketsModule, domain.LevelView)
	}
	if sc.up.Geo == nil {
		return nil, domain.UpstreamUnavailable("geo")
	}
	country, region := strings.ToUpper(pq.Get("country")), strings.ToUpper(pq.Get("region"))
	var raw jobj
	type place = jobj
	out := []place{}
	switch {
	case country == "":
		if err := sc.get(ctx, sc.up.Geo, "/v1/places/countries", nil, &raw); err != nil {
			return nil, err
		}
		for _, it := range objs(raw, "items") {
			out = append(out, place{"code": jstr(it, "code"), "name": jstr(it, "name"), "label": jstr(it, "regionLabel")})
		}
		return jobj{"items": out, "label": "Country", "total": len(out)}, nil
	case !countryCodeRe.MatchString(country):
		return nil, notFoundPlace()
	case region == "":
		if err := sc.get(ctx, sc.up.Geo, "/v1/places/countries/"+url.PathEscape(country)+"/regions", nil, &raw); err != nil {
			return nil, err
		}
		for _, it := range objs(raw, "items") {
			out = append(out, place{"code": jstr(it, "code"), "name": jstr(it, "name"), "cityCount": int(num(it, "cityCount"))})
		}
		label := jstr(raw, "regionLabel")
		if label == "" {
			label = "State"
		}
		return jobj{"items": out, "label": label, "total": len(out)}, nil
	case !regionCodeRe.MatchString(region):
		return nil, notFoundPlace()
	default:
		cq := url.Values{}
		if v := pq.Get("q"); v != "" {
			cq.Set("q", v)
		}
		limit := 200
		if n, err := strconv.Atoi(pq.Get("limit")); err == nil && n > 0 && n <= 5000 {
			limit = n
		}
		cq.Set("limit", strconv.Itoa(limit))
		if err := sc.get(ctx, sc.up.Geo, "/v1/places/countries/"+url.PathEscape(country)+"/regions/"+url.PathEscape(region)+"/cities", cq, &raw); err != nil {
			return nil, err
		}
		for _, n := range strs(raw, "items") {
			out = append(out, place{"code": n, "name": n})
		}
		return jobj{"items": out, "label": "City", "total": int(num(raw, "total"))}, nil
	}
}
