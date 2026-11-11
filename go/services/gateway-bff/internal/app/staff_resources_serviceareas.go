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

// Service areas (Settings > Markets & locations): the radius policy per service category and the platform's metro zones.
// Bookings owns both (GET/PUT /v1/staff/service-areas/...); every change carries a reason and is audited there.
// docs/engineering/service-areas.md explains how the policy and the zones decide which providers a customer sees.

const (
	areaRulesPath = "/v1/staff/service-areas/policy"
	areaZonesPath = "/v1/staff/service-areas/zones"
	areaLimitsID  = "_limits"
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
	rows = append(rows, jobj{"id": areaLimitsID, "name": "Platform limits", "customerTravelMiles": num(p, "customerTravelMiles"),
		"nearestMaxMiles": num(p, "nearestMaxMiles"), "note": fmt.Sprintf("Customers are assumed to travel up to %.0f miles to a shop; the nearest suggestions go up to %.0f miles.",
			num(p, "customerTravelMiles"), num(p, "nearestMaxMiles"))})
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
		key: "service-area-rules", module: "markets", title: "Service radius rules", exportable: false,
		columns: []domain.Column{col("name", "Service category", "strong", 34), col("defaultMiles", "Default radius (mi)", "number", 16), col("minMiles", "Minimum (mi)", "number", 14),
			col("maxMiles", "Maximum (mi)", "number", 14), col("note", "Notes", "text", 22)},
		idOf: func(o jobj) string { return jstr(o, "id") },
		list: listAll(load), get: rule,
		titleOf:    func(o jobj) string { return jstr(o, "name") },
		subtitleOf: func(o jobj) string { return "Providers choose their radius between the minimum and the maximum" },
		sections: func(o jobj) []domain.DetailSection {
			if jstr(o, "id") == areaLimitsID {
				return []domain.DetailSection{{Title: "Platform limits", Fields: []domain.DetailField{
					fieldOf("Customer travel limit (miles)", jstr(o, "customerTravelMiles"), "text"),
					fieldOf("Nearest suggestions up to (miles)", jstr(o, "nearestMaxMiles"), "text")}}}
			}
			return []domain.DetailSection{{Title: "Radius", Fields: []domain.DetailField{
				fieldOf("Default", jstr(o, "defaultMiles"), "text"), fieldOf("Minimum", jstr(o, "minMiles"), "text"), fieldOf("Maximum", jstr(o, "maxMiles"), "text")}}}
		},
		edit: &staffEdit{permission: "content:markets.edit",
			form: func(o jobj) domain.Form {
				if jstr(o, "id") == areaLimitsID {
					return domain.Form{Title: "Platform limits", SubmitLabel: "Save", Intro: "Services are in person only. A customer who goes to a provider's shop is matched within the smaller of the provider's radius and the travel limit. When nobody serves a place, providers outside their usual coverage are suggested up to the nearest-suggestion distance (0 turns the suggestions off).",
						Fields: []domain.FormField{
							withValue(withRange(ff("customerTravelMiles", "Customer travel limit (miles)", "number", true), 150), num(o, "customerTravelMiles")),
							withValue(withRange(ff("nearestMaxMiles", "Nearest suggestions up to (miles)", "number", true), 300), num(o, "nearestMaxMiles")),
							withMax(ff("reason", "Reason (kept in the audit trail)", "textarea", true), 500)}}
				}
				return domain.Form{Title: "Radius rule for " + jstr(o, "name"), SubmitLabel: "Save", Intro: "What a provider may choose for the radius of the services it lists in this category.",
					Fields: []domain.FormField{
						withValue(withRange(ff("defaultMiles", "Default radius (miles)", "number", true), 150), num(o, "defaultMiles")),
						withValue(withRange(ff("minMiles", "Minimum (miles)", "number", true), 150), num(o, "minMiles")),
						withValue(withRange(ff("maxMiles", "Maximum (miles)", "number", true), 150), num(o, "maxMiles")),
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
				switch id {
				case areaLimitsID:
					p["customerTravelMiles"] = int64(vn(v, "customerTravelMiles"))
					p["nearestMaxMiles"] = int64(vn(v, "nearestMaxMiles"))
				default:
					r := jobj{"defaultMiles": int64(vn(v, "defaultMiles")), "minMiles": int64(vn(v, "minMiles")), "maxMiles": int64(vn(v, "maxMiles"))}
					if id == "_fallback" {
						p["fallback"] = r
					} else {
						byCat, _ := p["byCategory"].(map[string]any)
						byCat[id] = r
						p["byCategory"] = byCat
					}
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
