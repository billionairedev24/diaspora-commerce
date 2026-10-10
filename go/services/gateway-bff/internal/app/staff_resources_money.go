package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- A-to-z claims (trust-ops)

func claimsResource() *staffResource {
	open := []string{"AWAITING_SELLER", "UNDER_REVIEW", "ESCALATED"}
	decide := func(outcome string) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.TrustOps, "/v1/claims/"+url.PathEscape(jstr(o, "id"))+"/decision", omitEmpty(jobj{"outcome": outcome, "note": vs(v, "note")}), nil)
		}
	}
	withinLimit := func(sc staffCtx, o jobj) string {
		if r := stateReason(jstr(o, "status"), open...); r != "" {
			return r
		}
		if limit := domain.RefundLimit(sc.access.Roles); limit != nil && int64(num(o, "amountMinor")) > *limit {
			return fmt.Sprintf("Above your refund limit ($%.2f).", float64(*limit)/100)
		}
		return ""
	}
	return &staffResource{
		key: "claims", module: "disputes", title: "A-to-z claims", statusKey: "status", exportable: true,
		columns: []domain.Column{col("reference", "Claim", "mono", 13), col("type", "Type", "text", 18), col("orderNumber", "Order", "mono", 13), col("sellerOrgId", "Seller", "text", 16),
			col("amountMinor", "Amount", "money", 11), col("sla.dueAt", "Due", "datetime", 14), col("status", "Status", "status", 15)},
		facets:     domain.StaffFacets("AWAITING_SELLER", "UNDER_REVIEW", "ESCALATED", "RESOLVED_REFUND", "RESOLVED_DENIED", "WITHDRAWN"),
		list:       listPaged("/v1/claims", upTrustOps, nil),
		get:        getByPath("/v1/claims/", upTrustOps),
		titleOf:    func(o jobj) string { return "Claim " + jstr(o, "reference") },
		subtitleOf: func(o jobj) string { return domain.Humanize(jstr(o, "type")) + " · order " + jstr(o, "orderNumber") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "status", "evidence") },
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Evidence", objs(o, "evidence"), []domain.Column{col("kind", "Kind", "text", 30), col("assetId", "Asset", "mono", 40), col("addedAt", "Added", "datetime", 30)}, "assetId")}
		},
		activity: func(ctx context.Context, sc staffCtx, o jobj) []domain.ActivityEntry {
			var hist []jobj
			if sc.get(ctx, sc.up.TrustOps, "/v1/claims/"+url.PathEscape(jstr(o, "id"))+"/history", nil, &hist) != nil {
				return nil
			}
			out := []domain.ActivityEntry{}
			for _, h := range hist {
				text := domain.Humanize(jstr(h, "action"))
				if to := jstr(h, "toStatus"); to != "" {
					text += " → " + strings.ToLower(domain.Humanize(to))
				}
				if n := jstr(h, "note"); n != "" {
					text += " — " + n
				}
				out = append(out, domain.ActivityEntry{At: timeOf(h, "at"), Actor: jstr(h, "actorId"), Text: text})
			}
			return out
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Order " + jstr(o, "orderNumber"), Href: "/orders?open=" + url.QueryEscape(jstr(o, "orderNumber"))},
				{Label: "Customer 360", Href: "/support?customer=" + url.QueryEscape(jstr(o, "customerId"))}}
		},
		version: func(o jobj) string { return jstr(o, "version") },
		actions: []staffAction{
			{key: "refund", label: "Refund", tone: "primary", permission: "disputes:manage", form: noteForm("Decide with a refund (payments)", "Refund", true), when: withinLimit, run: decide("REFUND")},
			{key: "deny", label: "Deny", tone: "danger", permission: "disputes:manage", form: noteForm("Deny the claim", "Deny", true), when: inState(open...), run: decide("DENY")},
			{key: "escalate", label: "Escalate", tone: "neutral", permission: "disputes:manage", form: reasonForm("Escalate the claim", "Escalate"), when: inState("AWAITING_SELLER", "UNDER_REVIEW"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TrustOps, "/v1/claims/"+url.PathEscape(jstr(o, "id"))+"/escalate", jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
	}
}

// ---------------------------------------------------------------- returns (tax-returns)

func returnsResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.TaxReturns, "/v1/returns/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	note := func(v jobj) jobj { return omitEmpty(jobj{"note": vs(v, "note")}) }
	return &staffResource{
		key: "returns", module: "disputes", title: "Returns", statusKey: "status", exportable: true,
		columns: []domain.Column{col("number", "RMA", "mono", 18), col("orderNumber", "Order", "mono", 14), col("method", "Method", "text", 14), col("refundState", "Refund", "text", 14),
			col("estimateMinor", "Refund est.", "money", 14), col("status", "Status", "status", 14), col("createdAt", "Opened", "date", 12)},
		facets:  domain.StaffFacets("REQUESTED", "APPROVED", "IN_TRANSIT", "RECEIVED", "INSPECTED", "REFUNDED", "REJECTED", "CANCELLED"),
		list:    listLimited("/v1/returns", upTaxReturns, nil),
		get:     getByPath("/v1/returns/", upTaxReturns),
		titleOf: func(o jobj) string { return "Return " + jstr(o, "number") },
		subtitleOf: func(o jobj) string {
			return "Order " + jstr(o, "orderNumber") + " · " + domain.Humanize(jstr(o, "method"))
		},
		sections: func(o jobj) []domain.DetailSection {
			return autoSections(o, "status", "lines", "qrPayload", "handoverCode")
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Lines", objs(o, "lines"), []domain.Column{col("title", "Item", "strong", 30), col("quantity", "Qty", "number", 8),
				col("reason", "Reason", "text", 20), col("unitPriceMinor", "Unit price", "money", 14), col("outcome", "Outcome", "text", 14), col("condition", "Condition", "text", 14)}, "lineId")}
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Order " + jstr(o, "orderNumber"), Href: "/orders?open=" + url.QueryEscape(jstr(o, "orderNumber"))},
				{Label: "Customer 360", Href: "/support?customer=" + url.QueryEscape(jstr(o, "customerId"))}}
		},
		actions: []staffAction{
			{key: "approve", label: "Approve", tone: "primary", permission: "returns:manage", form: noteForm("Approve the return", "Approve", false), when: inState("REQUESTED", "PENDING_APPROVAL"), run: act("/approve", note)},
			{key: "reject", label: "Reject", tone: "danger", permission: "returns:manage", form: noteForm("Reject the return (the customer sees the note)", "Reject", true), when: inState("REQUESTED", "PENDING_APPROVAL"), run: act("/reject", note)},
			{key: "receive", label: "Mark received", tone: "neutral", permission: "returns:manage", confirm: "Mark the parcel as received?", when: inState("APPROVED", "IN_TRANSIT", "DROPPED_OFF", "PICKED_UP"), run: act("/receive", func(jobj) jobj { return nil })},
		},
	}
}

// ---------------------------------------------------------------- payout requests (payments)

func payoutsResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.Payments, "/v1/payouts/requests/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	return &staffResource{
		key: "payouts", module: "payouts", title: "Payout requests", statusKey: "status", exportable: true,
		columns: []domain.Column{col("id", "Request", "mono", 14), col("orgId", "Organisation", "strong", 22), col("amountMinor", "Amount", "money", 14),
			col("requestedAt", "Requested", "date", 14), col("holdReason", "Hold reason", "text", 22), col("status", "Status", "status", 14)},
		facets:     domain.StaffFacets("REQUESTED", "ON_HOLD", "APPROVED", "IN_TRANSIT", "PAID", "FAILED"),
		list:       listLimited("/v1/payouts/requests", upPayments, nil),
		get:        getByPath("/v1/payouts/requests/", upPayments),
		titleOf:    func(o jobj) string { return "Payout to " + jstr(o, "orgId") },
		subtitleOf: func(o jobj) string { return jstr(o, "id") },
		actions: []staffAction{
			{key: "approve", label: "Approve & pay", tone: "primary", permission: "payouts:approve", level: domain.LevelApprove, confirm: "Create the Stripe payout now?", when: inState("REQUESTED"), run: act("/approve", func(jobj) jobj { return nil })},
			{key: "hold", label: "Hold", tone: "danger", permission: "payouts:approve", level: domain.LevelApprove, form: reasonForm("Put the payout on hold", "Hold"), when: inState("REQUESTED"),
				run: act("/hold", func(v jobj) jobj { return jobj{"reason": vs(v, "reason")} })},
			{key: "release", label: "Release", tone: "neutral", permission: "payouts:approve", level: domain.LevelApprove, confirm: "Release the hold?", when: inState("ON_HOLD", "HELD"), run: act("/release", func(jobj) jobj { return nil })},
		},
	}
}

// ---------------------------------------------------------------- fee schedules (payments)

func feeSchedulesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/fee-schedules", nil, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "fee-schedules", module: "fees", title: "Fee schedules", exportable: true,
		columns: []domain.Column{col("version", "Version", "number", 10), col("effectiveFrom", "Effective from", "datetime", 20), col("defaultCommissionBps", "Commission (bps)", "number", 16),
			col("cardPctBps", "Card % (bps)", "number", 14), col("fbaPerUnitMinor", "FBA / unit", "money", 14), col("note", "Note", "text", 26)},
		list: list, get: findIn(list, "id"),
		titleOf:    func(o jobj) string { return "Fee schedule v" + jstr(o, "version") },
		subtitleOf: func(o jobj) string { return jstr(o, "note") },
		sections: func(o jobj) []domain.DetailSection {
			secs := autoSections(o, "", "categoryBps", "overrides")
			cat := domain.DetailSection{Title: "Commission by category (bps)", Fields: []domain.DetailField{}}
			if m, ok := o["categoryBps"].(map[string]any); ok {
				for k, v := range m {
					cat.Fields = append(cat.Fields, domain.DetailField{Label: k, Value: displayValue(v), Kind: "text"})
				}
			}
			return append(secs, cat)
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Seller overrides", objs(o, "overrides"), []domain.Column{col("orgId", "Organisation", "mono", 40), col("category", "Category", "text", 30), col("commissionBps", "Commission (bps)", "number", 30)}, "orgId")}
		},
		create: &staffCreate{permission: "payments:configure",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New fee schedule version (applies to new orders)", SubmitLabel: "Create version", Fields: []domain.FormField{
					ff("effectiveFrom", "Effective from", "datetime", true),
					withRange(ff("defaultCommissionBps", "Default commission (bps)", "number", true), 5000),
					withRange(ff("cardPctBps", "Card fee (bps)", "number", true), 1000),
					withRange(ff("cardFixedMinor", "Card fixed fee", "money", true), 1000),
					withRange(ff("fbaPerUnitMinor", "FBA fee per unit", "money", true), 10_000),
					withValue(ff("reverseCommissionOnRefund", "Reverse commission on refunds", "toggle", false), true),
					withMax(ff("note", "Note", "text", true), 200),
				}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				// categoryBps is required: carry the current schedule's category rates forward.
				current, _ := load(ctx, sc, staffQuery{})
				cats := jobj{}
				if len(current) > 0 {
					if m, ok := current[0]["categoryBps"].(map[string]any); ok {
						cats = m
					}
				}
				body := jobj{"effectiveFrom": vs(v, "effectiveFrom"), "currency": "USD", "categoryBps": cats,
					"defaultCommissionBps": int(vn(v, "defaultCommissionBps")), "cardPctBps": int(vn(v, "cardPctBps")),
					"cardFixedMinor": int64(vn(v, "cardFixedMinor")), "fbaPerUnitMinor": int64(vn(v, "fbaPerUnitMinor")),
					"reverseCommissionOnRefund": vb(v, "reverseCommissionOnRefund"), "note": vs(v, "note")}
				var out jobj
				if err := sc.post(ctx, sc.up.Payments, "/v1/fee-schedules", body, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

// ---------------------------------------------------------------- tax rates (tax-returns)

func taxRatesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var table jobj
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/rates", nil, &table); err != nil {
			return nil, err
		}
		out := []jobj{}
		if q.Status == "" || q.Status == "STATE" {
			for _, s := range objs(table, "states") {
				s["kind"], s["rowId"] = "STATE", "state:"+jstr(s, "state")
				out = append(out, s)
			}
		}
		if q.Status == "" || q.Status == "LOCAL" {
			for _, l := range objs(table, "locals") {
				l["kind"], l["rowId"], l["generalRate"] = "LOCAL", "local:"+jstr(l, "state")+":"+jstr(l, "zipPrefix"), l["rate"]
				out = append(out, l)
			}
		}
		return out, nil
	}
	list := listAll(load)
	rate := func(label string, hi float64, v any) domain.FormField {
		f := withRange(ff(label, domain.Humanize(label), "number", true), hi)
		f.Help = "Decimal, e.g. 0.0625 for 6.25 %"
		f.Value = v
		return f
	}
	return &staffResource{
		key: "tax-rates", module: "fees", title: "Tax rates", statusKey: "kind", exportable: true,
		columns: []domain.Column{col("kind", "Rule", "status", 10), col("state", "State", "mono", 8), col("zipPrefix", "ZIP prefix", "mono", 12), col("name", "Name", "strong", 22),
			col("generalRate", "General", "percent", 12), col("groceryRate", "Grocery", "percent", 12), col("preparedFoodRate", "Prepared food", "percent", 14), col("version", "Version", "number", 10)},
		facets: domain.StaffFacets("STATE", "LOCAL"),
		idOf:   func(o jobj) string { return jstr(o, "rowId") },
		list:   list, get: findIn(list, "rowId"),
		titleOf: func(o jobj) string {
			return strings.TrimSpace(jstr(o, "name") + " (" + jstr(o, "state") + " " + jstr(o, "zipPrefix") + ")")
		},
		subtitleOf: func(o jobj) string {
			if jstr(o, "kind") == "STATE" {
				return "State rule"
			}
			return "Local rate"
		},
		version: func(o jobj) string { return jstr(o, "version") },
		edit: &staffEdit{permission: "tax:configure",
			form: func(o jobj) domain.Form {
				if jstr(o, "kind") == "STATE" {
					return domain.Form{Title: "Edit the state rule", SubmitLabel: "Save rule", Fields: []domain.FormField{
						withValue(withMax(ff("name", "Name", "text", true), 80), jstr(o, "name")),
						rate("generalRate", 0.2, num(o, "generalRate")), rate("groceryRate", 0.2, num(o, "groceryRate")), rate("preparedFoodRate", 0.2, num(o, "preparedFoodRate")),
						withValue(ff("shippingTaxable", "Shipping is taxable", "toggle", false), boolean(o, "shippingTaxable")),
						withValue(ff("facilitator", "Marketplace facilitator", "toggle", false), boolean(o, "facilitator")),
					}}
				}
				return domain.Form{Title: "Edit the local rate", SubmitLabel: "Save rate", Fields: []domain.FormField{
					withValue(withMax(ff("name", "Name", "text", true), 80), jstr(o, "name")), rate("generalRate", 0.1, num(o, "generalRate")),
					withValue(ff("appliesToGrocery", "Applies to grocery", "toggle", false), boolean(o, "appliesToGrocery"))}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, version string) error {
				if jstr(o, "kind") == "STATE" {
					body := jobj{"country": firstNonEmpty(jstr(o, "country"), "US"), "state": jstr(o, "state"), "name": vs(v, "name"), "generalRate": vn(v, "generalRate"),
						"groceryRate": vn(v, "groceryRate"), "preparedFoodRate": vn(v, "preparedFoodRate"), "shippingTaxable": vb(v, "shippingTaxable"), "facilitator": vb(v, "facilitator")}
					if version != "" {
						body["version"] = num(o, "version")
					}
					return sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/rates/states/" + url.PathEscape(jstr(o, "state")), Body: body}, nil)
				}
				return sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/rates/local/" + url.PathEscape(jstr(o, "state")) + "/" + url.PathEscape(jstr(o, "zipPrefix")),
					Body: jobj{"name": vs(v, "name"), "rate": vn(v, "generalRate"), "appliesToGrocery": vb(v, "appliesToGrocery")}}, nil)
			}},
		create: &staffCreate{permission: "tax:configure",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New local rate", SubmitLabel: "Add rate", Fields: []domain.FormField{
					withPattern(ff("state", "State", "text", true), `^[A-Z]{2}$`, "Two-letter state code, e.g. TX"),
					withPattern(ff("zipPrefix", "ZIP or prefix", "text", true), `^([0-9]{3}|[0-9]{5})$`, "A 3-digit prefix or a 5-digit ZIP"),
					withMax(ff("name", "Name", "text", true), 80), rate("rate", 0.1, nil)}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				err := sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/rates/local/" + url.PathEscape(vs(v, "state")) + "/" + url.PathEscape(vs(v, "zipPrefix")),
					Body: jobj{"name": vs(v, "name"), "rate": vn(v, "rate"), "appliesToGrocery": false}}, nil)
				return "local:" + vs(v, "state") + ":" + vs(v, "zipPrefix"), err
			}},
	}
}
