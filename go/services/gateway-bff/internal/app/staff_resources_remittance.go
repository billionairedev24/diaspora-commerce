package app

import (
	"context"
	"math"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Tax filings (Fees & commissions → Sales tax): for every month and state with marketplace tax, what the ledger says is
// due, what staff recorded as filed and paid, and what is left. The record itself is kept by tax-returns.

var filingStatusLabels = map[string]string{
	"NOT_FILED":   "Not filed",
	"OWES_MORE":   "Owes more",
	"OVERPAID":    "Overpaid",
	"SETTLED":     "Settled",
	"NOTHING_DUE": "Nothing due",
}

// filingID is "2026-09|US-TX|USD".
func filingID(month, jurisdiction, currency string) string {
	return month + "|" + jurisdiction + "|" + currency
}

func parseFilingID(id string) (month, jurisdiction, currency string) {
	parts := strings.Split(id, "|")
	if len(parts) != 3 {
		return "", "", ""
	}
	return parts[0], parts[1], parts[2]
}

func filingRow(o jobj) jobj {
	rem, hasRemittance := o["remittance"].(map[string]any)
	row := jobj{"id": filingID(jstr(o, "month"), jstr(o, "jurisdiction"), jstr(o, "currency")), "month": jstr(o, "month"),
		"jurisdiction": jstr(o, "jurisdiction"), "currency": jstr(o, "currency"), "dueMinor": num(o, "dueMinor"),
		"outstandingMinor": num(o, "outstandingMinor"), "changedSinceFilingMinor": num(o, "changedSinceFilingMinor"),
		"state": jstr(o, "status"), "statusLabel": filingStatusLabels[jstr(o, "status")]}
	if hasRemittance {
		row["paidMinor"] = num(rem, "paidMinor")
		row["filedOn"], row["paidOn"] = jstr(rem, "filedOn"), jstr(rem, "paidOn")
		row["confirmationRef"], row["paymentRef"], row["note"] = jstr(rem, "confirmationRef"), jstr(rem, "paymentRef"), jstr(rem, "note")
		row["remittanceId"], row["recordedBy"], row["recordedAt"] = jstr(rem, "id"), jstr(rem, "recordedBy"), jstr(rem, "recordedAt")
		row["dueAtFilingMinor"] = num(rem, "dueMinor")
	}
	return row
}

func taxFilingsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		var raw jobj
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/remittances", url.Values{"months": {"12"}}, &raw); err != nil {
			return nil, err
		}
		rows := []jobj{}
		for _, o := range objs(raw, "obligations") {
			rows = append(rows, filingRow(o))
		}
		return filterState(rows, q.Status), nil
	}
	list := listAll(load)
	get := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		month, jurisdiction, currency := parseFilingID(id)
		if month == "" {
			return nil, domain.NewProblem(404, domain.CodeNotFound, "Not found", "We couldn't find that.")
		}
		var raw jobj
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/remittances/"+url.PathEscape(month)+"/"+url.PathEscape(jurisdiction),
			url.Values{"currency": {currency}}, &raw); err != nil {
			return nil, err
		}
		row := filingRow(raw)
		var hist []jobj
		var rawHist any
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/remittances/"+url.PathEscape(month)+"/history", nil, &rawHist); err != nil {
			return nil, err
		}
		w := newWhoIs(sc)
		for _, h := range jpage(rawHist).items {
			if jstr(h, "jurisdiction") != jurisdiction {
				continue
			}
			h["recordedByName"] = w.name(ctx, jstr(h, "recordedBy"))
			h["voidedByName"] = w.name(ctx, jstr(h, "voidedBy"))
			h["stateLabel"] = "Active"
			if !boolean(h, "active") {
				h["stateLabel"] = "Voided"
			}
			hist = append(hist, h)
		}
		row["history"] = hist
		row["recordedByName"] = w.name(ctx, jstr(row, "recordedBy"))
		return row, nil
	}
	canRecord := func(_ staffCtx, o jobj) string {
		if jstr(o, "remittanceId") != "" {
			return "A remittance is already recorded; void it first if it was a mistake."
		}
		return ""
	}
	return &staffResource{
		key: "tax-filings", module: "fees", title: "Tax filings", statusKey: "state", exportable: true,
		columns: []domain.Column{col("month", "Month", "mono", 10), col("jurisdiction", "State", "mono", 10), col("dueMinor", "Due now", "money", 12),
			col("paidMinor", "Paid", "money", 12), col("outstandingMinor", "Outstanding", "money", 12), col("filedOn", "Filed", "date", 12),
			col("confirmationRef", "State confirmation", "mono", 18), col("state", "Status", "status", 12)},
		facets: domain.StaffFacets("NOT_FILED", "OWES_MORE", "OVERPAID", "SETTLED", "NOTHING_DUE"),
		idOf:   func(o jobj) string { return jstr(o, "id") },
		list:   list, get: get,
		titleOf:    func(o jobj) string { return jstr(o, "jurisdiction") + " · " + jstr(o, "month") },
		subtitleOf: func(o jobj) string { return jstr(o, "statusLabel") },
		sections: func(o jobj) []domain.DetailSection {
			s := []domain.DetailSection{{Title: "What the ledger says", Fields: []domain.DetailField{
				fieldOf("Month", jstr(o, "month"), "text"), fieldOf("State", jstr(o, "jurisdiction"), "mono"),
				fieldOf("Net tax due now", jstr(o, "dueMinor"), "money"),
				fieldOf("Status", jstr(o, "state"), "status"),
				fieldOf("Outstanding", jstr(o, "outstandingMinor"), "money"),
				fieldOf("What it means", filingMeaning(o), "text"),
			}}}
			if jstr(o, "remittanceId") != "" {
				s = append(s, domain.DetailSection{Title: "What was filed and paid", Fields: []domain.DetailField{
					fieldOf("Paid to the state", jstr(o, "paidMinor"), "money"),
					fieldOf("Due when recorded", jstr(o, "dueAtFilingMinor"), "money"),
					fieldOf("Changed since", jstr(o, "changedSinceFilingMinor"), "money"),
					fieldOf("Filed on", jstr(o, "filedOn"), "date"), fieldOf("Paid on", jstr(o, "paidOn"), "date"),
					fieldOf("State confirmation", jstr(o, "confirmationRef"), "mono"), fieldOf("Payment reference", jstr(o, "paymentRef"), "mono"),
					fieldOf("Note", jstr(o, "note"), "text"),
					fieldOf("Recorded by", firstNonEmpty(jstr(o, "recordedByName"), jstr(o, "recordedBy")), "text"),
					fieldOf("Recorded", jstr(o, "recordedAt"), "datetime"),
				}})
			}
			return s
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, h := range objs(o, "history") {
				rows = append(rows, jobj{"id": jstr(h, "id"), "recordedAt": jstr(h, "recordedAt"), "stateLabel": jstr(h, "stateLabel"), "paidMinor": num(h, "paidMinor"),
					"byName": firstNonEmpty(jstr(h, "recordedByName"), jstr(h, "recordedBy")), "voidReason": jstr(h, "voidReason")})
			}
			return []domain.DetailTable{tableOf("Every record for this month and state", rows, []domain.Column{col("recordedAt", "Recorded", "datetime", 18), col("stateLabel", "Record", "text", 10),
				col("paidMinor", "Paid", "money", 12), col("byName", "By", "text", 16), col("voidReason", "Why it was voided", "text", 30)}, "id")}
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Liability report for the month", Href: "/settings?section=tax&liability=" + url.QueryEscape(jstr(o, "month"))}}
		},
		actions: []staffAction{
			{key: "record", label: "Record filing and payment", tone: "primary", permission: "tax:configure", when: canRecord,
				settled: func(o jobj) bool { return jstr(o, "remittanceId") != "" },
				form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
					return &domain.Form{Title: "Record what was filed and paid", SubmitLabel: "Record",
						Intro: "Do this after you have filed the return with " + jstr(o, "jurisdiction") + " and paid it. The amount due now (" + moneyText(num(o, "dueMinor")) + ") is saved with the record, so later changes to the month show up as a difference. A mistake is voided, not edited.",
						Fields: []domain.FormField{
							withValue(withRange(ff("paid", "Amount paid to the state (dollars)", "number", true), 100_000_000), math.Max(num(o, "dueMinor"), 0)/100),
							withValue(ff("filedOn", "Return filed on", "date", true), time.Now().UTC().Format(time.DateOnly)),
							withValue(ff("paidOn", "Payment made on", "date", true), time.Now().UTC().Format(time.DateOnly)),
							withMax(ff("confirmationRef", "State confirmation number", "text", false), 80),
							withMax(ff("paymentRef", "Payment reference (ACH trace, bank reference)", "text", false), 80),
							withMax(ff("note", "Note", "textarea", false), 1000),
						}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TaxReturns, "/v1/tax/remittances", jobj{"month": jstr(o, "month"), "jurisdiction": jstr(o, "jurisdiction"),
						"currency": jstr(o, "currency"), "paidMinor": int64(math.Round(vn(v, "paid") * 100)), "filedOn": vs(v, "filedOn"), "paidOn": vs(v, "paidOn"),
						"confirmationRef": vs(v, "confirmationRef"), "paymentRef": vs(v, "paymentRef"), "note": vs(v, "note")}, nil)
				}},
			{key: "void", label: "Void this record", tone: "danger", permission: "tax:configure",
				when: func(_ staffCtx, o jobj) string {
					if jstr(o, "remittanceId") == "" {
						return "Nothing is recorded for this month and state."
					}
					return ""
				},
				form: noteFormTitled("Why is it voided?", "Void", "Use this for a wrong amount or date. The record stays visible as voided, with your name and reason; then record the correct one."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.TaxReturns, "/v1/tax/remittances/"+url.PathEscape(jstr(o, "remittanceId"))+"/void", jobj{"reason": vs(v, "note")}, nil)
				}},
		},
	}
}

func filingMeaning(o jobj) string {
	out := num(o, "outstandingMinor")
	switch jstr(o, "state") {
	case "NOT_FILED":
		return "Tax was collected for " + jstr(o, "jurisdiction") + " and nothing is recorded as remitted yet."
	case "SETTLED":
		return "What was paid matches what the ledger says is due."
	case "OWES_MORE":
		return "The ledger now says " + moneyText(out) + " more is due than was paid (a sale was recorded late, or less was paid than due). File an amended return or pay the difference, then void and re-record, or note it."
	case "OVERPAID":
		return "More was paid than the ledger now says is due, by " + moneyText(-out) + " (a refund reached the ledger after filing). Claim it back or let it offset next month."
	default:
		return "No tax is due for this month and state."
	}
}
