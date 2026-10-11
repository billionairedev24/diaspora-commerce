package app

import (
	"context"
	"net/http"
	"net/url"
	"slices"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Markets & locations: switch a country's states / provinces on (Marketplace) from the country itself, and change one
// state's capability switches (design: "Add a state" and the per-state switch table). Both go to content's draft
// through PATCH /v1/markets/draft/countries/{code}/regions/{region}; Publish makes them live.

// marketCapabilities are content's per-state switches, in the design's column order.
var marketCapabilities = []struct{ key, label string }{
	{"marketplace", "Marketplace"}, {"services", "Services"}, {"food", "Food & perishables"}, {"pickup", "Pickup points"},
	{"taxRegistration", "Tax registered"},
}

func marketRegions(o jobj) []jobj { return objs(o, "regions") }

func regionCapability(r jobj, key string) bool {
	caps, _ := r["capabilities"].(map[string]any)
	on, _ := caps[key].(bool)
	return on
}

func regionOptions(o jobj) []domain.FieldOption {
	out := []domain.FieldOption{}
	for _, r := range marketRegions(o) {
		out = append(out, domain.FieldOption{Value: jstr(r, "code"), Label: jstr(r, "name")})
	}
	return out
}

func patchMarketRegion(ctx context.Context, sc staffCtx, country, region string, body jobj) error {
	return sc.call(ctx, sc.up.Content, port.StaffCall{Method: http.MethodPatch,
		Path: "/v1/markets/draft/countries/" + url.PathEscape(country) + "/regions/" + url.PathEscape(region), Body: body}, nil)
}

func marketStateActions() []staffAction {
	return []staffAction{
		{key: "states", label: "Choose live states", tone: "primary", permission: "content:markets.edit",
			form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
				live := []string{}
				for _, r := range marketRegions(o) {
					if regionCapability(r, "marketplace") {
						live = append(live, jstr(r, "code"))
					}
				}
				f := withOptions(ff("live", "States / provinces with the marketplace on", "multiselect", false), regionOptions(o))
				f.Value = live
				f.Help = "Add or remove several at once. A state switched off loses every capability; one switched on starts with the marketplace only (draft until you publish)."
				return &domain.Form{Title: "Live states and provinces (draft)", SubmitLabel: "Save draft", Fields: []domain.FormField{f}}
			},
			run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
				want := vlist(v, "live")
				known := map[string]bool{}
				for _, r := range marketRegions(o) {
					known[jstr(r, "code")] = true
				}
				for _, code := range want {
					if !known[code] {
						return domain.ValidationProblem(map[string]string{"live": "Unknown state or province " + code})
					}
				}
				for _, r := range marketRegions(o) {
					code := jstr(r, "code")
					on := slices.Contains(want, code)
					if on == regionCapability(r, "marketplace") {
						continue
					}
					if err := patchMarketRegion(ctx, sc, jstr(o, "code"), code, jobj{"marketplace": on}); err != nil {
						return err
					}
				}
				return nil
			}},
		{key: "state-switches", label: "Edit a state's switches", tone: "neutral", permission: "content:markets.edit",
			form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
				fields := []domain.FormField{withOptions(ff("region", "State / province", "select", true), regionOptions(o))}
				for _, c := range marketCapabilities {
					f := withOptions(ff(c.key, c.label, "select", false), []domain.FieldOption{{Value: "", Label: "Keep as is"},
						{Value: "on", Label: "On"}, {Value: "off", Label: "Off"}})
					fields = append(fields, f)
				}
				return &domain.Form{Title: "State or province switches (draft)", SubmitLabel: "Save draft", Fields: fields}
			},
			run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
				region := vs(v, "region")
				if !slices.ContainsFunc(marketRegions(o), func(r jobj) bool { return jstr(r, "code") == region }) {
					return domain.ValidationProblem(map[string]string{"region": "Choose a state or province of " + jstr(o, "name")})
				}
				body := jobj{}
				for _, c := range marketCapabilities {
					switch vs(v, c.key) {
					case "on":
						body[c.key] = true
					case "off":
						body[c.key] = false
					case "":
					default:
						return domain.ValidationProblem(map[string]string{c.key: "On, off or keep as is"})
					}
				}
				if len(body) == 0 {
					return domain.ValidationProblem(map[string]string{"marketplace": "Change at least one switch"})
				}
				return patchMarketRegion(ctx, sc, jstr(o, "code"), region, body)
			}},
	}
}
