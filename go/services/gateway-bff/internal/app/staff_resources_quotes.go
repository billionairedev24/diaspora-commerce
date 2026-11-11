package app

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Delivery quotes (orders-service): a shopper outside the delivery zones asks for a price, customer care agrees an amount
// or declines, the shopper pays it at checkout (listing rules D20). The queue copies the application review workflow:
// who has each case, the SLA, escalation, names on the timeline. Everyone who may view sees every case; what they may do
// is the `can*` flags orders computes for the caller, so the console never re-derives the rules.
// docs/runbooks/delivery-quotes.md.

const (
	quotesPath         = "/v1/staff/delivery-quotes"
	quoteSettingsPath  = "/v1/staff/delivery-quote-settings"
	quoteSettingsID    = "current"
	quoteMine          = "MY"
	quoteUnassigned    = "UNASSIGNED"
	quoteWaiting       = "WAITING"
	quoteClosed        = "CLOSED"
	quoteEscalatedChip = "ESCALATED"
	quoteOverdueChip   = "OVERDUE"
)

func deliveryQuoteQueues() []domain.Facet {
	return []domain.Facet{{Value: quoteMine, Label: "My queue"}, {Value: quoteUnassigned, Label: "Unassigned"},
		{Value: quoteWaiting, Label: "Waiting on customer"}, {Value: quoteClosed, Label: "Closed"},
		{Value: quoteEscalatedChip, Label: "Escalated"}, {Value: quoteOverdueChip, Label: "Overdue"}, {Value: queueAll, Label: "All"}}
}

// quoteQueueParams maps a tab to orders' queue filters.
func quoteQueueParams(q staffQuery, v url.Values) {
	v.Del("status")
	switch q.Status {
	case "", queueAll:
		v.Set("queue", queueAll)
	case quoteEscalatedChip:
		v.Set("queue", queueAll)
		v.Set("escalated", "true")
	case quoteOverdueChip:
		v.Set("queue", queueAll)
		v.Set("overdue", "true")
	default:
		v.Set("queue", q.Status)
	}
	if q.Q != "" {
		v.Set("q", q.Q)
	}
}

// quoteStageLine is "Being quoted · with Amina Bello · waiting 3 h" (the status alone once it left customer care).
func quoteStageLine(o jobj) string {
	w := wf(o)
	if jstr(w, "stage") == "" {
		return jstr(o, "statusLabel")
	}
	return jstr(o, "statusLabel") + " · " + firstNonEmpty(strings.TrimPrefix(jstr(w, "holder"), ""), "any customer care agent") + " · waiting " + ago(num(w, "waitingSeconds"))
}

// quoteCells are the With / Waiting / SLA / Escalation / Amount columns of a row.
func quoteCells(o jobj) map[string]any {
	w := wf(o)
	out := map[string]any{"with": nil, "waiting": nil, "sla": nil, "escalation": nil, "amountMinor": nil}
	switch {
	case jstr(w, "stage") != "":
		out["with"] = firstNonEmpty(jstr(w, "assigneeName"), "Any agent")
		out["waiting"] = num(w, "waitingSeconds")
		out["sla"] = slaWord(w)
		if lvl := int(num(w, "escalationLevel")); lvl > 0 {
			out["escalation"] = fmt.Sprintf("Level %d", lvl)
		}
	case jstr(o, "status") == "READY":
		out["with"] = "The customer"
	}
	if _, ok := o["amountMinor"].(float64); ok {
		out["amountMinor"] = o["amountMinor"]
	}
	// The plain words the shopper sees too: "Being quoted", "Quote ready".
	out["status"] = jstr(o, "statusLabel")
	return out
}

func quoteSections(o jobj) []domain.DetailSection {
	w := wf(o)
	request := []domain.DetailField{
		fieldOf("Customer", jstr(o, "customerName"), "text"),
		fieldOf("Deliver to", jstr(o, "place"), "text"),
		fieldOf("Items", jstr(o, "itemCount"), "text"),
		fieldOf("How to reach them", domain.Humanize(strings.ToLower(jstr(o, "contact"))), "text"),
	}
	if n := jstr(o, "note"); n != "" {
		request = append(request, fieldOf("Their note", n, "text"))
	}
	out := []domain.DetailSection{{Title: "Request", Fields: request}}
	if lines := objs(o, "lines"); len(lines) > 0 {
		items := make([]domain.DetailField, 0, len(lines))
		for _, l := range lines {
			items = append(items, fieldOf(firstNonEmpty(jstr(l, "title"), jstr(l, "variantAtin")), fmt.Sprintf("%d × %s", int(num(l, "quantity")), domain.Money(int64(num(l, "unitPriceMinor")))), "text"))
		}
		out = append(out, domain.DetailSection{Title: "Cart", Fields: items})
	}
	who := []domain.DetailField{fieldOf("Where it is", quoteStageLine(o), "text")}
	if jstr(w, "stage") != "" {
		who = append(who, fieldOf("With", firstNonEmpty(jstr(w, "assigneeName"), "Nobody yet: "+jstr(w, "holder")), "text"))
		if jstr(w, "assigneeName") != "" {
			who = append(who, fieldOf("Assigned by", jstr(w, "assignedByName"), "text"), fieldOf("Assigned", jstr(w, "assignedAt"), "datetime"))
		}
		who = append(who, fieldOf("Waiting since", jstr(w, "stageEnteredAt"), "datetime"), fieldOf("Waiting", ago(num(w, "waitingSeconds")), "text"),
			fieldOf("Service level", slaWord(w)+" (due "+shortWhen(jstr(w, "slaDueAt"))+")", "text"))
	}
	out = append(out, domain.DetailSection{Title: "Who has it", Fields: who})
	if lvl := int(num(w, "escalationLevel")); lvl > 0 {
		out = append(out, domain.DetailSection{Title: "Escalation", Fields: []domain.DetailField{
			fieldOf("Level", fmt.Sprintf("%d", lvl), "text"), fieldOf("Escalated", jstr(w, "escalatedAt"), "datetime"),
			fieldOf("Sent to", strings.Join(strs(w, "escalatedTo"), ", "), "text"), fieldOf("Why", jstr(w, "escalationReason"), "text")}})
	}
	if _, ok := o["amountMinor"].(float64); ok {
		out = append(out, domain.DetailSection{Title: "Agreed amount", Fields: []domain.DetailField{
			fieldOf("Delivery charge", domain.Money(int64(num(o, "amountMinor"))), "text"), fieldOf("Agreed by", jstr(o, "agreedByName"), "text"),
			fieldOf("Agreed", jstr(o, "agreedAt"), "datetime"), fieldOf("Valid until", jstr(o, "expiresAt"), "datetime"),
			fieldOf("Why this amount", jstr(o, "agreedReason"), "text"), fieldOf("Used on an order", map[bool]string{true: "Yes", false: "Not yet"}[boolean(o, "usedOnOrder")], "text")}})
	}
	if jstr(o, "declinedByName") != "" {
		out = append(out, domain.DetailSection{Title: "Declined", Fields: []domain.DetailField{
			fieldOf("Declined by", jstr(o, "declinedByName"), "text"), fieldOf("Declined", jstr(o, "declinedAt"), "datetime"), fieldOf("Why", jstr(o, "declinedReason"), "text")}})
	}
	return out
}

// quoteTimeline lists the case's history with names (never ids), newest first.
func quoteTimeline(o jobj) []domain.ActivityEntry {
	rows := objs(o, "timeline")
	out := make([]domain.ActivityEntry, 0, len(rows))
	for i := len(rows) - 1; i >= 0; i-- {
		t := rows[i]
		text := domain.Humanize(strings.ToLower(jstr(t, "action")))
		if n := jstr(t, "note"); n != "" {
			text = n
		}
		out = append(out, domain.ActivityEntry{At: timeOf(t, "at"), Actor: firstNonEmpty(jstr(t, "actorName"), "Unknown user"), Text: text})
	}
	return out
}

func quoteFlag(flag, why string) func(staffCtx, jobj) string {
	return flagReason(flag, why)
}

// quoteRun posts one action to orders; the acting person's name goes on the timeline.
func quoteRun(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
	return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
		b := body(v)
		b["actorName"] = firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)
		return sc.call(ctx, sc.up.Orders, port.StaffCall{Method: http.MethodPost, Path: quotesPath + "/" + url.PathEscape(jstr(o, "id")) + path, Body: omitEmpty(b), IdempotencyKey: sc.idem}, nil)
	}
}

func quoteAssignment(action string) func(context.Context, staffCtx, jobj, jobj) error {
	return quoteRun("/assignment", func(v jobj) jobj {
		return jobj{"action": action, "assigneeId": vs(v, "assigneeId"), "note": vs(v, "note"), "reason": vs(v, "reason")}
	})
}

// quoteLimits reads the amount bounds from the settings so the form says what is allowed.
func quoteLimits(ctx context.Context, sc staffCtx) (lo, hi float64) {
	var s jobj
	if sc.get(ctx, sc.up.Orders, quoteSettingsPath, nil, &s) != nil {
		return 0, 0
	}
	return num(s, "minAmountMinor") / 100, num(s, "maxAmountMinor") / 100
}

func quoteActions() []staffAction {
	const (
		agree  = domain.PermQuotesAgree
		assign = domain.PermQuotesAssign
	)
	noteForm := func(title, submit, intro string) func(context.Context, staffCtx, jobj) *domain.Form {
		return func(context.Context, staffCtx, jobj) *domain.Form {
			return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro, Fields: []domain.FormField{withMax(ff("note", "Note (internal)", "textarea", false), 500)}}
		}
	}
	assignForm := func(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
		var people []jobj
		opts := []domain.FieldOption{}
		if sc.get(ctx, sc.up.Orders, quotesPath+"/"+url.PathEscape(jstr(o, "id"))+"/assignment/candidates", nil, &people) == nil {
			for _, p := range people {
				opts = append(opts, domain.FieldOption{Value: jstr(p, "id"), Label: firstNonEmpty(jstr(p, "name"), "Unknown user")})
			}
		}
		pick := withOptions(ff("assigneeId", "Assign to", "select", true), opts)
		pick.Help = "Only people who may agree delivery quotes are listed."
		return &domain.Form{Title: "Assign or take over", SubmitLabel: "Assign", Intro: "Gives the case to one person. The person is told, and the change is recorded with their name.",
			Fields: []domain.FormField{pick, withMax(ff("note", "Note (internal)", "textarea", false), 500)}}
	}
	escalateForm := func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: "Escalate now", SubmitLabel: "Escalate", Intro: "Raises the escalation one level now: the people set up for that level are told and can take over or reassign. The reason is kept on the timeline.",
			Fields: []domain.FormField{withMax(ff("reason", "Why are you escalating?", "textarea", true), 500)}}
	}
	agreeForm := func(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
		amount := ff("amount", "Delivery charge (US dollars)", "number", true)
		amount.Help = "What the shopper pays for delivery to " + jstr(o, "place") + ". Tax follows the items like any delivery charge."
		if lo, hi := quoteLimits(ctx, sc); hi > 0 {
			amount = numberField(amount, lo, hi)
			amount.Help += fmt.Sprintf(" Between %s and %s.", domain.Money(int64(math.Round(lo*100))), domain.Money(int64(math.Round(hi*100))))
		}
		reason := withMax(ff("reason", "Why this amount (kept in the audit trail)", "textarea", true), 500)
		return &domain.Form{Title: "Agree a delivery charge", SubmitLabel: "Send the quote",
			Intro:  "The shopper is told by email and can use the quote at checkout until it expires; it is for this cart and address only and can be used once.",
			Fields: []domain.FormField{amount, reason}}
	}
	declineForm := func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: "Decline to quote", SubmitLabel: "Decline", Intro: "The shopper is told we can't deliver there right now. They are not shown your reason; it is kept on the timeline.",
			Fields: []domain.FormField{withMax(ff("reason", "Why (kept in the audit trail)", "textarea", true), 500)}}
	}
	closedQuote := func(o jobj) bool {
		switch jstr(o, "status") {
		case "READY", "DECLINED", "EXPIRED", "USED":
			return true
		}
		return false
	}
	return []staffAction{
		{key: "claim", label: "Claim", tone: "primary", permission: agree, when: quoteFlag("Claim", "You can't take this case: it is with someone, or it is not waiting for customer care."), run: quoteAssignment("CLAIM")},
		{key: "assign", label: "Assign…", tone: "neutral", permission: assign, also: []string{agree}, form: assignForm, when: quoteFlag("Assign", "Only a supervisor, or the person an escalation went to, can assign."), run: quoteAssignment("ASSIGN")},
		{key: "release", label: "Release to the pool", tone: "neutral", permission: agree, also: []string{assign}, form: noteForm("Release to the pool", "Release", "Anyone in customer care will be able to claim it."), when: quoteFlag("Release", "Nobody holds this case, or it is not yours to release."), run: quoteAssignment("RELEASE")},
		{key: "escalate", label: "Escalate now", tone: "danger", permission: agree, also: []string{assign}, form: escalateForm, when: quoteFlag("Escalate", "Only the person who has the case or a supervisor can escalate, and it is not at the top level yet."), run: quoteAssignment("ESCALATE")},
		{key: "agree", label: "Agree a charge…", tone: "primary", permission: agree, form: agreeForm, when: quoteFlag("Decide", "Someone else has this case, or it is already answered."), settled: closedQuote,
			run: quoteRun("/agree", func(v jobj) jobj {
				return jobj{"amountMinor": int64(math.Round(vn(v, "amount") * 100)), "reason": vs(v, "reason")}
			})},
		{key: "decline", label: "Decline", tone: "danger", permission: agree, form: declineForm, when: quoteFlag("Decide", "Someone else has this case, or it is already answered."), settled: closedQuote,
			run: quoteRun("/decline", func(v jobj) jobj { return jobj{"reason": vs(v, "reason")} })},
	}
}

func deliveryQuotesResource() *staffResource {
	return &staffResource{
		key: "delivery-quotes", module: domain.DeliveryQuotesModule, title: "Delivery quotes", statusKey: "status", searchPlaceholder: "Reference, place or who has it",
		columns: []domain.Column{col("reference", "Reference", "mono", 12), col("customerName", "Customer", "strong", 14), col("place", "Deliver to", "text", 16), col("itemCount", "Items", "number", 5),
			col("with", "With", "text", 12), col("waiting", "Waiting", "duration", 8), col("sla", "SLA", "text", 8), col("escalation", "Escalation", "text", 9),
			col("amountMinor", "Charge", "money", 8), col("createdAt", "Asked", "datetime", 14), col("status", "Status", "status", 12)},
		facets: deliveryQuoteQueues(),
		list: func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
			// orders pages from 0; the console asks for the same.
			return listPaged(quotesPath, upOrders, func(q staffQuery, v url.Values) { quoteQueueParams(q, v) })(ctx, sc, q)
		},
		get:        getByPath(quotesPath+"/", upOrders),
		cells:      quoteCells,
		titleOf:    func(o jobj) string { return strings.TrimSpace(jstr(o, "reference") + " · " + jstr(o, "place")) },
		subtitleOf: func(o jobj) string { return jstr(o, "customerName") + " · " + quoteStageLine(o) },
		status:     func(o jobj) string { return jstr(o, "status") },
		sections:   quoteSections,
		activity:   func(_ context.Context, _ staffCtx, o jobj) []domain.ActivityEntry { return quoteTimeline(o) },
		actions:    quoteActions(),
	}
}

// ---------------------------------------------------------------- settings: SLA, escalation, amount bounds

func deliveryQuoteSettingsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Orders, quoteSettingsPath, nil, &o); err != nil {
			return nil, err
		}
		o["id"] = quoteSettingsID
		o["name"] = "Delivery quotes"
		return o, nil
	}
	mode := map[string]string{"NONE": "Off: cases wait in the pool", "ROUND_ROBIN": "Round robin", "LEAST_LOADED": "Least loaded"}
	target := func(o jobj, level, people string) string {
		var parts []string
		if role := jstr(o, level+".roleKey"); role != "" {
			parts = append(parts, "everyone with the role "+domain.Humanize(strings.TrimPrefix(role, "platform.")))
		}
		parts = append(parts, strs(o, people)...)
		if len(parts) == 0 {
			return "Nobody set"
		}
		return strings.Join(parts, ", ")
	}
	days := func(n float64) string {
		if n == 1 {
			return "1 business day"
		}
		return displayValue(n) + " business days"
	}
	return &staffResource{
		key: "delivery-quote-settings", module: domain.DeliveryQuotesModule, title: "Delivery quotes: SLA, escalation and amounts",
		columns: []domain.Column{col("name", "Settings", "strong", 24), col("sla", "Answer within", "text", 16), col("bounds", "Amount bounds", "text", 18),
			col("validity", "Valid for", "text", 10), col("updatedByName", "Last changed by", "text", 18), col("updatedAt", "Changed", "datetime", 14)},
		idOf: func(jobj) string { return quoteSettingsID },
		cells: func(o jobj) map[string]any {
			return map[string]any{"sla": days(num(o, "slaBusinessDays")), "validity": displayValue(num(o, "validityHours")) + " h",
				"bounds": domain.Money(int64(num(o, "minAmountMinor"))) + " to " + domain.Money(int64(num(o, "maxAmountMinor")))}
		},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := load(ctx, sc)
			if err != nil {
				return staffList{}, err
			}
			n := int64(1)
			return staffList{items: []jobj{o}, total: &n}, nil
		},
		get:     func(ctx context.Context, sc staffCtx, _ string) (jobj, error) { return load(ctx, sc) },
		titleOf: func(jobj) string { return "Delivery quotes: SLA, escalation and amounts" },
		subtitleOf: func(jobj) string {
			return "How long a request may wait, who is told when it is late, what amounts may be agreed and how long a quote stays valid"
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "Service level", Fields: []domain.DetailField{
					fieldOf("A request is answered within", days(num(o, "slaBusinessDays")), "text"),
					fieldOf("Due soon after", displayValue(num(o, "dueSoonPercent"))+"% of the time", "text"),
					fieldOf("Escalate a request nobody has taken after", displayValue(num(o, "unassignedAfterHours"))+" hours", "text"),
					fieldOf("Level 2 after the service level plus", displayValue(num(o, "level2AfterHours"))+" hours", "text")}},
				{Title: "Escalation", Fields: []domain.DetailField{
					fieldOf("Level 1", target(o, "level1", "level1People"), "text"), fieldOf("Level 2", target(o, "level2", "level2People"), "text")}},
				{Title: "Handing out requests", Fields: []domain.DetailField{fieldOf("Auto-assignment", mode[jstr(o, "autoAssign")], "text")}},
				{Title: "Amounts and validity", Fields: []domain.DetailField{
					fieldOf("Smallest amount", domain.Money(int64(num(o, "minAmountMinor"))), "text"), fieldOf("Largest amount", domain.Money(int64(num(o, "maxAmountMinor"))), "text"),
					fieldOf("A quote can be used for", displayValue(num(o, "validityHours"))+" hours", "text")}},
				{Title: "Limits on shoppers", Fields: []domain.DetailField{
					fieldOf("Requests per shopper per hour", displayValue(num(o, "requestsPerHour")), "text"),
					fieldOf("A repeat of the same cart and address within", displayValue(num(o, "duplicateWindowMinutes"))+" minutes returns the same request", "text")}},
			}
		},
		activity: func(_ context.Context, _ staffCtx, o jobj) []domain.ActivityEntry {
			out := []domain.ActivityEntry{}
			for _, c := range objs(o, "changes") {
				out = append(out, domain.ActivityEntry{At: timeOf(c, "changedAt"), Actor: firstNonEmpty(jstr(c, "actorName"), "Unknown user"), Text: "Changed: " + jstr(c, "reason")})
			}
			return out
		},
		edit: &staffEdit{permission: domain.PermQuotesAssign, form: quoteSettingsForm, save: saveQuoteSettings},
	}
}

func quoteSettingsForm(o jobj) domain.Form {
	person := func(level int) domain.FormField {
		f := withLookup(ff(fmt.Sprintf("level%dPerson", level), fmt.Sprintf("Level %d person", level), "text", false), lookupStaff)
		if ids, ok := at(o, fmt.Sprintf("level%d.userIds", level)).([]any); ok && len(ids) > 0 {
			f = withValue(f, ids[0])
		}
		return f
	}
	role := func(level int) domain.FormField {
		f := withValue(ff(fmt.Sprintf("level%dRole", level), fmt.Sprintf("Level %d role (everyone holding it)", level), "text", false), at(o, fmt.Sprintf("level%d.roleKey", level)))
		f.Help = "A role key such as platform.operations_manager. Leave empty to name a person only."
		return f
	}
	num := func(key, label, help string, lo, hi float64) domain.FormField {
		f := numberField(ff(key, label, "number", true), lo, hi)
		f.Help = help
		f.Value = at(o, key)
		return f
	}
	dollars := func(key, label string) domain.FormField {
		f := ff(key, label, "number", true)
		f.Value = num2(at(o, key+"Minor")) / 100
		return numberField(f, 0, 10000)
	}
	fields := []domain.FormField{
		num("slaBusinessDays", "A request is answered within, business days", "Monday to Friday, 1 to 10.", 1, 10),
		withValue(numberField(ff("dueSoonPercent", "Due soon after this percent of the time", "number", true), 1, 99), at(o, "dueSoonPercent")),
		num("unassignedAfterHours", "Escalate a request nobody has taken after, hours", "Counted from when it was asked.", 1, 720),
		num("level2AfterHours", "Level 2 this many hours after the service level ends", "", 1, 720),
		withValue(withOptions(ff("autoAssign", "Auto-assignment", "select", true), []domain.FieldOption{{Value: "NONE", Label: "Off: cases wait in the pool"}, {Value: "ROUND_ROBIN", Label: "Round robin"}, {Value: "LEAST_LOADED", Label: "Least loaded"}}), at(o, "autoAssign")),
		role(1), person(1), role(2), person(2),
		dollars("minAmount", "Smallest amount staff may agree (US dollars)"), dollars("maxAmount", "Largest amount staff may agree (US dollars)"),
		num("validityHours", "A quote can be used for, hours", "The shopper's pay link stops working after this.", 1, 720),
		num("requestsPerHour", "Requests per shopper per hour", "Stops one person flooding the queue.", 1, 100),
		num("duplicateWindowMinutes", "A repeat of the same cart and address returns the same request for, minutes", "", 1, 1440),
		reasonField(),
	}
	keys := []string{"slaBusinessDays", "dueSoonPercent", "unassignedAfterHours", "level2AfterHours", "autoAssign", "level1Role", "level1Person", "level2Role", "level2Person",
		"minAmount", "maxAmount", "validityHours", "requestsPerHour", "duplicateWindowMinutes"}
	return domain.Form{Title: "Delivery quotes: SLA, escalation and amounts", SubmitLabel: "Save",
		Intro:  "Level 1 comes before level 2. Every role and person must exist and be active. The change is recorded with your reason and what it was before.",
		Fields: fields, ChangeKeys: keys}
}

func num2(v any) float64 {
	f, _ := v.(float64)
	return f
}

func saveQuoteSettings(ctx context.Context, sc staffCtx, _ jobj, v jobj, _ string) error {
	target := func(level int) jobj {
		t := jobj{"userIds": []string{}}
		if role := strings.TrimSpace(vs(v, fmt.Sprintf("level%dRole", level))); role != "" {
			t["roleKey"] = role
		}
		if p := strings.TrimSpace(vs(v, fmt.Sprintf("level%dPerson", level))); p != "" {
			t["userIds"] = []string{p}
		}
		return t
	}
	body := jobj{"slaBusinessDays": int64(vn(v, "slaBusinessDays")), "dueSoonPercent": int64(vn(v, "dueSoonPercent")), "unassignedAfterHours": int64(vn(v, "unassignedAfterHours")),
		"level2AfterHours": int64(vn(v, "level2AfterHours")), "autoAssign": vs(v, "autoAssign"), "level1": target(1), "level2": target(2),
		"minAmountMinor": int64(math.Round(vn(v, "minAmount") * 100)), "maxAmountMinor": int64(math.Round(vn(v, "maxAmount") * 100)),
		"validityHours": int64(vn(v, "validityHours")), "requestsPerHour": int64(vn(v, "requestsPerHour")), "duplicateWindowMinutes": int64(vn(v, "duplicateWindowMinutes")),
		"reason": vs(v, "reason"), "actorName": firstNonEmpty(strings.TrimSpace(sc.me.FirstName+" "+sc.me.LastName), sc.me.Email)}
	return sc.call(ctx, sc.up.Orders, port.StaffCall{Method: http.MethodPut, Path: quoteSettingsPath, Body: body}, nil)
}
