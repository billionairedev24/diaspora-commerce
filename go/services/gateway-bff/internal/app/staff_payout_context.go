package app

import (
	"context"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// What Finance needs to decide on a payout request: who the seller is, who asked, whether Stripe can pay them and what
// their balance looks like. Every lookup is best effort: a service that does not answer leaves its fields out and the
// "Before you approve" checks say what could not be confirmed.

// sellerOf reads the organisation's name, legal name, country and status from the seller directory.
func sellerOf(ctx context.Context, sc staffCtx, orgID string) jobj {
	var m jobj
	if orgID == "" || sc.get(ctx, sc.up.Seller, "/v1/orgs/"+url.PathEscape(orgID)+"/mirror", nil, &m) != nil {
		return nil
	}
	return m
}

// addSellerNames puts the seller's name on every row of the list (one lookup per organisation).
func addSellerNames(ctx context.Context, sc staffCtx, rows []jobj) {
	names := map[string]string{}
	for _, r := range rows {
		org := jstr(r, "orgId")
		name, seen := names[org]
		if !seen {
			m := sellerOf(ctx, sc, org)
			name = firstNonEmpty(jstr(m, "displayName"), jstr(m, "legalName"))
			names[org] = name
		}
		r["sellerName"] = firstNonEmpty(name, org)
	}
}

// enrichPayout adds the seller, requester, Stripe account and balance to one payout request.
func enrichPayout(ctx context.Context, sc staffCtx, o jobj) {
	org := jstr(o, "orgId")
	if m := sellerOf(ctx, sc, org); m != nil {
		o["sellerName"] = firstNonEmpty(jstr(m, "displayName"), jstr(m, "legalName"), org)
		o["sellerLegalName"] = jstr(m, "legalName")
		o["sellerCountry"] = jstr(m, "country")
		o["sellerStatus"] = jstr(m, "status")
		o["sellerKind"] = domain.Humanize(jstr(m, "kind"))
	} else {
		o["sellerName"] = org
	}
	if who := jstr(o, "requestedBy"); who != "" {
		var u jobj
		if sc.get(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(who), nil, &u) == nil {
			o["requestedByName"] = jstr(u, "name")
			o["requestedByEmail"] = jstr(u, "email")
		}
	}
	var acct jobj
	if sc.get(ctx, sc.up.Payments, "/v1/connected-accounts/"+url.PathEscape(org), nil, &acct) == nil {
		o["stripeAccountId"] = jstr(acct, "stripeAccountId")
	}
	if id := jstr(o, "stripeAccountId"); id != "" {
		var h jobj
		if sc.get(ctx, sc.up.Seller, "/v1/compliance/accounts/"+url.PathEscape(id), nil, &h) == nil {
			o["stripeStatus"] = jstr(h, "status")
			o["chargesEnabled"] = boolean(h, "chargesEnabled")
			o["payoutsEnabled"] = boolean(h, "payoutsEnabled")
			o["payoutsPaused"] = boolean(h, "payoutsPaused")
			o["complianceKnown"] = true
			if bank, ok := h["payoutBank"].(map[string]any); ok {
				o["bankName"] = jstr(bank, "bankName")
				o["bankLast4"] = jstr(bank, "last4")
				o["bankStatus"] = jstr(bank, "status")
			}
		}
	}
	if id := jstr(o, "stripePayoutId"); id != "" {
		var l jobj
		if sc.get(ctx, sc.up.Payments, "/v1/stripe/lookup", url.Values{"ref": {id}, "orgId": {org}}, &l) == nil {
			o["stripe"] = l
		}
	}
	var bal jobj
	if sc.get(ctx, sc.up.Payments, "/v1/payouts/balance", url.Values{"orgId": {org}}, &bal) == nil {
		o["balance"] = bal
	}
	var raw any
	if sc.get(ctx, sc.up.Payments, "/v1/payouts/requests", url.Values{"orgId": {org}, "limit": {"10"}}, &raw) == nil {
		o["sellerPayouts"] = anySlice(jpage(raw).items)
	}
}

func anySlice(items []jobj) []any {
	out := make([]any, 0, len(items))
	for _, it := range items {
		out = append(out, map[string]any(it))
	}
	return out
}

func fieldOf(label, value, kind string) domain.DetailField {
	if strings.TrimSpace(value) == "" {
		value = "—"
	}
	return domain.DetailField{Label: label, Value: value, Kind: kind}
}

// payoutChecks are the things Finance confirms before paying: each reads "Yes", "No — why" or "Not confirmed".
func payoutChecks(o jobj) []domain.DetailField {
	out := []domain.DetailField{}
	if jstr(o, "stripeAccountId") == "" {
		out = append(out, fieldOf("Stripe account on file", "No — payments has no connected account for this seller; approving will fail", "text"))
		return out
	}
	out = append(out, fieldOf("Stripe account on file", "Yes ("+jstr(o, "stripeAccountId")+")", "text"))
	if !boolean(o, "complianceKnown") {
		return append(out, fieldOf("Stripe verification", "Not confirmed — could not read the account's status", "text"))
	}
	verified := strings.EqualFold(jstr(o, "stripeStatus"), "VERIFIED")
	out = append(out, fieldOf("Stripe verified", map[bool]string{true: "Yes", false: "No — status " + domain.Humanize(jstr(o, "stripeStatus"))}[verified], "text"))
	out = append(out, fieldOf("Stripe can pay out", map[bool]string{true: "Yes", false: "No — payouts are not enabled on the account"}[boolean(o, "payoutsEnabled")], "text"))
	out = append(out, fieldOf("No compliance hold", map[bool]string{true: "Yes", false: "No — payouts are paused on this seller"}[!boolean(o, "payoutsPaused")], "text"))
	if jstr(o, "bankLast4") != "" {
		out = append(out, fieldOf("Bank account", strings.TrimSpace(jstr(o, "bankName")+" ····"+jstr(o, "bankLast4")+" ("+domain.Humanize(jstr(o, "bankStatus"))+")"), "text"))
	} else {
		out = append(out, fieldOf("Bank account", "Not confirmed — no bank on the Stripe account", "text"))
	}
	return out
}

func payoutSections(o jobj) []domain.DetailSection {
	secs := []domain.DetailSection{}
	secs = append(secs, domain.DetailSection{Title: "Payout", Fields: []domain.DetailField{
		fieldOf("Amount", jstr(o, "amountMinor"), "money"),
		fieldOf("Status", jstr(o, "status"), "status"),
		fieldOf("Requested", jstr(o, "requestedAt"), "datetime"),
		fieldOf("Decided", jstr(o, "decidedAt"), "datetime"),
		fieldOf("Decided by", jstr(o, "decidedBy"), "mono"),
		fieldOf("Hold reason", jstr(o, "holdReason"), "text"),
		fieldOf("Failure", jstr(o, "failureMessage"), "text"),
		fieldOf("Stripe payout", jstr(o, "stripePayoutId"), "mono"),
		fieldOf("Request id", jstr(o, "id"), "mono"),
	}})
	secs = append(secs, domain.DetailSection{Title: "Seller", Fields: []domain.DetailField{
		fieldOf("Business", jstr(o, "sellerName"), "text"),
		fieldOf("Legal name", jstr(o, "sellerLegalName"), "text"),
		fieldOf("Type", jstr(o, "sellerKind"), "text"),
		fieldOf("Country", jstr(o, "sellerCountry"), "text"),
		fieldOf("Account status", domain.Humanize(jstr(o, "sellerStatus")), "text"),
		fieldOf("Organisation id", jstr(o, "orgId"), "mono"),
		fieldOf("Requested by", firstNonEmpty(jstr(o, "requestedByName"), jstr(o, "requestedBy")), "text"),
		fieldOf("Requester email", jstr(o, "requestedByEmail"), "text"),
	}})
	secs = append(secs, domain.DetailSection{Title: "Before you approve", Fields: payoutChecks(o)})
	if st, ok := o["stripe"].(map[string]any); ok {
		sec := stripeSection("Live from Stripe", jobj(st))
		if note := stripeMismatch(o, jobj(st)); note != "" {
			sec.Fields = append([]domain.DetailField{fieldOf("Needs attention", note, "text")}, sec.Fields...)
		}
		if b, ok := st["balance"].(map[string]any); ok {
			sec.Fields = append(sec.Fields,
				fieldOf("Seller's Stripe balance, available", jstr(b, "availableMinor"), "money"),
				fieldOf("Seller's Stripe balance, pending", jstr(b, "pendingMinor"), "money"))
		}
		secs = append(secs, sec)
	}
	if b, ok := o["balance"].(map[string]any); ok {
		secs = append(secs, domain.DetailSection{Title: "Seller's balance now", Fields: []domain.DetailField{
			fieldOf("Available to request", jstr(b, "availableMinor"), "money"),
			fieldOf("Still on hold (not yet delivered or released)", jstr(b, "onHoldMinor"), "money"),
			fieldOf("Requested or on the way", jstr(b, "requestedOrInTransitMinor"), "money"),
			fieldOf("Paid out so far", jstr(b, "paidMinor"), "money"),
			fieldOf("Shipping label charges owed", jstr(b, "labelChargesMinor"), "money"),
			fieldOf("Plan fees owed", jstr(b, "planFeesMinor"), "money"),
		}})
	}
	return secs
}

func payoutTables(o jobj) []domain.DetailTable {
	tables := []domain.DetailTable{}
	if rows := objs(o, "sellerPayouts"); len(rows) > 0 {
		tables = append(tables, tableOf("This seller's recent payouts", rows, []domain.Column{
			col("requestedAt", "Requested", "date", 22), col("amountMinor", "Amount", "money", 22), col("status", "Status", "status", 22), col("stripePayoutId", "Stripe payout", "mono", 34),
		}, "id"))
	}
	if b, ok := o["balance"].(map[string]any); ok {
		if held := objs(b, "onHold"); len(held) > 0 {
			tables = append(tables, tableOf("Orders still on hold", held, []domain.Column{
				col("orderId", "Order", "mono", 50), col("amountMinor", "Amount", "money", 25), col("releaseAt", "Releases", "date", 25),
			}, "orderId"))
		}
	}
	return tables
}

// stripeMismatch says when Stripe and our record of the payout disagree and what to do about it.
func stripeMismatch(o, lookup jobj) string {
	obj, ok := lookup["object"].(map[string]any)
	if !ok {
		return ""
	}
	at := strings.ToLower(jstr(obj, "status"))
	ours := strings.ToUpper(jstr(o, "status"))
	switch {
	case ours == "IN_TRANSIT" && at == "paid":
		return "Stripe says this payout was paid, but it still shows on the way here. Use Check with Stripe to close it."
	case ours == "IN_TRANSIT" && (at == "failed" || at == "canceled"):
		return "Stripe says this payout " + at + ", but it still shows on the way here. Use Check with Stripe to record the failure and give the seller the money back."
	case ours == "PAID" && at != "" && at != "paid":
		return "We show this payout as paid, but Stripe says " + at + ". Check the payout in the Stripe dashboard."
	}
	return ""
}
