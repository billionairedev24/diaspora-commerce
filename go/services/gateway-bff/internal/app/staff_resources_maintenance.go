package app

import (
	"context"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

var maintenanceScopes = []domain.FieldOption{
	{Value: domain.MaintenanceShoppers, Label: "Shoppers only (sellers can keep working)"},
	{Value: domain.MaintenanceAll, Label: "Shoppers and sellers"},
}

var maintenanceBackIn = []domain.FieldOption{
	{Value: "0", Label: "Not sure yet"},
	{Value: "15", Label: "About 15 minutes"},
	{Value: "30", Label: "About 30 minutes"},
	{Value: "60", Label: "About 1 hour"},
	{Value: "120", Label: "About 2 hours"},
	{Value: "240", Label: "About 4 hours"},
}

func maintenanceScopeLabel(s string) string {
	if s == domain.MaintenanceAll {
		return "Shoppers and sellers"
	}
	return "Shoppers only"
}

// maintenanceResource is the maintenance switch in Settings → General: turn it on with what customers are told and
// when we expect to be back, turn it off, and see who switched it and when.
func maintenanceResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		if sc.maint == nil {
			return nil, domain.UpstreamUnavailable("maintenance")
		}
		m := sc.maint.Current(ctx)
		w := newWhoIs(sc)
		o := jobj{"id": "mode", "setting": "Maintenance mode", "state": "OFF", "stateLabel": "Off: the platform is open",
			"scope": m.Scope, "scopeLabel": maintenanceScopeLabel(m.Scope), "title": m.Title, "message": m.Message,
			"updatedAt": m.UpdatedAt.Format(time.RFC3339), "updatedByName": w.name(ctx, m.UpdatedBy)}
		if m.UpdatedAt.IsZero() {
			o["updatedAt"] = ""
		}
		if m.Enabled {
			o["state"], o["stateLabel"] = "ON", "On: "+maintenanceScopeLabel(m.Scope)+" see the maintenance page"
		}
		if m.ExpectedBackAt != nil {
			o["expectedBackAt"] = m.ExpectedBackAt.Format(time.RFC3339)
		}
		hist := make([]jobj, 0, len(m.History))
		for _, h := range m.History {
			row := jobj{"id": h.At.Format(time.RFC3339Nano), "at": h.At.Format(time.RFC3339), "byName": w.name(ctx, h.By), "message": h.Message,
				"change": "Turned off", "scopeLabel": ""}
			if h.Enabled {
				row["change"], row["scopeLabel"] = "Turned on", maintenanceScopeLabel(h.Scope)
			}
			hist = append(hist, row)
		}
		o["history"] = hist
		return o, nil
	}
	return &staffResource{
		key: "maintenance", module: "siteCfg", title: "Maintenance mode", statusKey: "state",
		columns: []domain.Column{col("setting", "Setting", "strong", 24), col("stateLabel", "Now", "status", 40), col("updatedByName", "Changed by", "text", 16), col("updatedAt", "Changed", "datetime", 12)},
		facets:  domain.StaffFacets("ON", "OFF"),
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "mode")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        get,
		idOf:       func(o jobj) string { return jstr(o, "id") },
		titleOf:    func(jobj) string { return "Maintenance mode" },
		subtitleOf: func(o jobj) string { return jstr(o, "stateLabel") },
		sections: func(o jobj) []domain.DetailSection {
			if jstr(o, "state") != "ON" {
				return []domain.DetailSection{{Title: "Now", Fields: []domain.DetailField{
					fieldOf("State", jstr(o, "stateLabel"), "text"),
					fieldOf("What it does", "Nothing yet. Turn it on before planned work (a release, a database change): shoppers see a page that explains what is happening and when we expect to be back, instead of errors. You and other staff can still sign in and work.", "text"),
				}}}
			}
			return []domain.DetailSection{{Title: "What customers see", Fields: []domain.DetailField{
				fieldOf("State", jstr(o, "stateLabel"), "text"),
				fieldOf("Heading", jstr(o, "title"), "text"),
				fieldOf("Message", jstr(o, "message"), "text"),
				fieldOf("Expected back", jstr(o, "expectedBackAt"), "datetime"),
				fieldOf("Turned on by", jstr(o, "updatedByName"), "text"),
				fieldOf("Turned on", jstr(o, "updatedAt"), "datetime"),
				fieldOf("Staff", "Not affected: staff can sign in and use the console.", "text"),
			}}}
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("History", objs(o, "history"), []domain.Column{col("at", "When", "datetime", 18), col("change", "Change", "text", 14),
				col("scopeLabel", "Paused", "text", 22), col("byName", "By", "text", 18), col("message", "Message", "text", 40)}, "id")}
		},
		edit: &staffEdit{permission: "content:site.edit",
			form: func(o jobj) domain.Form {
				scope := jstr(o, "scope")
				if scope == "" {
					scope = domain.MaintenanceShoppers
				}
				msg := jstr(o, "message")
				if msg == "" {
					msg = "We’re doing some planned work to make AfroTransact better. Your cart and orders are safe. Please check back soon."
				}
				return domain.Form{Title: "Maintenance mode", SubmitLabel: "Save",
					Intro: "While it is on, shoppers (and sellers, if you choose) see the maintenance page instead of the site, and calls to the API are refused with a 503. Staff can still sign in. Payments already taken, and webhooks from Stripe and carriers, are not affected. It changes within a few seconds and is recorded with your name.",
					Fields: []domain.FormField{
						withValue(ff("enabled", "Maintenance mode on", "toggle", false), jstr(o, "state") == "ON"),
						withValue(withOptions(ff("scope", "Who is paused", "select", true), maintenanceScopes), scope),
						withValue(withMax(ff("title", "Heading", "text", false), 80), jstr(o, "title")),
						withValue(withMax(ff("message", "What customers are told (required when turning on)", "textarea", false), 600), msg),
						withValue(withOptions(ff("backIn", "When we expect to be back", "select", false), maintenanceBackIn), "0"),
					}, ChangeKeys: []string{"enabled", "scope", "title", "message", "backIn"}}
			},
			save: func(ctx context.Context, sc staffCtx, _ jobj, v jobj, _ string) error {
				back := time.Duration(0)
				for _, o := range maintenanceBackIn {
					if o.Value == vs(v, "backIn") && o.Value != "0" {
						d, _ := time.ParseDuration(o.Value + "m")
						back = d
					}
				}
				_, err := sc.maint.Set(ctx, sc.me.ID, MaintenanceChange{Enabled: vb(v, "enabled"), Scope: vs(v, "scope"), Title: vs(v, "title"),
					Message: vs(v, "message"), BackIn: back})
				return err
			}},
	}
}
