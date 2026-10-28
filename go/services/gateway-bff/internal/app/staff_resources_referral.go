package app

import (
	"context"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The referral program for staff (S322, ADR 0006): the referrals with their status, rewards and cost, the review queue
// of referrals held by an abuse check (approve or reject, with a reason), and the program's settings, each side's
// reward separately, with a reason on every change. AfroTransact pays: rewards are credit (never cash) or membership
// days. Customers are shown by name, never by id.

func referralResources() []*staffResource {
	return []*staffResource{referralsResource(), referralSettingsResource()}
}

var rewardTypes = []domain.FieldOption{
	{Value: "CREDIT", Label: "AfroTransact credit"},
	{Value: "MEMBERSHIP_DAYS", Label: "Free membership days"},
}

var qualifyingEvents = []domain.FieldOption{
	{Value: "FIRST_ORDER_DELIVERED", Label: "The friend's first order is delivered"},
}

// rewardText words a reward for a table cell: "$10.00 credit" or "14 membership days".
func rewardText(r jobj) string {
	if jstr(r, "type") == "MEMBERSHIP_DAYS" {
		return jstr(r, "membershipDays") + " membership days"
	}
	return dollarText(num(r, "creditMinor")) + " credit"
}

// ---------------------------------------------------------------- referrals

func referralsResource() *staffResource {
	enrich := func(o jobj) jobj {
		parts := []string{}
		for _, r := range objs(o, "rewards") {
			side := "Referrer"
			if jstr(r, "side") == "REFERRED" {
				side = "Friend"
			}
			state := ""
			if st := jstr(r, "status"); st != "GRANTED" {
				state = " (" + strings.ToLower(domain.Humanize(st)) + ")"
			}
			parts = append(parts, side+" "+rewardText(mapOrEmpty(r, "terms"))+state)
		}
		o["rewardsText"] = strings.Join(parts, " · ")
		flags := []string{}
		if list, ok := o["flags"].([]any); ok {
			for _, f := range list {
				if s, ok := f.(string); ok {
					flags = append(flags, domain.Humanize(s))
				}
			}
		}
		o["flagsText"] = strings.Join(flags, ", ")
		return o
	}
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"500"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/referrals", v, &raw); err != nil {
			return nil, err
		}
		rows := jpage(raw).items
		for _, o := range rows {
			enrich(o)
		}
		return rows, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "referrals", module: "payouts", title: "Referrals", statusKey: "status", exportable: true,
		names: []nameRef{newRef(nameUser, "referrerId", "Referrer", "Unknown customer"), newRef(nameUser, "referredId", "Friend", "Unknown customer"),
			personRef("decidedBy", "Decided by")},
		columns: []domain.Column{col("referrerName", "Referrer", "strong", 17), col("referredName", "Friend", "text", 17), col("status", "Status", "status", 12),
			col("flagsText", "Held for", "text", 14), col("rewardsText", "Rewards", "text", 20), col("costMinor", "Paid by AfroTransact", "money", 10),
			col("signedUpAt", "Signed up", "date", 10)},
		facets: domain.StaffFacets("IN_REVIEW", "PENDING", "WAITING", "PARTIALLY_REWARDED", "REWARDED", "REJECTED", "EXPIRED"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			var o jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/referrals/"+url.PathEscape(id), nil, &o); err != nil {
				return nil, err
			}
			return enrich(o), nil
		},
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "referrerName"), "A customer") + " referred " + firstNonEmpty(jstr(o, "referredName"), "a friend")
		},
		subtitleOf: func(o jobj) string {
			return domain.Humanize(jstr(o, "status")) + " · " + dollarText(num(o, "costMinor")) + " paid"
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "The referral", Fields: []domain.DetailField{
					fieldOf("Referrer", jstr(o, "referrerName"), "text"),
					fieldOf("Friend", jstr(o, "referredName"), "text"),
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("Signed up", jstr(o, "signedUpAt"), "datetime"),
					fieldOf("Can still qualify until", jstr(o, "expiresAt"), "datetime"),
					fieldOf("Paid by AfroTransact", jstr(o, "costMinor"), "money"),
				}},
				{Title: "The qualifying order", Fields: []domain.DetailField{
					fieldOf("Order", jstr(o, "qualifyingOrderId"), "mono"),
					fieldOf("Value delivered", jstr(o, "qualifyingMinor"), "money"),
					fieldOf("Delivered", jstr(o, "deliveredAt"), "datetime"),
					fieldOf("Rewards due", jstr(o, "eligibleAt"), "datetime"),
				}},
				{Title: "Checks and decision", Fields: []domain.DetailField{
					fieldOf("Held because", firstNonEmpty(jstr(o, "flagsText"), "Nothing found"), "text"),
					fieldOf("Decision", domain.Humanize(jstr(o, "decision")), "text"),
					fieldOf("Why", jstr(o, "decisionReason"), "text"),
					fieldOf("Decided by", jstr(o, "decidedByName"), "text"),
					fieldOf("Decided at", jstr(o, "decidedAt"), "datetime"),
				}},
			}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, r := range objs(o, "rewards") {
				who := firstNonEmpty(jstr(o, "referrerName"), "Referrer")
				if jstr(r, "side") == "REFERRED" {
					who = firstNonEmpty(jstr(o, "referredName"), "Friend")
				}
				rows = append(rows, jobj{"id": jstr(r, "side"), "who": who, "side": domain.Humanize(jstr(r, "side")),
					"reward": rewardText(mapOrEmpty(r, "terms")), "status": jstr(r, "status"), "grantedAt": jstr(r, "grantedAt")})
			}
			return []domain.DetailTable{tableOf("Rewards", rows, []domain.Column{col("who", "Who", "strong", 24), col("side", "As", "text", 16),
				col("reward", "Reward", "text", 24), col("status", "Status", "status", 16), col("grantedAt", "Granted", "datetime", 20)}, "id")}
		},
		actions: []staffAction{
			{key: "approve", label: "Approve", tone: "primary", permission: "ledger:adjust", when: inState("IN_REVIEW"),
				confirm: "The checks found something shared between the referrer and the friend. Approving says it is fine: the rewards are then granted after the return window.",
				form:    referralReasonForm("Why is it fine?", "Approve", "The reason is kept with your name. If the return window has passed, both rewards are granted now."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/referrals/"+url.PathEscape(jstr(o, "id"))+"/approve", jobj{"reason": vs(v, "reason")}, nil)
				}},
			{key: "reject", label: "Reject", tone: "danger", permission: "ledger:adjust", when: inState("IN_REVIEW", "WAITING"),
				form: referralReasonForm("Why is it rejected?", "Reject", "No reward is granted to either side. The reason is kept with your name."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Payments, "/v1/referrals/"+url.PathEscape(jstr(o, "id"))+"/reject", jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
	}
}

func referralReasonForm(title, submit, intro string) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: title, SubmitLabel: submit, Intro: intro,
			Fields: []domain.FormField{withMax(ff("reason", "Reason (at least 15 characters)", "textarea", true), 500)}}
	}
}

func mapOrEmpty(o jobj, key string) jobj {
	if m, ok := o[key].(map[string]any); ok {
		return m
	}
	return jobj{}
}

// ---------------------------------------------------------------- settings

func referralSettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/referral-settings", nil, &o); err != nil {
			return nil, err
		}
		o["id"] = "referral"
		o["referrerText"] = rewardText(mapOrEmpty(o, "referrer"))
		o["referredText"] = rewardText(mapOrEmpty(o, "referred"))
		o["stateText"] = "Off"
		if boolean(o, "enabled") {
			o["stateText"] = "On"
		}
		return o, nil
	}
	withExtras := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		o, err := get(ctx, sc, id)
		if err != nil {
			return nil, err
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/referral-settings/history", url.Values{"limit": {"20"}}, &raw); err == nil {
			o["history"] = jpage(raw).items
		}
		var sum jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/referrals/summary", nil, &sum); err == nil {
			o["costMinor"], o["pendingMinor"], o["membershipDaysGranted"] = num(sum, "costMinor"), num(sum, "pendingMinor"), num(sum, "membershipDays")
			counts := []string{}
			by := mapOf(sum, "byStatus")
			for _, k := range sortedKeys(by) {
				if n, ok := by[k].(float64); ok && n > 0 {
					counts = append(counts, domain.Humanize(k)+": "+strconv.Itoa(int(n)))
				}
			}
			sort.Strings(counts)
			o["countsText"] = strings.Join(counts, " · ")
		}
		return o, nil
	}
	return &staffResource{
		key: "referral-settings", module: "payouts", title: "Referral program",
		names: []nameRef{personRef("updatedBy", "Changed by"), personRef("changedBy", "Changed by").inList("history")},
		columns: []domain.Column{col("stateText", "Program", "status", 10), col("referrerText", "Referrer earns", "text", 22), col("referredText", "Friend earns", "text", 22),
			col("minOrderMinor", "Smallest order", "money", 14), col("returnWindowDays", "Return window (days)", "text", 12), col("updatedByName", "Last changed by", "text", 20)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "referral")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        withExtras,
		titleOf:    func(jobj) string { return "Referral program" },
		subtitleOf: func(o jobj) string { return o["stateText"].(string) + " · version " + jstr(o, "version") + " · " + jstr(o, "updatedByName") },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "The offer", Fields: []domain.DetailField{
					fieldOf("Program", jstr(o, "stateText"), "status"),
					fieldOf("The customer who shares earns", jstr(o, "referrerText"), "text"),
					fieldOf("The friend who joins earns", jstr(o, "referredText"), "text"),
				}},
				{Title: "The rules", Fields: []domain.DetailField{
					fieldOf("Qualifying event", "The friend's first order is delivered", "text"),
					fieldOf("Smallest order", jstr(o, "minOrderMinor"), "money"),
					fieldOf("Rewards are granted this many days after delivery", jstr(o, "returnWindowDays"), "text"),
					fieldOf("Most rewarded referrals per customer (0 = no limit)", jstr(o, "maxRewardedPerReferrer"), "text"),
					fieldOf("Most referral credit per customer (0 = no limit)", jstr(o, "maxCreditPerCustomerMinor"), "money"),
					fieldOf("A friend can qualify for this many days after signing up", jstr(o, "validityDays"), "text"),
				}},
				{Title: "What the program has cost so far", Fields: []domain.DetailField{
					fieldOf("Credit paid by AfroTransact", jstr(o, "costMinor"), "money"),
					fieldOf("Credit promised, after the return window", jstr(o, "pendingMinor"), "money"),
					fieldOf("Membership days granted", jstr(o, "membershipDaysGranted"), "text"),
					fieldOf("Referrals", jstr(o, "countsText"), "text"),
				}},
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
				referrer, referred := mapOrEmpty(o, "referrer"), mapOrEmpty(o, "referred")
				section := func(f domain.FormField, s string) domain.FormField { f.Section = s; return f }
				const (
					general = "The program"
					sharer  = "What the customer who shares earns"
					friend  = "What the friend who joins earns"
					rules   = "When it is earned"
					limits  = "Limits"
					audit   = "Why"
				)
				fields := []domain.FormField{
					section(withValue(ff("enabled", "Program on (off stops new referrals and pauses granting)", "toggle", false), boolean(o, "enabled")), general),
					section(withValue(withOptions(ff("referrerType", "Reward", "select", true), rewardTypes), firstNonEmpty(jstr(referrer, "type"), "CREDIT")), sharer),
					section(withValue(withRange(ff("referrerCredit", "Credit (dollars, when the reward is credit)", "number", false), 1000), num(referrer, "creditMinor")/100), sharer),
					section(withValue(withRange(ff("referrerDays", "Membership days (when the reward is membership)", "number", false), 366), num(referrer, "membershipDays")), sharer),
					section(withValue(withOptions(ff("referredType", "Reward", "select", true), rewardTypes), firstNonEmpty(jstr(referred, "type"), "CREDIT")), friend),
					section(withValue(withRange(ff("referredCredit", "Credit (dollars, when the reward is credit)", "number", false), 1000), num(referred, "creditMinor")/100), friend),
					section(withValue(withRange(ff("referredDays", "Membership days (when the reward is membership)", "number", false), 366), num(referred, "membershipDays")), friend),
					section(withValue(withOptions(ff("qualifyingEvent", "Qualifying event", "select", true), qualifyingEvents), firstNonEmpty(jstr(o, "qualifyingEvent"), "FIRST_ORDER_DELIVERED")), rules),
					section(withValue(withRange(ff("minOrder", "Smallest order that qualifies (dollars)", "number", true), 10_000), num(o, "minOrderMinor")/100), rules),
					section(withValue(withRange(ff("returnWindowDays", "Grant the rewards this many days after delivery (the return window)", "number", true), 180), num(o, "returnWindowDays")), rules),
					section(withValue(withRange(ff("validityDays", "A friend can qualify for this many days after signing up", "number", true), 730), num(o, "validityDays")), rules),
					section(withValue(withRange(ff("maxRewarded", "Most rewarded referrals per customer (0 = no limit)", "number", true), 10_000), num(o, "maxRewardedPerReferrer")), limits),
					section(withValue(withRange(ff("maxCredit", "Most referral credit one customer can earn (dollars, 0 = no limit)", "number", true), 1_000_000), num(o, "maxCreditPerCustomerMinor")/100), limits),
					section(withValue(withMax(ff("reason", "Why it changes (at least 15 characters)", "textarea", true), 500), ""), audit),
				}
				return domain.Form{Title: "Change the referral program", SubmitLabel: "Save",
					ChangeKeys: []string{"enabled", "referrerType", "referrerCredit", "referrerDays", "referredType", "referredCredit", "referredDays", "qualifyingEvent",
						"minOrder", "returnWindowDays", "validityDays", "maxRewarded", "maxCredit"},
					Intro:  "AfroTransact pays the rewards. Each side is set on its own. Credit only buys products and services and is never cash. A change applies to new sign-ups and to referrals that qualify afterwards; rewards already promised keep their amounts. Every change is kept with who made it and why.",
					Fields: fields}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				reward := func(typ string, dollars, days float64) jobj {
					if typ == "MEMBERSHIP_DAYS" {
						return jobj{"type": typ, "creditMinor": 0, "membershipDays": int(days)}
					}
					return jobj{"type": "CREDIT", "creditMinor": minorOf(jobj{"x": dollars}, "x"), "membershipDays": 0}
				}
				body := jobj{"enabled": vb(v, "enabled"),
					"referrer":        reward(vs(v, "referrerType"), vn(v, "referrerCredit"), vn(v, "referrerDays")),
					"referred":        reward(vs(v, "referredType"), vn(v, "referredCredit"), vn(v, "referredDays")),
					"qualifyingEvent": vs(v, "qualifyingEvent"), "minOrderMinor": minorOf(v, "minOrder"), "returnWindowDays": int(vn(v, "returnWindowDays")),
					"maxRewardedPerReferrer": int(vn(v, "maxRewarded")), "maxCreditPerCustomerMinor": minorOf(v, "maxCredit"), "validityDays": int(vn(v, "validityDays")),
					"reason": vs(v, "reason"), "version": int64(num(o, "version"))}
				return sc.call(ctx, sc.up.Payments, port.StaffCall{Method: http.MethodPut, Path: "/v1/referral-settings", Body: body}, nil)
			}},
	}
}
