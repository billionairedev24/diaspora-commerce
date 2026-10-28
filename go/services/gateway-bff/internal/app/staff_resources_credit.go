package app

import (
	"context"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AfroTransact credit and gift cards for staff (S320, S321, ADR 0006): grant or correct a customer's credit with a reason
// (a second approver above the limit set in Settings, Money), look up, issue, void and resend gift cards, the liability
// report for Finance, and the limits themselves. Credit only buys products and services, so there is deliberately no
// table, action or form here that pays credit out, refunds it to a card or moves it between customers.

func creditResources() []*staffResource {
	return []*staffResource{creditAdjustmentsResource(), giftCardsResource(), creditLiabilitiesResource(), moneySettingsResource()}
}

// ---------------------------------------------------------------- helpers

// customerByEmail finds the customer behind an email address through identity, so staff type what they know and never an id.
func customerByEmail(ctx context.Context, sc staffCtx, email string) (string, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	problem := func() error {
		return domain.ValidationProblem(map[string]string{"customerEmail": "No customer has that email address"})
	}
	if email == "" || !strings.Contains(email, "@") {
		return "", problem()
	}
	var page jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", url.Values{"q": {email}, "size": {"10"}}, &page); err != nil {
		return "", err
	}
	for _, u := range objs(page, "items") {
		if strings.EqualFold(jstr(u, "email"), email) && firstNonEmpty(jstr(u, "principalType"), "CUSTOMER") == "CUSTOMER" {
			return jstr(u, "id"), nil
		}
	}
	return "", problem()
}

// moneySettingsOf reads the limits in force; the zero value when payments cannot answer (forms then say nothing about them).
func moneySettingsOf(ctx context.Context, sc staffCtx) jobj {
	var s jobj
	if err := sc.get(ctx, sc.up.Payments, "/v1/money/settings", nil, &s); err != nil {
		return jobj{}
	}
	return s
}

func dollarText(minor float64) string { return moneyText(minor) }

// minorOf turns a dollar amount typed on a form into minor units.
func minorOf(v jobj, key string) int64 { return int64(math.Round(vn(v, key) * 100)) }

var creditSources = []domain.FieldOption{
	{Value: "STAFF", Label: "Goodwill or correction"},
	{Value: "PROMO", Label: "Promotion"},
}

var giftCardLocales = []domain.FieldOption{
	{Value: "en", Label: "English"}, {Value: "fr", Label: "French"}, {Value: "es", Label: "Spanish"}, {Value: "ar", Label: "Arabic"}, {Value: "yo", Label: "Yoruba"},
}

// ---------------------------------------------------------------- credit adjustments

func creditAdjustmentsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"500"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/credit/adjustments", v, &raw); err != nil {
			return nil, err
		}
		return jpage(raw).items, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "credit-adjustments", module: "payouts", title: "Credit adjustments", statusKey: "status", exportable: true,
		names: []nameRef{customerRef("customerId"), personRef("proposedBy", "Proposed by"), personRef("decidedBy", "Decided by")},
		columns: []domain.Column{col("customerName", "Customer", "strong", 18), col("reason", "Why", "text", 30), col("amountMinor", "Amount", "money", 11),
			col("source", "Kind", "text", 12), col("proposedByName", "Proposed by", "text", 13), col("decidedByName", "Decided by", "text", 13), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PROPOSED", "POSTED", "REJECTED"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			a, err := findIn(list, "id")(ctx, sc, id)
			if err != nil {
				return nil, err
			}
			// The customer's balance and latest credit lines next to the request, so the approver sees what it changes.
			var c jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/credit/customers/"+url.PathEscape(jstr(a, "customerId")), url.Values{"limit": {"20"}}, &c); err == nil {
				a["balanceMinor"] = num(c, "balance.balanceMinor")
				a["entries"] = c["entries"]
			}
			return a, nil
		},
		titleOf: func(o jobj) string {
			verb := "Credit added for "
			if num(o, "amountMinor") < 0 {
				verb = "Credit taken back from "
			}
			return verb + firstNonEmpty(jstr(o, "customerName"), "a customer")
		},
		subtitleOf: func(o jobj) string { return jstr(o, "reason") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "The change", Fields: []domain.DetailField{
					fieldOf("Customer", jstr(o, "customerName"), "text"),
					fieldOf("Amount", jstr(o, "amountMinor"), "money"),
					fieldOf("Kind", domain.Humanize(jstr(o, "source")), "text"),
					fieldOf("Why", jstr(o, "reason"), "text"),
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("Customer's credit now", jstr(o, "balanceMinor"), "money"),
				}},
				{Title: "Who decided", Fields: []domain.DetailField{
					fieldOf("Proposed by", jstr(o, "proposedByName"), "text"),
					fieldOf("Proposed at", jstr(o, "proposedAt"), "datetime"),
					fieldOf("Decided by", jstr(o, "decidedByName"), "text"),
					fieldOf("Decided at", jstr(o, "decidedAt"), "datetime"),
					fieldOf("Decision note", jstr(o, "decisionNote"), "text"),
				}},
			}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, e := range objs(o, "entries") {
				rows = append(rows, jobj{"id": jstr(e, "id"), "createdAt": jstr(e, "createdAt"), "what": domain.Humanize(jstr(e, "source")) + " · " + domain.Humanize(jstr(e, "kind")),
					"amountMinor": num(e, "amountMinor"), "balanceAfterMinor": num(e, "balanceAfterMinor"), "reason": jstr(e, "reason")})
			}
			return []domain.DetailTable{tableOf("The customer's latest credit lines", rows, []domain.Column{col("createdAt", "When", "datetime", 20), col("what", "What", "text", 26),
				col("amountMinor", "Amount", "money", 14), col("balanceAfterMinor", "Balance after", "money", 14), col("reason", "Why", "text", 26)}, "id")}
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
				confirm: "Post this change to the customer's credit now? It cannot be edited afterwards; a mistake is fixed with a new adjustment.",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/credit/adjustments/"+url.PathEscape(jstr(o, "id"))+"/approve", jobj{}, nil)
				}},
			{key: "reject", label: "Reject", tone: "danger", permission: "ledger:approve", when: inState("PROPOSED"),
				form: noteFormTitled("Why is it rejected?", "Reject", "The proposer sees this reason. The request stays on record as rejected and the customer's credit does not change."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/credit/adjustments/"+url.PathEscape(jstr(o, "id"))+"/reject", jobj{"note": vs(v, "note")}, nil)
				}},
		},
		create: &staffCreate{permission: "ledger:adjust",
			form: func(ctx context.Context, sc staffCtx) domain.Form {
				intro := "Adds to a customer's AfroTransact credit, or takes some back after a mistake (never below zero). Credit only buys products and services: it cannot be paid out or refunded to a card."
				if s := moneySettingsOf(ctx, sc); num(s, "creditApprovalLimitMinor") > 0 {
					intro += " Up to " + dollarText(num(s, "creditApprovalLimitMinor")) + " is posted at once; more needs a different person to approve it."
				}
				return domain.Form{Title: "Adjust a customer's credit", SubmitLabel: "Continue", Intro: intro, Fields: []domain.FormField{
					withMax(ff("customerEmail", "Customer's email address", "email", true), 254),
					withValue(withOptions(ff("change", "What to do", "select", true), []domain.FieldOption{{Value: "ADD", Label: "Add credit"}, {Value: "TAKE_BACK", Label: "Take credit back"}}), "ADD"),
					withRange(ff("amount", "Amount in dollars", "number", true), 100_000),
					withValue(withOptions(ff("source", "Kind", "select", true), creditSources), "STAFF"),
					withMax(ff("reason", "Why (at least 15 characters; the customer is not shown it)", "textarea", true), 500),
				}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				customer, err := customerByEmail(ctx, sc, vs(v, "customerEmail"))
				if err != nil {
					return "", err
				}
				amount := minorOf(v, "amount")
				if amount <= 0 {
					return "", domain.ValidationProblem(map[string]string{"amount": "Enter an amount above zero"})
				}
				if vs(v, "change") == "TAKE_BACK" {
					amount = -amount
				}
				var out jobj
				if err := sc.post(ctx, sc.up.Payments, "/v1/credit/adjustments", jobj{"customerId": customer, "amountMinor": amount, "source": vs(v, "source"), "reason": vs(v, "reason")}, &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

// ---------------------------------------------------------------- gift cards

func giftCardsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"200"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		if q.Q != "" {
			v.Set("q", q.Q)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/gift-cards", v, &raw); err != nil {
			return nil, err
		}
		rows := jpage(raw).items
		for _, g := range rows {
			g["recipient"] = firstNonEmpty(jstr(g, "recipientName"), jstr(g, "recipientEmail"))
			g["code"] = "•••• " + jstr(g, "codeLast4")
		}
		return rows, nil
	}
	return &staffResource{
		key: "gift-cards", module: "payouts", title: "Gift cards", statusKey: "status", exportable: true,
		searchPlaceholder: "Last four of the code, recipient, sender or buyer's email",
		names:             []nameRef{newRef(nameUser, "purchaserId", "Buyer", "Unknown customer"), personRef("issuedBy", "Issued by"), personRef("redeemedBy", "Redeemed by"), personRef("voidedBy", "Voided by")},
		columns: []domain.Column{col("code", "Code", "mono", 10), col("amountMinor", "Amount", "money", 10), col("recipient", "Recipient", "text", 20), col("origin", "Origin", "text", 10),
			col("purchaserName", "Bought by", "text", 16), col("sendAt", "Emailed on", "date", 11), col("createdAt", "Created", "datetime", 12), col("status", "Status", "status", 11)},
		facets: domain.StaffFacets("ACTIVE", "REDEEMED", "VOID", "PENDING_PAYMENT", "PAYMENT_FAILED"),
		list: func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
			return listAll(load)(ctx, sc, q)
		},
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var g jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/gift-cards/"+url.PathEscape(id), nil, &g); err != nil {
				return nil, err
			}
			g["recipient"] = firstNonEmpty(jstr(g, "recipientName"), jstr(g, "recipientEmail"))
			g["code"] = "•••• " + jstr(g, "codeLast4")
			return g, nil
		},
		titleOf: func(o jobj) string { return "Gift card " + jstr(o, "code") },
		subtitleOf: func(o jobj) string {
			return dollarText(num(o, "amountMinor")) + " · " + domain.Humanize(jstr(o, "status"))
		},
		sections: func(o jobj) []domain.DetailSection {
			origin := "Bought by a customer"
			if jstr(o, "origin") == "STAFF" {
				origin = "Issued by staff"
			}
			return []domain.DetailSection{
				{Title: "The card", Fields: []domain.DetailField{
					fieldOf("Amount", jstr(o, "amountMinor"), "money"),
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("Code ends in", jstr(o, "codeLast4"), "mono"),
					fieldOf("Origin", origin, "text"),
					fieldOf("Created", jstr(o, "createdAt"), "datetime"),
				}},
				{Title: "Recipient and delivery", Fields: []domain.DetailField{
					fieldOf("Recipient", jstr(o, "recipientName"), "text"),
					fieldOf("Recipient's email", jstr(o, "recipientEmail"), "text"),
					fieldOf("From", jstr(o, "senderName"), "text"),
					fieldOf("Message", jstr(o, "message"), "text"),
					fieldOf("Language", jstr(o, "locale"), "text"),
					fieldOf("Emailed on", jstr(o, "sendAt"), "datetime"),
					fieldOf("Delivery", domain.Humanize(jstr(o, "deliveryStatus")), "text"),
					fieldOf("Times emailed", jstr(o, "deliveryCount"), "text"),
					fieldOf("Last emailed", jstr(o, "sentAt"), "datetime"),
				}},
				{Title: "Who did what", Fields: []domain.DetailField{
					fieldOf("Bought by", jstr(o, "purchaserName"), "text"),
					fieldOf("Issued by", jstr(o, "issuedByName"), "text"),
					fieldOf("Why it was issued", jstr(o, "issueReason"), "text"),
					fieldOf("Redeemed by", jstr(o, "redeemedByName"), "text"),
					fieldOf("Redeemed at", jstr(o, "redeemedAt"), "datetime"),
					fieldOf("Voided by", jstr(o, "voidedByName"), "text"),
					fieldOf("Voided at", jstr(o, "voidedAt"), "datetime"),
					fieldOf("Why it was voided", jstr(o, "voidReason"), "text"),
					fieldOf("Refunded to the buyer's card", map[bool]string{true: "Yes", false: "No"}[boolean(o, "refundedToCard")], "text"),
				}},
			}
		},
		actions: []staffAction{
			{key: "void", label: "Void this gift card", tone: "danger", permission: "ledger:adjust", when: inState("ACTIVE", "PENDING_PAYMENT", "PAYMENT_FAILED"),
				confirm: "Void this gift card? Its code stops working. A card a customer bought is refunded to their card; one staff issued just ends. A redeemed card is credit and cannot be voided.",
				form:    noteFormReason("Why is it voided?", "Void", "The reason is kept on the card and in the audit log."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/gift-cards/"+url.PathEscape(jstr(o, "id"))+"/void", jobj{"reason": vs(v, "reason")}, nil)
				}},
			{key: "resend", label: "Email it again with a new code", tone: "neutral", permission: "ledger:adjust", when: inState("ACTIVE"),
				form: noteFormReason("Why is it sent again?", "Send again", "The old code stops working and the recipient gets a new one by email."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/gift-cards/"+url.PathEscape(jstr(o, "id"))+"/resend", jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
		create: &staffCreate{permission: "ledger:adjust",
			form: func(ctx context.Context, sc staffCtx) domain.Form {
				intro := "Goodwill or a promotion. The card is a liability from the moment it is issued and is emailed to the recipient on the day you choose."
				if s := moneySettingsOf(ctx, sc); num(s, "giftCardMaxMinor") > 0 {
					intro += " Amounts from " + dollarText(num(s, "giftCardMinMinor")) + " to " + dollarText(num(s, "giftCardMaxMinor")) + " (Settings, Money)."
				}
				return domain.Form{Title: "Issue a gift card", SubmitLabel: "Issue", Intro: intro, Fields: []domain.FormField{
					withRange(ff("amount", "Amount in dollars", "number", true), 100_000),
					withMax(ff("recipientEmail", "Recipient's email address", "email", true), 254),
					withMax(ff("recipientName", "Recipient's name", "text", false), 120),
					withMax(ff("message", "Message", "textarea", false), 500),
					ff("sendAt", "Email it on (empty = now)", "date", false),
					withValue(withOptions(ff("locale", "Language of the email", "select", true), giftCardLocales), "en"),
					withMax(ff("reason", "Why it is issued (at least 15 characters)", "textarea", true), 500),
				}}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				body := jobj{"amountMinor": minorOf(v, "amount"), "recipientEmail": vs(v, "recipientEmail"), "recipientName": vs(v, "recipientName"),
					"message": vs(v, "message"), "locale": vs(v, "locale"), "reason": vs(v, "reason")}
				if day := vs(v, "sendAt"); day != "" {
					body["sendAt"] = day + "T09:00:00Z"
				}
				var out jobj
				if err := sc.post(ctx, sc.up.Payments, "/v1/gift-cards", omitEmpty(body), &out); err != nil {
					return "", err
				}
				return jstr(out, "id"), nil
			}},
	}
}

// noteFormReason is a one-field form asking why, sent as "reason".
func noteFormReason(title, submit, intro string) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro,
			Fields: []domain.FormField{withMax(ff("reason", "Reason (at least 15 characters)", "textarea", true), 500)}}
	}
}

// ---------------------------------------------------------------- liability report

func creditLiabilitiesResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var l jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/credit/liabilities", nil, &l); err != nil {
			return nil, err
		}
		return liabilityRows(l), nil
	}
	list := listAll(load)
	return &staffResource{
		key: "credit-liabilities", module: "payouts", title: "Credit & gift card liability", exportable: true,
		columns: []domain.Column{col("item", "What", "strong", 38), col("amountMinor", "Amount", "money", 16), col("count", "Count", "number", 10), col("account", "Ledger account", "mono", 14), col("note", "What it means", "text", 22)},
		list:    list,
		get:     findIn(list, "id"),
		titleOf: func(o jobj) string { return jstr(o, "item") },
		subtitleOf: func(o jobj) string {
			return dollarText(num(o, "amountMinor"))
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "This line", Fields: []domain.DetailField{
				fieldOf("What", jstr(o, "item"), "text"),
				fieldOf("Amount", jstr(o, "amountMinor"), "money"),
				fieldOf("Count", jstr(o, "count"), "text"),
				fieldOf("Ledger account", jstr(o, "account"), "text"),
				fieldOf("What it means", jstr(o, "note"), "text"),
			}}}
		},
	}
}

// liabilityRows lays the payments report out as lines Finance can read and export: what is owed in credit and in unredeemed
// gift cards (apart), how the general ledger accounts compare, and the flows behind the credit.
func liabilityRows(l jobj) []jobj {
	row := func(id, item string, amount float64, count any, account, note string) jobj {
		return jobj{"id": id, "item": item, "amountMinor": amount, "count": count, "account": account, "note": note}
	}
	rows := []jobj{
		row("credit-outstanding", "Credit owed to customers", num(l, "creditOutstandingMinor"), int(num(l, "accountsWithCredit")), "2600",
			"Customer balances today. Credit never expires and cannot be paid out."),
		row("credit-open-orders", "Credit spent on orders not shipped yet", num(l, "creditOnOpenOrdersMinor"), nil, "2600",
			"Held in 2600 until each order ships, then it becomes revenue."),
		row("giftcards-outstanding", "Gift cards sold or issued, not yet redeemed", num(l, "giftCardsOutstandingMinor"), int(num(l, "giftCardsOutstandingCount")), "2650",
			"Owed until the recipient redeems the card into credit."),
		row("giftcards-redeemed", "Gift cards redeemed (all time)", num(l, "giftCardsRedeemedMinor"), nil, "", "Moved into customers' credit."),
		row("giftcards-voided", "Gift cards voided (all time)", num(l, "giftCardsVoidedMinor"), nil, "", "Ended by staff; purchased cards were refunded to the buyer's card."),
		row("ledger-2600", "Ledger: customer credit", num(l, "ledgerCreditMinor"), nil, "2600",
			"Customer balances plus credit spent on orders not shipped yet."),
		row("ledger-2650", "Ledger: gift card liability", num(l, "ledgerGiftCardsMinor"), nil, "2650", "Should equal the gift cards not yet redeemed."),
	}
	for _, k := range sortedKeys(mapOf(l, "flowsMinor")) {
		kind, source, _ := strings.Cut(k, "/")
		rows = append(rows, row("flow-"+strings.ToLower(strings.ReplaceAll(k, "/", "-")), domain.Humanize(source)+" · "+domain.Humanize(kind), num(l, "flowsMinor."+k), nil, "",
			"All entries of this kind and source in the credit ledger (signed)."))
	}
	return rows
}

func mapOf(o jobj, path string) map[string]any {
	m, _ := at(o, path).(map[string]any)
	return m
}

// ---------------------------------------------------------------- money settings

var termsLocales = []string{"en", "fr", "es", "ar", "yo"}

func moneySettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/money/settings", nil, &o); err != nil {
			return nil, err
		}
		o["id"] = "money"
		presets := []string{}
		if list, ok := o["giftCardPresets"].([]any); ok {
			for _, p := range list {
				if f, ok := p.(float64); ok {
					presets = append(presets, dollarText(f))
				}
			}
		}
		o["presetsText"] = strings.Join(presets, ", ")
		return o, nil
	}
	withHistory := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		o, err := get(ctx, sc, id)
		if err != nil {
			return nil, err
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/money/settings/history", url.Values{"limit": {"20"}}, &raw); err == nil {
			o["history"] = jpage(raw).items
		}
		return o, nil
	}
	return &staffResource{
		key: "money-settings", module: "payouts", title: "Credit & gift card limits",
		names: []nameRef{personRef("updatedBy", "Changed by"), personRef("changedBy", "Changed by").inList("history")},
		columns: []domain.Column{col("giftCardMinMinor", "Smallest gift card", "money", 18), col("giftCardMaxMinor", "Largest gift card", "money", 18),
			col("creditApprovalLimitMinor", "Second approver above", "money", 20), col("updatedByName", "Last changed by", "text", 24), col("updatedAt", "Changed", "datetime", 20)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "money")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        withHistory,
		titleOf:    func(jobj) string { return "Credit and gift card limits" },
		subtitleOf: func(o jobj) string { return "Version " + jstr(o, "version") + " · " + jstr(o, "updatedByName") },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			terms := domain.DetailSection{Title: "Gift card terms (shown on the buy page)", Fields: []domain.DetailField{}}
			for _, l := range termsLocales {
				terms.Fields = append(terms.Fields, fieldOf(strings.ToUpper(l), firstNonEmpty(jstr(o, "giftCardTerms."+l), "Built-in terms"), "text"))
			}
			return []domain.DetailSection{
				{Title: "Gift cards", Fields: []domain.DetailField{
					fieldOf("Smallest gift card", jstr(o, "giftCardMinMinor"), "money"),
					fieldOf("Largest gift card", jstr(o, "giftCardMaxMinor"), "money"),
					fieldOf("Amounts offered on the buy page", jstr(o, "presetsText"), "text"),
				}},
				{Title: "Credit granted by staff", Fields: []domain.DetailField{
					fieldOf("A second approver is needed above", jstr(o, "creditApprovalLimitMinor"), "money"),
				}},
				terms,
				{Title: "Last change", Fields: []domain.DetailField{
					fieldOf("Changed by", jstr(o, "updatedByName"), "text"),
					fieldOf("Changed at", jstr(o, "updatedAt"), "datetime"),
				}},
			}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, h := range objs(o, "history") {
				rows = append(rows, jobj{"id": jstr(h, "id"), "changedAt": jstr(h, "changedAt"), "changedByName": jstr(h, "changedByName"), "reason": jstr(h, "reason")})
			}
			return []domain.DetailTable{tableOf("Who changed it and why", rows, []domain.Column{col("changedAt", "When", "datetime", 20), col("changedByName", "Who", "text", 24), col("reason", "Why", "text", 56)}, "id")}
		},
		edit: &staffEdit{permission: "payments:configure",
			form: func(o jobj) domain.Form {
				fields := []domain.FormField{
					withValue(withRange(ff("giftCardMin", "Smallest gift card (dollars)", "number", true), 100_000), num(o, "giftCardMinMinor")/100),
					withValue(withRange(ff("giftCardMax", "Largest gift card (dollars)", "number", true), 100_000), num(o, "giftCardMaxMinor")/100),
					withValue(withPattern(withMax(ff("presets", "Amounts offered on the buy page (dollars, separated by commas; up to 8)", "text", true), 120),
						`^\s*\d+(\.\d{1,2})?(\s*,\s*\d+(\.\d{1,2})?){0,7}\s*$`, "For example 25, 50, 100, 200"), strings.ReplaceAll(jstr(o, "presetsText"), "$", "")),
					withValue(withRange(ff("creditApprovalLimit", "A second approver is needed for staff credit above (dollars)", "number", true), 100_000), num(o, "creditApprovalLimitMinor")/100),
				}
				changeKeys := []string{"giftCardMin", "giftCardMax", "presets", "creditApprovalLimit"}
				for _, l := range termsLocales {
					fields = append(fields, withValue(withMax(ff("terms."+l, "Gift card terms · "+strings.ToUpper(l)+" (empty = built-in terms)", "textarea", false), 4000), jstr(o, "giftCardTerms."+l)))
					changeKeys = append(changeKeys, "terms."+l)
				}
				fields = append(fields, withValue(withMax(ff("reason", "Why it changes (at least 15 characters)", "textarea", true), 500), ""))
				return domain.Form{Title: "Change the credit and gift card limits", SubmitLabel: "Save", ChangeKeys: changeKeys,
					Intro: "Takes effect at once for new gift cards and staff credit. Every change is kept with who made it and why.", Fields: fields}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				presets, err := presetsMinor(vs(v, "presets"))
				if err != nil {
					return err
				}
				terms := jobj{}
				for _, l := range termsLocales {
					if t := vs(v, "terms."+l); t != "" {
						terms[l] = t
					}
				}
				body := jobj{"giftCardMinMinor": minorOf(v, "giftCardMin"), "giftCardMaxMinor": minorOf(v, "giftCardMax"), "giftCardPresets": presets, "giftCardTerms": terms,
					"creditApprovalLimitMinor": minorOf(v, "creditApprovalLimit"), "reason": vs(v, "reason"), "version": int64(num(o, "version"))}
				return sc.call(ctx, sc.up.Payments, port.StaffCall{Method: http.MethodPut, Path: "/v1/money/settings", Body: body}, nil)
			}},
		actions: nil,
	}
}

// presetsMinor reads "25, 50, 100" as minor units.
func presetsMinor(text string) ([]int64, error) {
	out := []int64{}
	for _, part := range strings.Split(text, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		d, err := strconv.ParseFloat(part, 64)
		if err != nil || d <= 0 {
			return nil, domain.ValidationProblem(map[string]string{"presets": "Use dollar amounts separated by commas, like 25, 50, 100"})
		}
		out = append(out, int64(math.Round(d*100)))
	}
	if len(out) > 8 {
		return nil, domain.ValidationProblem(map[string]string{"presets": "Offer at most 8 amounts"})
	}
	return out, nil
}
