package app

import (
	"context"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Refund recoveries (payments, docs/runbooks/finance-reconciliation.md "Refunds Stripe would not take back"): when a
// refund debits a seller whose net was already paid out, Stripe refuses to reverse the transfer. The customer's refund
// completes and the amount becomes a recovery the seller owes the platform: netted against the seller's next transfers,
// or closed by Finance. Closing posts to the ledger, so it needs four eyes like a ledger adjustment: one person proposes
// ("Mark recovered", "Write off"), a different one approves or rejects.

// recoveryRows flattens the pending proposal so tables and sheets read it without digging.
func recoveryRows(items []jobj) []jobj {
	for _, r := range items {
		p, _ := r["pending"].(map[string]any)
		r["waitingFor"], r["proposedBy"] = "", ""
		if p != nil {
			r["proposedBy"] = jstr(p, "proposedBy")
			r["waitingFor"] = "Approval of " + pendingText(jstr(p, "resolution"))
		}
	}
	return items
}

func pendingText(resolution string) string {
	if resolution == "WRITE_OFF" {
		return "a write-off"
	}
	return "money received"
}

// recoveryProposable is the reason a resolution cannot be proposed now, or "".
func recoveryProposable(_ staffCtx, o jobj) string {
	if r := stateReason(jstr(o, "status"), "OPEN"); r != "" {
		return r
	}
	if jstr(o, "waitingFor") != "" {
		return "A resolution already waits for approval."
	}
	return ""
}

func recoveryProposalForm(title, submit, intro string, withReference bool) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
		fields := []domain.FormField{}
		if withReference {
			fields = append(fields, withMax(ff("reference", "Bank reference of the money received", "text", true), 120))
		}
		fields = append(fields, withMax(ff("reason", "Why (at least 15 characters)", "textarea", true), 500))
		return &domain.Form{Title: title, SubmitLabel: submit, Fields: fields,
			Intro: intro + " It covers the " + moneyText(num(o, "outstandingMinor")) + " still owed. Nothing is posted until a different person approves it."}
	}
}

func recoveriesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"500"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/transfer-recoveries", v, &raw); err != nil {
			return nil, err
		}
		return recoveryRows(jpage(raw).items), nil
	}
	propose := func(resolution string) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			body := omitEmpty(jobj{"resolution": resolution, "reason": vs(v, "reason"), "reference": vs(v, "reference")})
			return sc.post(ctx, sc.up.Payments, "/v1/transfer-recoveries/"+url.PathEscape(jstr(o, "id"))+"/propose", body, nil)
		}
	}
	return &staffResource{
		key:    "recoveries",
		names:  []nameRef{sellerRef("orgId"), orderRef("orderId"), personRef("proposedBy", "Proposed by"), personRef("decidedBy", "Decided by")},
		module: "payouts", title: "Refund recoveries", statusKey: "status", exportable: true,
		columns: []domain.Column{col("orgName", "Seller", "strong", 18), col("orderNumber", "Order", "mono", 12), col("amountMinor", "Stripe refused", "money", 11),
			col("outstandingMinor", "Still owed", "money", 11), col("stripeCode", "Stripe said", "text", 14), col("waitingFor", "Waiting for", "text", 14),
			col("createdAt", "Opened", "date", 10), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("OPEN", "RECOVERED", "WRITTEN_OFF"),
		list:   listAll(load),
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var r jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/transfer-recoveries/"+url.PathEscape(id), nil, &r); err != nil {
				return nil, err
			}
			recoveryRows([]jobj{r})
			return r, nil
		},
		titleOf: func(o jobj) string { return "Refund recovery of " + moneyText(num(o, "outstandingMinor")) },
		subtitleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "orgName"), "Unknown seller") + " · order " + firstNonEmpty(jstr(o, "orderNumber"), "unknown")
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "What happened", Fields: []domain.DetailField{
					fieldOf("Seller", jstr(o, "orgName"), "text"),
					fieldOf("Order", jstr(o, "orderNumber"), "mono"),
					fieldOf("Stripe refused to take back", jstr(o, "amountMinor"), "money"),
					fieldOf("Stripe said", jstr(o, "stripeCode"), "text"),
					fieldOf("Stripe's message", jstr(o, "reason"), "text"),
					fieldOf("Transfer", jstr(o, "stripeTransferId"), "mono"),
					fieldOf("Opened", jstr(o, "createdAt"), "datetime"),
				}},
				{Title: "Where it stands", Fields: []domain.DetailField{
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("Still owed", jstr(o, "outstandingMinor"), "money"),
					fieldOf("Recovered from later transfers", jstr(o, "recoveredMinor"), "money"),
					fieldOf("Closed by Finance", jstr(o, "resolvedMinor"), "money"),
					fieldOf("Waiting for", jstr(o, "waitingFor"), "text"),
				}},
				{Title: "Who decided", Fields: []domain.DetailField{
					fieldOf("Proposed by", jstr(o, "proposedByName"), "text"),
					fieldOf("Decided by", jstr(o, "decidedByName"), "text"),
					fieldOf("Decided at", jstr(o, "decidedAt"), "datetime"),
					fieldOf("Decision note", jstr(o, "decisionNote"), "text"),
					fieldOf("Bank reference", jstr(o, "resolutionRef"), "mono"),
				}},
			}
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "The order", Href: "/orders?open=" + url.QueryEscape(firstNonEmpty(jstr(o, "orderNumber"), jstr(o, "orderId")))}}
		},
		actions: []staffAction{
			{key: "mark-recovered", label: "Mark recovered", tone: "primary", permission: "ledger:adjust", when: recoveryProposable,
				form: recoveryProposalForm("The seller paid the platform back", "Propose", "Use this when the seller paid outside Stripe (a bank transfer).", true),
				run:  propose("RECOVERED")},
			{key: "write-off", label: "Write off", tone: "danger", permission: "ledger:adjust", when: recoveryProposable,
				form: recoveryProposalForm("Write the recovery off", "Propose", "The platform absorbs the loss (account 5300) and never takes it from the seller's earnings.", false),
				run:  propose("WRITE_OFF")},
			{key: "approve", label: "Approve and post", tone: "primary", permission: "ledger:approve", when: func(sc staffCtx, o jobj) string {
				if r := stateReason(jstr(o, "status"), "OPEN"); r != "" {
					return r
				}
				if jstr(o, "waitingFor") == "" {
					return "Nothing waits for approval."
				}
				if jstr(o, "proposedBy") == sc.me.ID {
					return "You proposed this, so a different person must approve it."
				}
				return ""
			},
				confirm: "Post this to the ledger now? It cannot be edited afterwards.",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/transfer-recoveries/"+url.PathEscape(jstr(o, "id"))+"/approve", jobj{}, nil)
				}},
			{key: "reject", label: "Reject", tone: "danger", permission: "ledger:approve", when: func(_ staffCtx, o jobj) string {
				if r := stateReason(jstr(o, "status"), "OPEN"); r != "" {
					return r
				}
				if jstr(o, "waitingFor") == "" {
					return "Nothing waits for approval."
				}
				return ""
			},
				form: noteFormTitled("Why is it rejected?", "Reject", "The proposer sees this reason. The recovery stays open."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/transfer-recoveries/"+url.PathEscape(jstr(o, "id"))+"/reject", jobj{"note": vs(v, "note")}, nil)
				}},
		},
	}
}
