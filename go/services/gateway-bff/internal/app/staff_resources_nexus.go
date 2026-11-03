package app

import (
	"context"
	"math"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Nexus tracker (Fees & commissions → Sales tax): per state, the sales shipped there over the last twelve months against its
// thresholds, and whether AfroTransact is registered. It tracks and warns; registering is done with the state.

var nexusStatusLabels = map[string]string{
	"EXCEEDED":    "Past the threshold, not registered",
	"APPROACHING": "Close to the threshold",
	"REGISTERED":  "Registered",
	"BELOW":       "Below the threshold",
}

func nexusRow(o jobj) jobj {
	o["id"] = jstr(o, "jurisdiction")
	o["statusLabel"] = nexusStatusLabels[jstr(o, "status")]
	o["percentLabel"] = jstr(o, "percent") + "%"
	o["facilitatorLabel"] = "No"
	if boolean(o, "facilitator") {
		o["facilitatorLabel"] = "Yes"
	}
	return o
}

func taxNexusResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw jobj
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/nexus", nil, &raw); err != nil {
			return nil, err
		}
		rows := []jobj{}
		for _, o := range objs(raw, "states") {
			rows = append(rows, nexusRow(o))
		}
		return filterState(rows, q.Status, "status"), nil
	}
	list := listAll(load)
	get := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/nexus/"+url.PathEscape(id), nil, &o); err != nil {
			return nil, err
		}
		o = nexusRow(o)
		o["updatedByName"] = newWhoIs(sc).name(ctx, jstr(o, "updatedBy"))
		return o, nil
	}
	return &staffResource{
		key: "tax-nexus", module: "fees", title: "Nexus tracker", statusKey: "status", exportable: true,
		columns: []domain.Column{col("jurisdiction", "State", "mono", 9), col("name", "Name", "strong", 16), col("salesMinor", "Sales, last 12 months", "money", 16),
			col("salesThresholdMinor", "Sales threshold", "money", 14), col("orders", "Orders", "text", 9), col("percentLabel", "Of threshold", "text", 11),
			col("registrationNumber", "Registration", "mono", 16), col("status", "Status", "status", 16)},
		facets: domain.StaffFacets("EXCEEDED", "APPROACHING", "REGISTERED", "BELOW"),
		idOf:   func(o jobj) string { return jstr(o, "id") },
		list:   list, get: get,
		titleOf:    func(o jobj) string { return jstr(o, "name") + " (" + jstr(o, "jurisdiction") + ")" },
		subtitleOf: func(o jobj) string { return jstr(o, "statusLabel") },
		sections: func(o jobj) []domain.DetailSection {
			txns := "No transaction test"
			if n := num(o, "transactionsThreshold"); n > 0 {
				txns = jstr(o, "transactionsThreshold") + " orders"
			}
			return []domain.DetailSection{
				{Title: "Where sales stand", Fields: []domain.DetailField{
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("What it means", nexusMeaning(o), "text"),
					fieldOf("Sales shipped here, last 12 months", jstr(o, "salesMinor"), "money"),
					fieldOf("Orders shipped here", jstr(o, "orders"), "text"),
					fieldOf("Sales threshold", jstr(o, "salesThresholdMinor"), "money"),
					fieldOf("Order-count threshold", txns, "text"),
					fieldOf("Of the nearest threshold", jstr(o, "percentLabel"), "text"),
					fieldOf("Marketplace facilitator state", jstr(o, "facilitatorLabel"), "text"),
				}},
				{Title: "Registration", Fields: []domain.DetailField{
					fieldOf("Registered", map[bool]string{true: "Yes", false: "No"}[boolean(o, "registered")], "text"),
					fieldOf("Registration number", jstr(o, "registrationNumber"), "mono"),
					fieldOf("Effective", jstr(o, "registeredOn"), "date"),
					fieldOf("Note", jstr(o, "note"), "text"),
					fieldOf("Last changed by", firstNonEmpty(jstr(o, "updatedByName"), jstr(o, "updatedBy")), "text"),
					fieldOf("Last changed", jstr(o, "updatedAt"), "datetime"),
				}},
			}
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Tax filings", Href: "/settings?section=tax&tab=tax-filings"}}
		},
		edit: &staffEdit{permission: "tax:configure",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Thresholds and registration for " + jstr(o, "name"), SubmitLabel: "Save",
					Intro: "The defaults are a common state figure, not legal advice: confirm each state's own sales and order-count thresholds, and when the clock starts, with your tax advisor. Record the registration here once the state has issued it.",
					Fields: []domain.FormField{
						withValue(withRange(ff("salesThreshold", "Sales threshold (dollars)", "number", true), 1_000_000_000), math.Round(num(o, "salesThresholdMinor"))/100),
						withValue(withRange(ff("transactionsThreshold", "Order-count threshold (leave empty if the state has none)", "number", false), 1_000_000), numOrEmpty(o, "transactionsThreshold")),
						withValue(ff("registered", "Registered in this state", "toggle", false), boolean(o, "registered")),
						withValue(withMax(ff("registrationNumber", "Registration or permit number", "text", false), 80), jstr(o, "registrationNumber")),
						withValue(ff("registeredOn", "Registration effective on", "date", false), jstr(o, "registeredOn")),
						withValue(withMax(ff("note", "Note", "textarea", false), 1000), jstr(o, "note")),
					}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				body := jobj{"salesThresholdMinor": int64(math.Round(vn(v, "salesThreshold") * 100)), "registered": vb(v, "registered"),
					"registrationNumber": vs(v, "registrationNumber"), "note": vs(v, "note")}
				if strings.TrimSpace(vs(v, "transactionsThreshold")) != "" {
					body["transactionsThreshold"] = int64(vn(v, "transactionsThreshold"))
				}
				if on := vs(v, "registeredOn"); on != "" {
					body["registeredOn"] = on
				}
				return sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: "PUT", Path: "/v1/tax/nexus/" + url.PathEscape(jstr(o, "jurisdiction")), Body: body}, nil)
			}},
	}
}

func numOrEmpty(o jobj, k string) any {
	if n := num(o, k); n > 0 {
		return n
	}
	return ""
}

func nexusMeaning(o jobj) string {
	switch jstr(o, "status") {
	case "EXCEEDED":
		return "Sales here are at or past a threshold and nothing is registered. Check with your tax advisor whether registration is due, then record it here."
	case "APPROACHING":
		return "Sales here are at " + jstr(o, "percentLabel") + " of a threshold. Plan the registration before it is crossed."
	case "REGISTERED":
		return "AfroTransact is registered here (effective " + jstr(o, "registeredOn") + ")."
	default:
		return "Below the thresholds used for this state."
	}
}
