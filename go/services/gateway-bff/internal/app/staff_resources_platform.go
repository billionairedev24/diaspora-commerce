package app

import (
	"context"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The platform's own settings in the console's Settings: what governs holds and payouts (read-only, set by the
// deployment) and who calculates sales tax (a switch staff can flip, with a reason).

func platformSettingsResources() []*staffResource {
	return []*staffResource{paymentSettingsResource(), taxSettingsResource()}
}

// ---------------------------------------------------------------- payments (read-only)

func paymentSettingsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/settings/summary", nil, &raw); err != nil {
			return nil, err
		}
		rows := jpage(raw).items
		for _, r := range rows {
			r["id"] = jstr(r, "key")
		}
		return rows, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "payment-settings", module: "payouts", title: "Payments & payouts",
		columns: []domain.Column{col("label", "Setting", "strong", 30), col("value", "In force", "text", 40), col("source", "Set in", "mono", 22)},
		list:    list, get: findIn(list, "id"),
		idOf:       func(o jobj) string { return jstr(o, "id") },
		titleOf:    func(o jobj) string { return jstr(o, "label") },
		subtitleOf: func(o jobj) string { return jstr(o, "value") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "This setting", Fields: []domain.DetailField{
				fieldOf("Setting", jstr(o, "label"), "text"),
				fieldOf("In force", jstr(o, "value"), "text"),
				fieldOf("What it does", jstr(o, "help"), "text"),
				fieldOf("Set in", jstr(o, "source"), "mono"),
				fieldOf("How to change it", "These values come from the deployment's configuration. Change the setting named above and restart payments; the console cannot edit them yet.", "text"),
			}}}
		},
	}
}

// ---------------------------------------------------------------- sales tax method

var taxMethodOptions = []domain.FieldOption{
	{Value: "RULES", Label: "Our own rules (rate tables and tax codes)"},
	{Value: "STRIPE_TAX", Label: "Stripe Tax (needs to be set up)"},
}

func taxMethodLabel(m string) string {
	if m == "STRIPE_TAX" {
		return "Stripe Tax"
	}
	return "Our own rules"
}

func taxSettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/settings", nil, &o); err != nil {
			return nil, err
		}
		o["id"] = "method"
		o["setting"] = "Who calculates sales tax"
		o["methodLabel"] = taxMethodLabel(jstr(o, "method"))
		o["stripeLabel"] = "Not set up yet"
		if boolean(o, "stripeAvailable") {
			o["stripeLabel"] = "Set up"
		}
		w := newWhoIs(sc)
		w.set(ctx, o, "changedBy", "changedByName")
		for _, h := range objs(o, "history") {
			w.set(ctx, h, "changedBy", "changedByName")
			h["fromLabel"] = taxMethodLabel(jstr(h, "from"))
			h["toLabel"] = taxMethodLabel(jstr(h, "to"))
		}
		return o, nil
	}
	return &staffResource{
		key: "tax-settings", module: "fees", title: "Sales tax method",
		columns: []domain.Column{col("setting", "Setting", "strong", 30), col("methodLabel", "In force", "text", 24), col("stripeLabel", "Stripe Tax", "text", 18),
			col("changedByName", "Changed by", "text", 16), col("changedAt", "Changed", "datetime", 12)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "method")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        get,
		idOf:       func(o jobj) string { return jstr(o, "id") },
		titleOf:    func(o jobj) string { return "Who calculates sales tax" },
		subtitleOf: func(o jobj) string { return taxMethodLabel(jstr(o, "method")) + " is in force" },
		sections: func(o jobj) []domain.DetailSection {
			what := "AfroTransact works out the tax itself: the state and local rates in Tax rates, and what each item is for tax in Tax classification (including items that are never taxed). No tax service is called and nothing costs per order."
			if jstr(o, "method") == "STRIPE_TAX" {
				what = "Stripe Tax works out the tax for items that have a Stripe tax code. Our own rules answer for anything Stripe cannot (an item without a Stripe code, or Stripe unreachable)."
			}
			return []domain.DetailSection{{Title: "In force", Fields: []domain.DetailField{
				fieldOf("Method", jstr(o, "methodLabel"), "text"),
				fieldOf("What it means", what, "text"),
				fieldOf("Stripe Tax", jstr(o, "stripeLabel"), "text"),
				fieldOf("Default from deployment", taxMethodLabel(jstr(o, "configuredDefault")), "text"),
				fieldOf("Last changed by", jstr(o, "changedByName"), "text"),
				fieldOf("Last changed", jstr(o, "changedAt"), "datetime"),
				fieldOf("Applies to", "New quotes within seconds. Orders already placed keep the tax they were charged.", "text"),
			}}}
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("History", objs(o, "history"), []domain.Column{col("changedAt", "When", "datetime", 18), col("fromLabel", "From", "text", 16), col("toLabel", "To", "text", 16),
				col("changedByName", "By", "text", 18), col("reason", "Why", "text", 32)}, "changedAt")}
		},
		links: func(jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Tax rates and classification", Href: "/settings?section=tax"}}
		},
		edit: &staffEdit{permission: "tax:configure",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Change who calculates sales tax", SubmitLabel: "Change method",
					Intro: "Our own rules are always available. Stripe Tax can only be chosen once it is set up (its key is set and registrations are done in the Stripe dashboard). The change applies to new quotes; orders already placed keep their tax. It is recorded with your reason.",
					Fields: []domain.FormField{
						withValue(withOptions(ff("method", "Method", "select", true), taxMethodOptions), jstr(o, "method")),
						withMax(ff("reason", "Why (at least 10 characters)", "textarea", true), 500),
					}, ChangeKeys: []string{"method"}}
			},
			save: func(ctx context.Context, sc staffCtx, _ jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/settings",
					Body: jobj{"method": vs(v, "method"), "reason": vs(v, "reason")}}, nil)
			}},
	}
}
