package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Service areas (Settings > Markets & locations): the default search radius per service category and the platform's metro
// zones. The radius rules are DEFAULTS for what a shopper searches until they choose their own distance, never limits:
// the platform sets no limit on how far a shopper goes. A provider's declared radius is how far they travel to customers.
// Bookings owns both (GET/PUT /v1/staff/service-areas/...); every change carries a reason and is audited there.
// docs/engineering/service-areas.md explains how the policy and the zones decide which providers a customer sees.

const (
	areaRulesPath = "/v1/staff/service-areas/policy"
	areaZonesPath = "/v1/staff/service-areas/zones"
)

func areaPolicy(ctx context.Context, sc staffCtx) (jobj, error) {
	var p jobj
	if err := sc.get(ctx, sc.up.Bookings, areaRulesPath, nil, &p); err != nil {
		return nil, err
	}
	return p, nil
}

func ruleRow(id, name string, r jobj) jobj {
	return jobj{"id": id, "name": name, "defaultMiles": num(r, "defaultMiles"), "minMiles": num(r, "minMiles"), "maxMiles": num(r, "maxMiles")}
}

func areaRuleRows(p jobj) []jobj {
	rows := []jobj{}
	byCat, _ := p["byCategory"].(map[string]any)
	keys := make([]string, 0, len(byCat))
	for k := range byCat {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		r, _ := byCat[k].(map[string]any)
		rows = append(rows, ruleRow(k, strings.ToUpper(k[:1])+k[1:], r))
	}
	fb, _ := p["fallback"].(map[string]any)
	rows = append(rows, ruleRow("_fallback", "Any other category", fb))
	return rows
}

func serviceAreaRulesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		p, err := areaPolicy(ctx, sc)
		if err != nil {
			return nil, err
		}
		return areaRuleRows(p), nil
	}
	rule := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		p, err := areaPolicy(ctx, sc)
		if err != nil {
			return nil, err
		}
		for _, r := range areaRuleRows(p) {
			if jstr(r, "id") == id {
				return r, nil
			}
		}
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such rule.")
	}
	return &staffResource{
		key: "service-area-rules", module: "markets", title: "Default search radius by category", exportable: false,
		columns: []domain.Column{col("name", "Service category", "strong", 34), col("defaultMiles", "Default search radius (mi)", "number", 20), col("minMiles", "Provider minimum (mi)", "number", 18),
			col("maxMiles", "Provider maximum (mi)", "number", 18)},
		idOf: func(o jobj) string { return jstr(o, "id") },
		list: listAll(load), get: rule,
		titleOf: func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string {
			return "Shoppers search this far by default and can choose any distance; providers declare how far they travel to customers between the minimum and the maximum"
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Radius", Fields: []domain.DetailField{
				fieldOf("Default search radius", jstr(o, "defaultMiles"), "text"), fieldOf("Provider minimum", jstr(o, "minMiles"), "text"), fieldOf("Provider maximum", jstr(o, "maxMiles"), "text")}}}
		},
		edit: &staffEdit{permission: "content:markets.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Default search radius for " + jstr(o, "name"), SubmitLabel: "Save", Intro: "The default is how far shoppers search in this category until they choose their own distance: it is not a limit, shoppers may pick any distance. Providers declare how far they travel to customers between the minimum and the maximum.",
					Fields: []domain.FormField{
						withValue(withRange(ff("defaultMiles", "Default search radius (miles)", "number", true), 150), num(o, "defaultMiles")),
						withValue(withRange(ff("minMiles", "Provider minimum radius (miles)", "number", true), 150), num(o, "minMiles")),
						withValue(withRange(ff("maxMiles", "Provider maximum radius (miles)", "number", true), 150), num(o, "maxMiles")),
						withMax(ff("reason", "Reason (kept in the audit trail)", "textarea", true), 500)}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				reason := vs(v, "reason")
				if len(reason) < 5 {
					return domain.ValidationProblem(map[string]string{"reason": "Give a reason of at least 5 characters"})
				}
				p, err := areaPolicy(ctx, sc)
				if err != nil {
					return err
				}
				id := jstr(o, "id")
				r := jobj{"defaultMiles": int64(vn(v, "defaultMiles")), "minMiles": int64(vn(v, "minMiles")), "maxMiles": int64(vn(v, "maxMiles"))}
				if id == "_fallback" {
					p["fallback"] = r
				} else {
					byCat, _ := p["byCategory"].(map[string]any)
					byCat[id] = r
					p["byCategory"] = byCat
				}
				return sc.call(ctx, sc.up.Bookings, port.StaffCall{Method: http.MethodPut, Path: areaRulesPath, Body: jobj{"policy": p, "reason": reason}}, nil)
			}},
	}
}

// zoneRow adds the table fields of a zone.
func zoneRow(o jobj) jobj {
	cities := objs(o, "cities")
	names := make([]string, 0, len(cities))
	for _, c := range cities {
		names = append(names, jstr(c, "city")+", "+jstr(c, "state"))
	}
	o["id"] = jstr(o, "key")
	o["cityCount"] = float64(len(cities))
	o["cityList"] = strings.Join(names, "\n")
	preview := names
	if len(preview) > 6 {
		preview = preview[:6]
	}
	o["citiesPreview"] = strings.Join(preview, ", ")
	return o
}

// parseZoneCities reads "Round Rock, TX" lines (or comma/semicolon separated pairs) into the zone's cities.
func parseZoneCities(text string) ([]jobj, error) {
	out := []jobj{}
	for _, line := range strings.FieldsFunc(text, func(r rune) bool { return r == '\n' || r == ';' }) {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		i := strings.LastIndex(line, ",")
		city, state := "", ""
		if i > 0 {
			city, state = strings.TrimSpace(line[:i]), strings.ToUpper(strings.TrimSpace(line[i+1:]))
		}
		if city == "" || len(state) != 2 {
			return nil, domain.ValidationProblem(map[string]string{"cities": fmt.Sprintf("Write each city as “City, ST” (one per line); check “%s”", line)})
		}
		out = append(out, jobj{"city": city, "state": state})
	}
	if len(out) == 0 {
		return nil, domain.ValidationProblem(map[string]string{"cities": "Add at least one city"})
	}
	return out, nil
}

func serviceZonesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw []jobj
		if err := sc.get(ctx, sc.up.Bookings, areaZonesPath, nil, &raw); err != nil {
			return nil, err
		}
		rows := make([]jobj, 0, len(raw))
		for _, z := range raw {
			row := zoneRow(z)
			if q.Q == "" || strings.Contains(strings.ToLower(jstr(row, "name")+" "+jstr(row, "cityList")), strings.ToLower(q.Q)) {
				rows = append(rows, row)
			}
		}
		return rows, nil
	}
	get := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		rows, err := load(ctx, sc, staffQuery{})
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			if jstr(r, "id") == id {
				return r, nil
			}
		}
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such zone.")
	}
	reasonField := func() domain.FormField {
		return withMax(ff("reason", "Reason (kept in the audit trail)", "textarea", true), 500)
	}
	put := func(ctx context.Context, sc staffCtx, key, name, cities, reason string) error {
		if len(strings.TrimSpace(reason)) < 5 {
			return domain.ValidationProblem(map[string]string{"reason": "Give a reason of at least 5 characters"})
		}
		list, err := parseZoneCities(cities)
		if err != nil {
			return err
		}
		return sc.call(ctx, sc.up.Bookings, port.StaffCall{Method: http.MethodPut, Path: areaZonesPath + "/" + url.PathEscape(key),
			Body: jobj{"zone": jobj{"key": key, "name": name, "cities": list}, "reason": strings.TrimSpace(reason)}}, nil)
	}
	return &staffResource{
		key: "service-zones", module: "markets", title: "Metro zones", searchPlaceholder: "Search zones or cities",
		columns: []domain.Column{col("name", "Zone", "strong", 22), col("key", "Key", "mono", 18), col("cityCount", "Cities", "number", 10), col("citiesPreview", "Includes", "text", 50)},
		idOf:    func(o jobj) string { return jstr(o, "id") },
		list:    listAll(load), get: get,
		titleOf:    func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string { return jstr(o, "cityCount") + " cities" },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Zone", Fields: []domain.DetailField{fieldOf("Name", jstr(o, "name"), "text"), fieldOf("Key", jstr(o, "key"), "mono"), fieldOf("Cities", jstr(o, "cityList"), "text")}}}
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Radius rules", Href: "/settings?section=markets&tab=service-area-rules"}}
		},
		edit: &staffEdit{permission: "content:markets.edit",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit " + jstr(o, "name"), SubmitLabel: "Save", Intro: "Providers see their zone (“Serves Greater Austin”) and can add a whole zone as an extra area. A city belongs to a zone by its name and state.",
					Fields: []domain.FormField{withValue(withMax(ff("name", "Zone name", "text", true), 80), jstr(o, "name")),
						withValue(withMax(ff("cities", "Cities (one per line, “City, ST”)", "textarea", true), 6000), jstr(o, "cityList")), reasonField()}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return put(ctx, sc, jstr(o, "key"), vs(v, "name"), vs(v, "cities"), vs(v, "reason"))
			}},
		create: &staffCreate{permission: "content:markets.edit",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New metro zone", SubmitLabel: "Create zone", Fields: []domain.FormField{
					withMax(ff("key", "Key (lowercase letters, digits and dashes, e.g. greater-austin)", "text", true), 40), withMax(ff("name", "Zone name", "text", true), 80),
					withMax(ff("cities", "Cities (one per line, “City, ST”)", "textarea", true), 6000), reasonField()}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				key := strings.ToLower(vs(v, "key"))
				return key, put(ctx, sc, key, vs(v, "name"), vs(v, "cities"), vs(v, "reason"))
			}},
		actions: []staffAction{
			{key: "delete", label: "Delete zone", tone: "danger", permission: "content:markets.edit", removes: true,
				confirm: "Delete this zone? Providers that added it as an extra area keep their other areas.",
				form:    reasonForm("Delete zone", "Delete"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					reason := vs(v, "reason")
					if len(reason) < 5 {
						return domain.ValidationProblem(map[string]string{"reason": "Give a reason of at least 5 characters"})
					}
					return sc.call(ctx, sc.up.Bookings, port.StaffCall{Method: http.MethodDelete, Path: areaZonesPath + "/" + url.PathEscape(jstr(o, "key")), Query: url.Values{"reason": {reason}}}, nil)
				}},
		},
	}
}
