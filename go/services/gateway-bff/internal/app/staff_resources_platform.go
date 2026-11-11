package app

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The platform's own settings in the console's Settings: what governs holds and payouts (read-only, set by the
// deployment). Who calculates sales tax lives with the other tax tools (staff_tax*.go).

func platformSettingsResources() []*staffResource {
	return []*staffResource{paymentSettingsResource(), shippingSettingsResource()}
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
