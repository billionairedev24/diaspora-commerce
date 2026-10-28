package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Tax classification (tax-returns, docs/engineering/tax-classification-design.md): what an item is for tax. Three tables in
// Fees & commissions: the codes, the rules that assign them and the review queue of items taxed by default.

var taxCategories = domain.StaffOptions("GENERAL", "GROCERY", "PREPARED_FOOD")

func taxList(ctx context.Context, sc staffCtx, path string, query url.Values) ([]jobj, error) {
	var raw any
	if err := sc.get(ctx, sc.up.TaxReturns, path, query, &raw); err != nil {
		return nil, err
	}
	return jpage(raw).items, nil
}

func taxCodesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		rows, err := taxList(ctx, sc, "/v1/tax/codes", nil)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			r["state"] = "ACTIVE"
			if !boolean(r, "active") {
				r["state"] = "INACTIVE"
			}
			r["taxableLabel"] = "Yes"
			if !boolean(r, "taxable") {
				r["taxableLabel"] = "Never"
			}
		}
		return filterState(rows, q.Status), nil
	}
	list := listAll(load)
	return &staffResource{
		key: "tax-codes", module: "fees", title: "Tax codes", statusKey: "state", exportable: true,
		columns: []domain.Column{col("code", "Code", "mono", 18), col("label", "What it is", "strong", 30), col("category", "Rate column", "text", 16),
			col("providerCode", "Provider code", "mono", 18), col("taxableLabel", "Taxable", "text", 12),
			col("exemptStates", "Exempt in", "mono", 14), col("state", "State", "status", 10)},
		facets: domain.StaffFacets("ACTIVE", "INACTIVE"),
		idOf:   func(o jobj) string { return jstr(o, "code") },
		list:   list, get: findIn(list, "code"),
		titleOf:    func(o jobj) string { return jstr(o, "label") },
		subtitleOf: func(o jobj) string { return jstr(o, "code") },
		edit: &staffEdit{permission: "tax:configure",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Edit the tax code", SubmitLabel: "Save code",
					Intro: "The provider code is the matching code in Stripe Tax's list (txcd_...). Without it a quote for this code is calculated by the built-in rules instead. Turn Taxable off, or list exempt states, only for items you know are not taxed there: they are charged no tax and never sent to the tax provider.",
					Fields: []domain.FormField{
						withValue(withMax(ff("label", "What it is", "text", true), 120), jstr(o, "label")),
						withValue(withOptions(ff("category", "Rate column", "select", true), taxCategories), jstr(o, "category")),
						withValue(withMax(ff("providerCode", "Provider code", "text", false), 60), jstr(o, "providerCode")),
						withValue(ff("taxable", "Taxable", "toggle", false), boolean(o, "taxable")),
						withValue(withPattern(ff("exemptStates", "Known exempt in these states", "text", false), `^([A-Za-z]{2}([, ]+[A-Za-z]{2})*)?$`,
							"Two-letter codes, e.g. TX, NJ. No tax is charged there and the provider is not called."), jstr(o, "exemptStates")),
						withValue(ff("active", "Active", "toggle", false), boolean(o, "active")),
					}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/codes/" + url.PathEscape(jstr(o, "code")),
					Body: jobj{"label": vs(v, "label"), "category": vs(v, "category"), "providerCode": vs(v, "providerCode"), "active": vb(v, "active"),
						"taxable": vb(v, "taxable"), "exemptStates": vs(v, "exemptStates")}}, nil)
			}},
		create: &staffCreate{permission: "tax:configure",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New tax code", SubmitLabel: "Add code", Fields: []domain.FormField{
					withPattern(ff("code", "Code", "text", true), `^[A-Z0-9_]{2,40}$`, "Capital letters, digits and underscores, e.g. BABY_CLOTHING"),
					withMax(ff("label", "What it is", "text", true), 120),
					withValue(withOptions(ff("category", "Rate column", "select", true), taxCategories), "GENERAL"),
					withMax(ff("providerCode", "Provider code", "text", false), 60),
					withValue(ff("taxable", "Taxable", "toggle", false), true),
					withPattern(ff("exemptStates", "Known exempt in these states", "text", false), `^([A-Za-z]{2}([, ]+[A-Za-z]{2})*)?$`, "Two-letter codes, e.g. TX, NJ")}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				code := strings.ToUpper(vs(v, "code"))
				err := sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/codes/" + url.PathEscape(code),
					Body: jobj{"label": vs(v, "label"), "category": vs(v, "category"), "providerCode": vs(v, "providerCode"), "active": true,
						"taxable": vb(v, "taxable"), "exemptStates": vs(v, "exemptStates")}}, nil)
				return code, err
			}},
	}
}

func taxRulesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		rows, err := taxList(ctx, sc, "/v1/tax/classification-rules", nil)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			r["rowId"] = jstr(r, "productType") + "|" + jstr(r, "attrKey") + "|" + jstr(r, "attrValue")
			r["kind"] = "TYPE"
			if jstr(r, "attrKey") != "" {
				r["kind"] = "ANSWER"
			}
		}
		return filterState(rows, q.Status, "kind"), nil
	}
	list := listAll(load)
	return &staffResource{
		key: "tax-rules", module: "fees", title: "Tax classification rules", statusKey: "kind", exportable: true,
		columns: []domain.Column{col("productType", "Product type", "mono", 16), col("attrKey", "Question", "mono", 14), col("attrValue", "Seller's answer", "text", 28),
			col("taxCode", "Tax code", "strong", 18), col("kind", "Rule", "status", 10), col("note", "Why", "text", 24)},
		facets: domain.StaffFacets("TYPE", "ANSWER"),
		idOf:   func(o jobj) string { return jstr(o, "rowId") },
		list:   list, get: findIn(list, "rowId"),
		titleOf: func(o jobj) string {
			return strings.TrimSpace(jstr(o, "productType") + " " + jstr(o, "attrValue"))
		},
		subtitleOf: func(o jobj) string { return "→ " + jstr(o, "taxCode") },
		edit: &staffEdit{permission: "tax:configure",
			form: func(o jobj) domain.Form {
				return domain.Form{Title: "Change the tax code of this rule", SubmitLabel: "Save rule",
					Intro: "Applies to new quotes within a minute. Orders already placed keep the code they were taxed under.",
					Fields: []domain.FormField{
						withValue(withPattern(ff("taxCode", "Tax code", "text", true), `^[A-Z0-9_]{2,40}$`, "A code from the Tax codes table"), jstr(o, "taxCode")),
						withValue(withMax(ff("note", "Why", "text", false), 300), jstr(o, "note"))}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				return saveTaxRule(ctx, sc, jstr(o, "productType"), jstr(o, "attrKey"), jstr(o, "attrValue"), vs(v, "taxCode"), vs(v, "note"))
			}},
		create: &staffCreate{permission: "tax:configure",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "New classification rule", SubmitLabel: "Add rule",
					Intro: "Leave the question and answer empty to classify a whole product type. With an answer, the rule applies only when the seller gave that answer.",
					Fields: []domain.FormField{
						withMax(ff("productType", "Product type", "text", true), 80),
						withMax(ff("attrKey", "Question (attribute key)", "text", false), 80),
						withMax(ff("attrValue", "Seller's answer", "text", false), 120),
						withPattern(ff("taxCode", "Tax code", "text", true), `^[A-Z0-9_]{2,40}$`, "A code from the Tax codes table"),
						withMax(ff("note", "Why", "text", false), 300)}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				err := saveTaxRule(ctx, sc, strings.ToLower(vs(v, "productType")), vs(v, "attrKey"), vs(v, "attrValue"), vs(v, "taxCode"), vs(v, "note"))
				return strings.ToLower(vs(v, "productType")) + "|" + vs(v, "attrKey") + "|" + vs(v, "attrValue"), err
			}},
	}
}

func saveTaxRule(ctx context.Context, sc staffCtx, productType, attrKey, attrValue, taxCode, note string) error {
	body := jobj{"productType": productType, "taxCode": strings.ToUpper(taxCode), "note": note}
	if attrKey != "" || attrValue != "" {
		body["attrKey"], body["attrValue"] = attrKey, attrValue
	}
	return sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/classification-rules", Body: body}, nil)
}

func taxReviewResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		rows, err := taxList(ctx, sc, "/v1/tax/review-queue", url.Values{"includeReviewed": {"true"}})
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			r["rowId"] = jstr(r, "productType") + "|" + jstr(r, "attrHash")
			r["state"] = "OPEN"
			if jstr(r, "reviewedAt") != "" {
				r["state"] = "REVIEWED"
			}
			if a, ok := r["attributes"].(map[string]any); ok {
				if v, ok := a["tax_class"].(string); ok {
					r["answer"] = v
				}
			}
		}
		return filterState(rows, q.Status), nil
	}
	list := listAll(load)
	return &staffResource{
		key: "tax-review", module: "fees", title: "Tax review queue", statusKey: "state", exportable: true,
		searchPlaceholder: "Search product types",
		columns: []domain.Column{col("productType", "Product type", "mono", 16), col("answer", "Seller's answer", "text", 28), col("taxCode", "Taxed as", "strong", 18),
			col("count", "Order lines", "number", 10), col("lastSeen", "Last seen", "datetime", 16), col("state", "State", "status", 12)},
		facets: domain.StaffFacets("OPEN", "REVIEWED"),
		idOf:   func(o jobj) string { return jstr(o, "rowId") },
		list:   list, get: findIn(list, "rowId"),
		names: []nameRef{personRef("reviewedBy", "Reviewed by")},
		titleOf: func(o jobj) string {
			return strings.TrimSpace(jstr(o, "productType") + " " + jstr(o, "answer"))
		},
		subtitleOf: func(o jobj) string {
			return "Taxed as general goods because no rule matched. Add a rule, or mark it reviewed if general goods is right."
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Item", Fields: []domain.DetailField{
				{Label: "Product type", Value: jstr(o, "productType"), Kind: "text"}, {Label: "Seller's answer", Value: firstNonEmpty(jstr(o, "answer"), "not answered"), Kind: "text"},
				{Label: "Example item (ATIN)", Value: jstr(o, "sampleAtin"), Kind: "text"}, {Label: "Order lines seen", Value: jstr(o, "count"), Kind: "text"},
				{Label: "Reviewed by", Value: firstNonEmpty(jstr(o, "reviewedByName"), "—"), Kind: "text"}, {Label: "Note", Value: firstNonEmpty(jstr(o, "reviewNote"), "—"), Kind: "text"}}}}
		},
		actions: []staffAction{
			{key: "reviewed", label: "Mark reviewed", tone: "primary", permission: "tax:configure", form: noteForm("Mark as reviewed", "Mark reviewed", false), when: inState("OPEN"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TaxReturns, "/v1/tax/review-queue/reviewed",
						jobj{"productType": jstr(o, "productType"), "attrHash": jstr(o, "attrHash"), "note": vs(v, "note")}, nil)
				}},
		},
	}
}

// filterState keeps the rows whose state field (default "state") equals want; an empty want keeps all.
func filterState(rows []jobj, want string, key ...string) []jobj {
	if want == "" {
		return rows
	}
	field := "state"
	if len(key) > 0 {
		field = key[0]
	}
	out := make([]jobj, 0, len(rows))
	for _, r := range rows {
		if strings.EqualFold(jstr(r, field), want) {
			out = append(out, r)
		}
	}
	return out
}
