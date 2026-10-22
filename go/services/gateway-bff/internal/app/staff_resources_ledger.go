package app

import (
	"context"
	"fmt"
	"math"
	"net/url"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Reconciliation and ledger adjustments (payments, docs/runbooks/finance-reconciliation.md): the daily comparison of the
// ledger with Stripe, each difference as an item Finance explains or corrects, the correcting journals (proposed by one
// person, approved by another) and a lookup of any Stripe object next to what the ledger booked for it.

func ledgerResources() []*staffResource {
	return []*staffResource{reconciliationResource(), reconciliationRunsResource(), ledgerAdjustmentsResource(), stripeLookupResource()}
}

// ---------------------------------------------------------------- names instead of ids

// whoIs turns an identity subject into a name ("Ada Obi"), remembering answers for the request; the id when unknown.
type whoIs struct {
	sc    staffCtx
	known map[string]string
}

func newWhoIs(sc staffCtx) *whoIs { return &whoIs{sc: sc, known: map[string]string{}} }

func (w *whoIs) name(ctx context.Context, id string) string {
	if id == "" || id == "system" {
		return id
	}
	if n, ok := w.known[id]; ok {
		return n
	}
	name := id
	var u jobj
	if w.sc.get(ctx, w.sc.up.Identity, "/v1/admin/users/"+url.PathEscape(id), nil, &u) == nil {
		name = firstNonEmpty(jstr(u, "name"), jstr(u, "email"), id)
	}
	w.known[id] = name
	return name
}

func (w *whoIs) set(ctx context.Context, o jobj, idKey, nameKey string) {
	if id := jstr(o, idKey); id != "" {
		o[nameKey] = w.name(ctx, id)
	}
}

// ---------------------------------------------------------------- the ledger accounts a correction may use

var adjustmentAccounts = []domain.FieldOption{
	{Value: "5000", Label: "5000 · Stripe processing fees"},
	{Value: "2000", Label: "2000 · Seller payable (needs the organisation id)"},
	{Value: "4000", Label: "4000 · Commission revenue"},
	{Value: "4100", Label: "4100 · FBA fee revenue"},
	{Value: "4200", Label: "4200 · AfroTransact shipping revenue"},
	{Value: "2100", Label: "2100 · Sales tax payable (needs the jurisdiction)"},
	{Value: "2200", Label: "2200 · Customer refunds payable"},
	{Value: "1000", Label: "1000 · Stripe clearing"},
	{Value: "1100", Label: "1100 · Chargeback receivable"},
	{Value: "5100", Label: "5100 · Chargeback losses"},
}

func accountTitle(code string) string {
	for _, a := range adjustmentAccounts {
		if a.Value == code {
			return a.Label
		}
	}
	return code
}

// moneyText formats minor units as dollars for the sentences on a sheet.
func moneyText(minor float64) string { return domain.Money(int64(math.Round(minor))) }

// journalLines reads one journal's lines as "Debit 1000 Stripe clearing $2.50 · Credit 5000 …".
func journalLines(j jobj) string {
	parts := []string{}
	for _, l := range objs(j, "lines") {
		side := "Debit"
		if strings.EqualFold(jstr(l, "side"), "CREDIT") {
			side = "Credit"
		}
		parts = append(parts, fmt.Sprintf("%s %s %s", side, jstr(l, "account"), moneyText(num(l, "amountMinor"))))
	}
	return strings.Join(parts, " · ")
}

func journalRows(items []jobj) []jobj {
	rows := make([]jobj, 0, len(items))
	for _, j := range items {
		rows = append(rows, jobj{"id": jstr(j, "id"), "type": domain.Humanize(jstr(j, "type")), "description": jstr(j, "description"),
			"occurredAt": jstr(j, "occurredAt"), "lines": journalLines(j)})
	}
	return rows
}

var journalColumns = []domain.Column{col("type", "Kind", "text", 16), col("description", "What it was", "text", 30), col("lines", "Booked as", "text", 40), col("occurredAt", "When", "datetime", 14)}

func stripeSection(title string, lookup jobj) domain.DetailSection {
	sec := domain.DetailSection{Title: title, Fields: []domain.DetailField{}}
	obj, ok := lookup["object"].(map[string]any)
	if !ok {
		sec.Fields = append(sec.Fields, fieldOf("Stripe says", firstNonEmpty(jstr(lookup, "problem"), "Stripe was not asked"), "text"))
		return sec
	}
	sec.Fields = append(sec.Fields,
		fieldOf("What it is", jstr(obj, "kind"), "text"),
		fieldOf("Stripe id", jstr(obj, "id"), "mono"),
		fieldOf("Status at Stripe", jstr(obj, "status"), "text"))
	if num(obj, "amountMinor") != 0 {
		sec.Fields = append(sec.Fields, fieldOf("Amount", jstr(obj, "amountMinor"), "money"))
	}
	sec.Fields = append(sec.Fields,
		fieldOf("Created at Stripe", jstr(obj, "created"), "datetime"),
		fieldOf("Description", jstr(obj, "description"), "text"),
		fieldOf("Failure", jstr(obj, "failure"), "text"))
	if links, ok := obj["links"].(map[string]any); ok {
		for _, k := range sortedKeys(links) {
			sec.Fields = append(sec.Fields, fieldOf(domain.Humanize(k), displayValue(links[k]), fieldKind(k, "")))
		}
	}
	return sec
}

func sortedKeys(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// ---------------------------------------------------------------- differences (reconciliation items)

func reconciliationResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"500"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/reconciliation/items", v, &raw); err != nil {
			return nil, err
		}
		rows := jpage(raw).items
		return rows, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "reconciliation", module: "payouts", title: "Differences", statusKey: "status", exportable: true,
		columns: []domain.Column{col("stripeRef", "Stripe object", "mono", 20), col("windowStart", "Day", "date", 11), col("ledgerMinor", "Ledger", "money", 12),
			col("stripeMinor", "Stripe", "money", 12), col("differenceMinor", "Difference", "money", 12), col("note", "Why", "text", 21), col("status", "Status", "status", 12)},
		facets: domain.StaffFacets("OPEN", "EXPLAINED", "ADJUSTED", "SUPERSEDED"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var d jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/reconciliation/items/"+url.PathEscape(id), nil, &d); err != nil {
				return nil, err
			}
			o, _ := d["item"].(map[string]any)
			item := jobj(o)
			item["stripe"] = d["stripe"]
			item["journals"] = d["journals"]
			item["adjustments"] = d["adjustments"]
			w := newWhoIs(sc)
			w.set(ctx, item, "resolvedBy", "resolvedByName")
			for _, a := range objs(item, "adjustments") {
				w.set(ctx, a, "proposedBy", "proposedByName")
			}
			return item, nil
		},
		idOf:       func(o jobj) string { return jstr(o, "id") },
		titleOf:    func(o jobj) string { return "Difference on " + jstr(o, "stripeRef") },
		subtitleOf: func(o jobj) string { return domain.Humanize(jstr(o, "status")) },
		sections: func(o jobj) []domain.DetailSection {
			diff := num(o, "differenceMinor")
			meaning := "The ledger and Stripe agree."
			switch {
			case diff > 0:
				meaning = "The ledger shows " + moneyText(diff) + " more than Stripe reports for this object."
			case diff < 0:
				meaning = "Stripe reports " + moneyText(-diff) + " more than the ledger shows for this object."
			}
			secs := []domain.DetailSection{{Title: "The difference", Fields: []domain.DetailField{
				fieldOf("Stripe object", jstr(o, "stripeRef"), "mono"),
				fieldOf("Day checked", jstr(o, "windowStart"), "date"),
				fieldOf("Ledger net", jstr(o, "ledgerMinor"), "money"),
				fieldOf("Stripe net", jstr(o, "stripeMinor"), "money"),
				fieldOf("Difference (ledger − Stripe)", jstr(o, "differenceMinor"), "money"),
				fieldOf("What it means", meaning, "text"),
			}}}
			if stripe, ok := o["stripe"].(map[string]any); ok {
				secs = append(secs, stripeSection("What Stripe says now", jobj(stripe)))
			}
			return append(secs, domain.DetailSection{Title: "Review", Fields: []domain.DetailField{
				fieldOf("Status", jstr(o, "status"), "status"),
				fieldOf("Explanation", jstr(o, "note"), "text"),
				fieldOf("Handled by", firstNonEmpty(jstr(o, "resolvedByName"), jstr(o, "resolvedBy")), "text"),
				fieldOf("Handled at", jstr(o, "resolvedAt"), "datetime"),
			}})
		},
		tables: func(o jobj) []domain.DetailTable {
			tables := []domain.DetailTable{tableOf("What the ledger booked for this Stripe object", journalRows(objs(o, "journals")), journalColumns, "id")}
			if adj := objs(o, "adjustments"); len(adj) > 0 {
				tables = append(tables, tableOf("Corrections proposed for it", adjustmentRows(adj), []domain.Column{col("reason", "Why", "text", 40), col("amountMinor", "Amount", "money", 14),
					col("proposedByName", "Proposed by", "text", 20), col("status", "Status", "status", 14)}, "id"))
			}
			return tables
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Look up in Stripe", Href: "/payouts?tab=stripe-lookup&open=" + url.QueryEscape(jstr(o, "stripeRef"))}}
		},
		actions: []staffAction{
			{key: "explain", label: "Mark as explained", tone: "neutral", permission: "ledger:adjust", when: inState("OPEN"),
				form: noteFormTitled("Why is this not an error?", "Mark explained",
					"Use this when the difference is expected (a fee that settles the next day, a timing gap). Nothing is posted to the ledger. A later run of the same day that finds the same difference keeps it explained."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/reconciliation/items/"+url.PathEscape(jstr(o, "id"))+"/explain", jobj{"note": vs(v, "note")}, nil)
				}},
			{key: "propose-correction", label: "Propose a correction", tone: "primary", permission: "ledger:adjust", when: inState("OPEN", "EXPLAINED"),
				form: proposeCorrectionForm,
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					lines, err := correctionLines(int64(math.Round(num(o, "differenceMinor"))), vs(v, "account"), vs(v, "key"))
					if err != nil {
						return err
					}
					body := jobj{"itemId": jstr(o, "id"), "reason": vs(v, "reason"), "lines": lines}
					return sc.post(ctx, sc.up.Payments, "/v1/ledger/adjustments", body, nil)
				}},
		},
	}
}

// correctionLines is the journal that makes the ledger match Stripe for one object: when the ledger holds more than Stripe
// (difference above zero) 1000 Stripe clearing is credited and the account that explains it debited; the other way round
// when Stripe holds more.
func correctionLines(diff int64, account, key string) ([]jobj, error) {
	if diff == 0 {
		return nil, domain.NewProblem(400, domain.CodeValidationFailed, "Nothing to correct", "The ledger and Stripe already agree for this object.")
	}
	amount, clearing, counter := diff, "CREDIT", "DEBIT"
	if diff < 0 {
		amount, clearing, counter = -diff, "DEBIT", "CREDIT"
	}
	return []jobj{
		{"account": "1000", "key": "", "side": clearing, "amountMinor": amount},
		{"account": account, "key": key, "side": counter, "amountMinor": amount, "orgId": orgForAccount(account, key)},
	}, nil
}

func orgForAccount(account, key string) string {
	if account == "2000" {
		return key
	}
	return ""
}

func noteFormTitled(title, submit, intro string) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro,
			Fields: []domain.FormField{withMax(ff("note", "Explanation (at least 10 characters)", "textarea", true), 2000)}}
	}
}

func proposeCorrectionForm(_ context.Context, _ staffCtx, o jobj) *domain.Form {
	diff := num(o, "differenceMinor")
	var intro string
	switch {
	case diff > 0:
		intro = "The ledger shows " + moneyText(diff) + " more than Stripe. This proposes taking " + moneyText(diff) + " out of 1000 Stripe clearing (a credit) and booking it against the account below (a debit)."
	default:
		intro = "Stripe shows " + moneyText(-diff) + " more than the ledger. This proposes adding " + moneyText(-diff) + " to 1000 Stripe clearing (a debit) and booking it against the account below (a credit)."
	}
	intro += " Nothing reaches the ledger until a different person approves it under Ledger adjustments."
	return &domain.Form{Title: "Propose a correction", SubmitLabel: "Propose", Intro: intro, Fields: []domain.FormField{
		withValue(withOptions(ff("account", "Book the other side against", "select", true), adjustmentAccounts), "5000"),
		withPattern(withMax(ff("key", "Organisation id or jurisdiction (only for 2000 and 2100)", "text", false), 80), "", "Seller payable needs the seller's organisation id, for example org_abc123. Leave empty for the other accounts."),
		withMax(ff("reason", "Why the ledger is wrong (at least 15 characters)", "textarea", true), 1000),
	}}
}

// ---------------------------------------------------------------- daily runs

func reconciliationRunsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/reconciliation/runs", url.Values{"limit": {"100"}}, &raw); err != nil {
			return nil, err
		}
		rows := jpage(raw).items
		for _, r := range rows {
			r["state"] = "RECONCILED"
			if num(r, "driftMinor") != 0 {
				r["state"] = "DIFFERENCES"
			}
			r["differences"] = len(objs(r, "drifts"))
		}
		return rows, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "reconciliation-runs", module: "payouts", title: "Daily runs", statusKey: "state", exportable: true,
		columns: []domain.Column{col("windowStart", "Day", "date", 14), col("ledgerNetMinor", "Ledger net", "money", 16), col("stripeNetMinor", "Stripe net", "money", 16),
			col("driftMinor", "Total difference", "money", 16), col("differences", "Objects that differ", "number", 16), col("state", "Result", "status", 14), col("createdAt", "Run at", "datetime", 8)},
		facets: domain.StaffFacets("RECONCILED", "DIFFERENCES"),
		list:   func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) { return list(ctx, sc, q) },
		get:    findIn(list, "id"),
		titleOf: func(o jobj) string {
			return "Reconciliation of " + strings.SplitN(jstr(o, "windowStart"), "T", 2)[0]
		},
		subtitleOf: func(o jobj) string { return domain.Humanize(jstr(o, "state")) },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "Result", Fields: []domain.DetailField{
				fieldOf("Day", jstr(o, "windowStart"), "date"),
				fieldOf("Ledger net on 1000 Stripe clearing", jstr(o, "ledgerNetMinor"), "money"),
				fieldOf("Stripe balance transactions net", jstr(o, "stripeNetMinor"), "money"),
				fieldOf("Total difference", jstr(o, "driftMinor"), "money"),
				fieldOf("Run at", jstr(o, "createdAt"), "datetime"),
			}}}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, d := range objs(o, "drifts") {
				rows = append(rows, jobj{"stripeRef": jstr(d, "stripeRef"), "ledgerMinor": num(d, "ledgerMinor"), "stripeMinor": num(d, "stripeMinor"),
					"differenceMinor": num(d, "ledgerMinor") - num(d, "stripeMinor")})
			}
			return []domain.DetailTable{tableOf("Stripe objects that differ", rows, []domain.Column{col("stripeRef", "Stripe object", "mono", 34), col("ledgerMinor", "Ledger", "money", 22),
				col("stripeMinor", "Stripe", "money", 22), col("differenceMinor", "Difference", "money", 22)}, "stripeRef")}
		},
		links: func(jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Work through the differences", Href: "/payouts?tab=reconciliation"}}
		},
		create: &staffCreate{permission: "payments:configure",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "Reconcile a day", SubmitLabel: "Run", Intro: "Compares what the ledger booked on 1000 Stripe clearing with Stripe's balance transactions for that day, Stripe object by Stripe object. Every difference becomes an item to explain or correct. Running a day again replaces its earlier open items.",
					Fields: []domain.FormField{ff("date", "Day", "date", true)}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				var run jobj
				if err := sc.post(ctx, sc.up.Payments, "/v1/reconciliation/runs", jobj{"date": vs(v, "date")}, &run); err != nil {
					return "", err
				}
				return jstr(run, "id"), nil
			}},
	}
}

// ---------------------------------------------------------------- ledger adjustments

func adjustmentRows(items []jobj) []jobj {
	for _, a := range items {
		var debit float64
		for _, l := range objs(a, "lines") {
			if strings.EqualFold(jstr(l, "side"), "DEBIT") {
				debit += num(l, "amountMinor")
			}
		}
		a["amountMinor"] = debit
	}
	return items
}

func ledgerAdjustmentsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"500"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/ledger/adjustments", v, &raw); err != nil {
			return nil, err
		}
		rows := adjustmentRows(jpage(raw).items)
		w := newWhoIs(sc)
		for _, r := range rows {
			w.set(ctx, r, "proposedBy", "proposedByName")
			w.set(ctx, r, "decidedBy", "decidedByName")
		}
		return rows, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "ledger-adjustments", module: "payouts", title: "Ledger adjustments", statusKey: "status", exportable: true,
		columns: []domain.Column{col("reason", "Why", "text", 34), col("amountMinor", "Amount", "money", 12), col("stripeRef", "Stripe object", "mono", 16),
			col("proposedByName", "Proposed by", "text", 14), col("decidedByName", "Decided by", "text", 14), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PROPOSED", "POSTED", "REJECTED"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var a jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/ledger/adjustments/"+url.PathEscape(id), nil, &a); err != nil {
				return nil, err
			}
			adjustmentRows([]jobj{a})
			w := newWhoIs(sc)
			w.set(ctx, a, "proposedBy", "proposedByName")
			w.set(ctx, a, "decidedBy", "decidedByName")
			return a, nil
		},
		titleOf:    func(o jobj) string { return "Adjustment of " + moneyText(num(o, "amountMinor")) },
		subtitleOf: func(o jobj) string { return jstr(o, "reason") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "The correction", Fields: []domain.DetailField{
					fieldOf("Why", jstr(o, "reason"), "text"),
					fieldOf("Amount", jstr(o, "amountMinor"), "money"),
					fieldOf("Stripe object", jstr(o, "stripeRef"), "mono"),
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("Ledger journal", jstr(o, "journalId"), "mono"),
				}},
				{Title: "Who decided", Fields: []domain.DetailField{
					fieldOf("Proposed by", firstNonEmpty(jstr(o, "proposedByName"), jstr(o, "proposedBy")), "text"),
					fieldOf("Proposed at", jstr(o, "proposedAt"), "datetime"),
					fieldOf("Decided by", firstNonEmpty(jstr(o, "decidedByName"), jstr(o, "decidedBy")), "text"),
					fieldOf("Decided at", jstr(o, "decidedAt"), "datetime"),
					fieldOf("Decision note", jstr(o, "decisionNote"), "text"),
				}},
			}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, l := range objs(o, "lines") {
				side := "Debit"
				if strings.EqualFold(jstr(l, "side"), "CREDIT") {
					side = "Credit"
				}
				rows = append(rows, jobj{"account": jstr(l, "account") + " · " + jstr(l, "title"), "side": side, "amountMinor": num(l, "amountMinor"), "orgId": jstr(l, "orgId")})
			}
			return []domain.DetailTable{tableOf("Journal lines", rows, []domain.Column{col("account", "Account", "text", 46), col("side", "Debit or credit", "text", 16),
				col("amountMinor", "Amount", "money", 16), col("orgId", "Seller", "mono", 22)}, "account")}
		},
		links: func(o jobj) []domain.RecordLink {
			if jstr(o, "itemId") == "" {
				return nil
			}
			return []domain.RecordLink{{Label: "The difference it corrects", Href: "/payouts?tab=reconciliation&open=" + url.QueryEscape(jstr(o, "itemId"))}}
		},
		actions: []staffAction{
			{key: "approve", label: "Approve and post", tone: "primary", permission: "ledger:approve", when: func(sc staffCtx, o jobj) string {
				if r := stateReason(jstr(o, "status"), "PROPOSED"); r != "" {
					return r
				}
				if jstr(o, "proposedBy") == sc.me.ID {
					return "You proposed this, so a different person must approve it."
				}
				return ""
			},
				confirm: "Post this correction to the ledger now? It cannot be edited afterwards; a mistake is fixed with a new adjustment.",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/ledger/adjustments/"+url.PathEscape(jstr(o, "id"))+"/approve", jobj{}, nil)
				}},
			{key: "reject", label: "Reject", tone: "danger", permission: "ledger:approve", when: inState("PROPOSED"),
				form: noteFormTitled("Why is it rejected?", "Reject", "The proposer sees this reason. The adjustment stays on record as rejected."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/ledger/adjustments/"+url.PathEscape(jstr(o, "id"))+"/reject", jobj{"note": vs(v, "note")}, nil)
				}},
		},
		create: &staffCreate{permission: "ledger:adjust",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "Propose an adjustment", SubmitLabel: "Propose", Intro: "A correction that is not tied to a reconciliation difference. Pick what to debit and what to credit, for the same amount. Nothing reaches the ledger until a different person approves it.",
					Fields: []domain.FormField{
						withOptions(ff("debitAccount", "Debit", "select", true), adjustmentAccounts),
						withMax(ff("debitKey", "Debit: organisation id or jurisdiction (2000, 2100 only)", "text", false), 80),
						withOptions(ff("creditAccount", "Credit", "select", true), adjustmentAccounts),
						withMax(ff("creditKey", "Credit: organisation id or jurisdiction (2000, 2100 only)", "text", false), 80),
						withRange(ff("amount", "Amount in dollars", "number", true), 10_000_000),
						withMax(ff("stripeRef", "Stripe object it corrects (needed when 1000 is used)", "text", false), 120),
						withMax(ff("reason", "Why the ledger is wrong (at least 15 characters)", "textarea", true), 1000),
					}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				amount := int64(math.Round(vn(v, "amount") * 100))
				body := jobj{"stripeRef": vs(v, "stripeRef"), "reason": vs(v, "reason"), "lines": []jobj{
					{"account": vs(v, "debitAccount"), "key": vs(v, "debitKey"), "side": "DEBIT", "amountMinor": amount, "orgId": orgForAccount(vs(v, "debitAccount"), vs(v, "debitKey"))},
					{"account": vs(v, "creditAccount"), "key": vs(v, "creditKey"), "side": "CREDIT", "amountMinor": amount, "orgId": orgForAccount(vs(v, "creditAccount"), vs(v, "creditKey"))},
				}}
				var out jobj
				if err := sc.post(ctx, sc.up.Payments, "/v1/ledger/adjustments", body, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

// ---------------------------------------------------------------- Stripe lookup

// lookupID is "ref" or "ref org_xxx": a payout lives on the seller's connected account, so it needs the seller.
func lookupID(s string) (ref, org string) {
	parts := strings.Fields(strings.ReplaceAll(s, "|", " "))
	if len(parts) == 0 {
		return "", ""
	}
	ref = parts[0]
	if len(parts) > 1 {
		org = parts[1]
	}
	return ref, org
}

func lookupOf(ctx context.Context, sc staffCtx, s string) (jobj, error) {
	ref, org := lookupID(s)
	v := url.Values{"ref": {ref}}
	if org != "" {
		v.Set("orgId", org)
	}
	var l jobj
	if err := sc.get(ctx, sc.up.Payments, "/v1/stripe/lookup", v, &l); err != nil {
		return nil, err
	}
	l["id"] = strings.TrimSpace(ref + " " + org)
	if obj, ok := l["object"].(map[string]any); ok {
		l["kind"], l["stripeStatus"], l["amountMinor"], l["created"] = obj["kind"], obj["status"], obj["amountMinor"], obj["created"]
	} else {
		l["kind"], l["stripeStatus"] = "Not found at Stripe", "NOT_FOUND"
	}
	return l, nil
}

func stripeLookupResource() *staffResource {
	return &staffResource{
		key: "stripe-lookup", module: "payouts", title: "Stripe lookup", statusKey: "stripeStatus",
		columns: []domain.Column{col("ref", "Stripe id", "mono", 30), col("kind", "What it is", "text", 20), col("stripeStatus", "Status at Stripe", "status", 20),
			col("amountMinor", "Amount", "money", 14), col("created", "Created", "datetime", 16)},
		// Nothing to list: a lookup is made with the Look up button (or a link from a payout or a difference).
		list:       func(context.Context, staffCtx, staffQuery) (staffList, error) { return staffList{}, nil },
		get:        func(ctx context.Context, sc staffCtx, id string) (jobj, error) { return lookupOf(ctx, sc, id) },
		idOf:       func(o jobj) string { return jstr(o, "id") },
		titleOf:    func(o jobj) string { return "Stripe " + jstr(o, "ref") },
		subtitleOf: func(o jobj) string { return jstr(o, "kind") },
		sections: func(o jobj) []domain.DetailSection {
			secs := []domain.DetailSection{stripeSection("What Stripe says", o)}
			if b, ok := o["balance"].(map[string]any); ok {
				secs = append(secs, domain.DetailSection{Title: "The seller's Stripe balance", Fields: []domain.DetailField{
					fieldOf("Connected account", jstr(o, "connectedAccount"), "mono"),
					fieldOf("Available at Stripe", jstr(b, "availableMinor"), "money"),
					fieldOf("Pending at Stripe", jstr(b, "pendingMinor"), "money"),
				}})
			}
			return secs
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("What the ledger booked for this Stripe object", journalRows(objs(o, "journals")), journalColumns, "id")}
		},
		create: &staffCreate{permission: "payments:read",
			form: func(context.Context, staffCtx) domain.Form {
				return domain.Form{Title: "Look up in Stripe", SubmitLabel: "Look up",
					Intro: "Reads the object from Stripe and shows what the ledger booked for it. A payout lives on the seller's Stripe account, so give the seller's organisation id with a payout id.",
					Fields: []domain.FormField{
						withPattern(withMax(ff("ref", "Stripe id", "text", true), 120), "^[a-z]{2,6}_[A-Za-z0-9_]{6,100}$", "For example po_…, tr_…, pi_…, ch_…, re_…, txn_…, dp_…, acct_…"),
						withMax(ff("orgId", "Seller's organisation id (for a payout)", "text", false), 80),
					}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				l, err := lookupOf(ctx, sc, vs(v, "ref")+" "+vs(v, "orgId"))
				if err != nil {
					return "", err
				}
				return jstr(l, "id"), nil
			}},
	}
}
