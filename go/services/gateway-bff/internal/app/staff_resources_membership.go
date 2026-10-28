package app

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AfroTransact membership for staff (S323, ADR 0006): the tiers (price, trial, benefits, publish and retire) in Settings,
// the billing rules, the members of every tier with their charges and timeline, and the report Finance reads (members,
// churn, what the benefits cost, recurring revenue, credit granted). Every change of a tier or of the rules carries a
// reason and is kept with who made it. Credit a membership grants is store credit: nothing here pays it out.

func membershipResources() []*staffResource {
	return []*staffResource{membershipTiersResource(), membershipsResource(), membershipSettingsResource(), membershipReportResource()}
}

var membershipBenefitLabels = map[string]string{
	"FREE_DELIVERY": "Free AfroTransact delivery", "FREE_SHIPPING": "Free carrier shipping", "CASHBACK": "Cashback as credit",
	"MEMBER_PRICES": "Member prices and deals", "EARLY_ACCESS": "Early access to pre-orders and runs", "PRIORITY_SUPPORT": "Priority support",
	"FREE_RETURNS": "Free returns", "BOOSTED_REWARDS": "Bigger referral and gift rewards", "MONTHLY_CREDIT": "Monthly credit",
}

// membershipBenefitOrder is the order benefits are shown in.
var membershipBenefitOrder = []string{"FREE_DELIVERY", "FREE_SHIPPING", "CASHBACK", "MEMBER_PRICES", "EARLY_ACCESS", "PRIORITY_SUPPORT", "FREE_RETURNS",
	"BOOSTED_REWARDS", "MONTHLY_CREDIT"}

func benefitOf(tier jobj, code string) jobj {
	for _, b := range objs(tier, "benefits") {
		if jstr(b, "code") == code {
			return b
		}
	}
	return nil
}

func percentText(bps float64) string {
	return strconv.FormatFloat(bps/100, 'f', -1, 64) + "%"
}

// benefitText says in a sentence what one benefit of a tier gives.
func benefitText(b jobj) string {
	switch jstr(b, "code") {
	case "FREE_DELIVERY", "FREE_SHIPPING":
		text := "On every order"
		if m := num(b, "minOrderMinor"); m > 0 {
			text = "On orders from " + dollarText(m)
		}
		if c := num(b, "capMinor"); c > 0 {
			text += ", up to " + dollarText(c) + " off an order"
		}
		return text
	case "CASHBACK":
		text := percentText(num(b, "percentBps")) + " of the goods back as credit"
		if m := num(b, "minOrderMinor"); m > 0 {
			text += " on orders from " + dollarText(m)
		}
		if c := num(b, "capMinor"); c > 0 {
			text += ", up to " + dollarText(c) + " an order"
		}
		return text
	case "EARLY_ACCESS":
		return jstr(b, "hours") + " hours before everyone else"
	case "BOOSTED_REWARDS":
		return percentText(num(b, "percentBps")) + " more on referral and gift rewards"
	case "MONTHLY_CREDIT":
		return dollarText(num(b, "amountMinor")) + " of credit every month"
	default:
		return "Included"
	}
}

func benefitsSummary(tier jobj) string {
	parts := []string{}
	for _, code := range membershipBenefitOrder {
		if benefitOf(tier, code) != nil {
			parts = append(parts, membershipBenefitLabels[code])
		}
	}
	if len(parts) == 0 {
		return "None"
	}
	return strings.Join(parts, ", ")
}

// ---------------------------------------------------------------- tiers

var membershipStatusOptions = map[string][]domain.FieldOption{
	"DRAFT":     {{Value: "DRAFT", Label: "Draft (not for sale)"}, {Value: "PUBLISHED", Label: "Published"}, {Value: "RETIRED", Label: "Retired"}},
	"PUBLISHED": {{Value: "PUBLISHED", Label: "Published"}, {Value: "RETIRED", Label: "Retired (no new members)"}},
	"RETIRED":   {{Value: "PUBLISHED", Label: "Published"}, {Value: "RETIRED", Label: "Retired (no new members)"}},
}

func tierRows(ctx context.Context, sc staffCtx) ([]jobj, error) {
	var raw any
	if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/tiers", nil, &raw); err != nil {
		return nil, err
	}
	rows := []jobj{}
	for _, it := range jpage(raw).items {
		t, _ := it["tier"].(map[string]any)
		if t == nil {
			continue
		}
		t["id"] = jstr(t, "code")
		t["members"], t["trialing"] = it["members"], it["trialing"]
		t["benefitsText"] = benefitsSummary(t)
		rows = append(rows, t)
	}
	return rows, nil
}

// tierFields is the editor of a tier: the plan, then one toggle per benefit of the menu with the numbers it takes.
// o is nil when a tier is created.
func tierFields(o jobj) []domain.FormField {
	has := func(code string) bool { return benefitOf(o, code) != nil }
	dollars := func(code, key string) float64 { return num(benefitOf(o, code), key) / 100 }
	status := firstNonEmpty(jstr(o, "status"), "DRAFT")
	f := []domain.FormField{
		withValue(withMax(ff("name", "Name shoppers see (Plus, Premium)", "text", true), 80), jstr(o, "name")),
		withValue(withMax(ff("description", "Short description", "textarea", false), 500), jstr(o, "description")),
		withValue(withOptions(ff("status", "Status", "select", true), membershipStatusOptions[status]), status),
		withValue(withRange(ff("sortOrder", "Order (lower is the entry tier; a higher tier is an upgrade)", "number", true), 1000), num(o, "sortOrder")),
		withValue(withRange(ff("monthlyPrice", "Monthly price (dollars)", "number", true), 1000), num(o, "monthlyPriceMinor")/100),
		withValue(withRange(ff("yearlyPrice", "Yearly price (dollars)", "number", true), 10_000), num(o, "yearlyPriceMinor")/100),
		withValue(withRange(ff("trialDays", "Free trial (days, 0 for none)", "number", true), 90), num(o, "trialDays")),
		withValue(ff("freeDelivery", "Free AfroTransact delivery", "toggle", false), has("FREE_DELIVERY")),
		withValue(withRange(ff("freeDeliveryMin", "Free delivery: order minimum (dollars)", "number", false), 10_000), dollars("FREE_DELIVERY", "minOrderMinor")),
		withValue(withRange(ff("freeDeliveryCap", "Free delivery: most waived per order (dollars, 0 for no cap)", "number", false), 10_000), dollars("FREE_DELIVERY", "capMinor")),
		withValue(ff("freeShipping", "Free carrier shipping", "toggle", false), has("FREE_SHIPPING")),
		withValue(withRange(ff("freeShippingMin", "Free shipping: order minimum (dollars)", "number", false), 10_000), dollars("FREE_SHIPPING", "minOrderMinor")),
		withValue(withRange(ff("freeShippingCap", "Free shipping: most waived per order (dollars, 0 for no cap)", "number", false), 10_000), dollars("FREE_SHIPPING", "capMinor")),
		withValue(ff("cashback", "Cashback as credit on delivered orders", "toggle", false), has("CASHBACK")),
		withValue(withRange(ff("cashbackPercent", "Cashback: percent of the goods (0.01 to 25)", "number", false), 25), num(benefitOf(o, "CASHBACK"), "percentBps")/100),
		withValue(withRange(ff("cashbackMin", "Cashback: order minimum (dollars)", "number", false), 10_000), dollars("CASHBACK", "minOrderMinor")),
		withValue(withRange(ff("cashbackCap", "Cashback: most per order (dollars, 0 for no cap)", "number", false), 10_000), dollars("CASHBACK", "capMinor")),
		withValue(ff("memberPrices", "Member prices and deals", "toggle", false), has("MEMBER_PRICES")),
		withValue(ff("earlyAccess", "Early access to pre-order windows and delivery runs", "toggle", false), has("EARLY_ACCESS")),
		withValue(withRange(ff("earlyAccessHours", "Early access: hours before everyone else (1 to 720)", "number", false), 720), num(benefitOf(o, "EARLY_ACCESS"), "hours")),
		withValue(ff("prioritySupport", "Priority support", "toggle", false), has("PRIORITY_SUPPORT")),
		withValue(ff("freeReturns", "Free returns", "toggle", false), has("FREE_RETURNS")),
		withValue(ff("boostedRewards", "Bigger referral and gift rewards", "toggle", false), has("BOOSTED_REWARDS")),
		withValue(withRange(ff("boostedPercent", "Bigger rewards: extra percent (0.01 to 100)", "number", false), 100), num(benefitOf(o, "BOOSTED_REWARDS"), "percentBps")/100),
		withValue(ff("monthlyCredit", "A credit every month", "toggle", false), has("MONTHLY_CREDIT")),
		withValue(withRange(ff("monthlyCreditAmount", "Monthly credit (dollars)", "number", false), 1000), dollars("MONTHLY_CREDIT", "amountMinor")),
	}
	return f
}

var tierChangeKeys = []string{"name", "description", "status", "sortOrder", "monthlyPrice", "yearlyPrice", "trialDays", "freeDelivery", "freeDeliveryMin",
	"freeDeliveryCap", "freeShipping", "freeShippingMin", "freeShippingCap", "cashback", "cashbackPercent", "cashbackMin", "cashbackCap", "memberPrices",
	"earlyAccess", "earlyAccessHours", "prioritySupport", "freeReturns", "boostedRewards", "boostedPercent", "monthlyCredit", "monthlyCreditAmount"}

// tierBenefits turns the toggles and numbers of the editor into the benefits payments stores.
func tierBenefits(v jobj) ([]jobj, error) {
	errs := map[string]string{}
	out := []jobj{}
	withCap := func(b jobj, prefix string) jobj {
		b["minOrderMinor"] = minorOf(v, prefix+"Min")
		if c := minorOf(v, prefix+"Cap"); c > 0 {
			b["capMinor"] = c
		}
		return b
	}
	if vb(v, "freeDelivery") {
		out = append(out, withCap(jobj{"code": "FREE_DELIVERY"}, "freeDelivery"))
	}
	if vb(v, "freeShipping") {
		out = append(out, withCap(jobj{"code": "FREE_SHIPPING"}, "freeShipping"))
	}
	if vb(v, "cashback") {
		bps := int(math.Round(vn(v, "cashbackPercent") * 100))
		if bps < 1 || bps > 2500 {
			errs["cashbackPercent"] = "Between 0.01% and 25%"
		}
		out = append(out, withCap(jobj{"code": "CASHBACK", "percentBps": bps}, "cashback"))
	}
	if vb(v, "memberPrices") {
		out = append(out, jobj{"code": "MEMBER_PRICES"})
	}
	if vb(v, "earlyAccess") {
		hours := int(math.Round(vn(v, "earlyAccessHours")))
		if hours < 1 || hours > 720 {
			errs["earlyAccessHours"] = "Between 1 and 720 hours"
		}
		out = append(out, jobj{"code": "EARLY_ACCESS", "hours": hours})
	}
	if vb(v, "prioritySupport") {
		out = append(out, jobj{"code": "PRIORITY_SUPPORT"})
	}
	if vb(v, "freeReturns") {
		out = append(out, jobj{"code": "FREE_RETURNS"})
	}
	if vb(v, "boostedRewards") {
		bps := int(math.Round(vn(v, "boostedPercent") * 100))
		if bps < 1 || bps > 10_000 {
			errs["boostedPercent"] = "Between 0.01% and 100%"
		}
		out = append(out, jobj{"code": "BOOSTED_REWARDS", "percentBps": bps})
	}
	if vb(v, "monthlyCredit") {
		amount := minorOf(v, "monthlyCreditAmount")
		if amount < 1 || amount > 100_000 {
			errs["monthlyCreditAmount"] = "Between 0.01 and 1,000.00"
		}
		out = append(out, jobj{"code": "MONTHLY_CREDIT", "amountMinor": amount})
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	return out, nil
}

// tierRequest is payments' MembershipTierRequest from the editor's values.
func tierRequest(code string, v jobj, version any) (jobj, error) {
	benefits, err := tierBenefits(v)
	if err != nil {
		return nil, err
	}
	monthly, yearly := minorOf(v, "monthlyPrice"), minorOf(v, "yearlyPrice")
	errs := map[string]string{}
	if monthly < 1 {
		errs["monthlyPrice"] = "Enter a price above zero"
	}
	if yearly < 1 {
		errs["yearlyPrice"] = "Enter a price above zero"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	body := jobj{"code": code, "name": vs(v, "name"), "description": vs(v, "description"), "status": vs(v, "status"), "sortOrder": int(vn(v, "sortOrder")),
		"monthlyPriceMinor": monthly, "yearlyPriceMinor": yearly, "trialDays": int(vn(v, "trialDays")), "benefits": benefits, "reason": vs(v, "reason")}
	if version != nil {
		body["expectedVersion"] = version
	}
	return omitEmpty(body), nil
}

// tierStatusRequest is the same tier with another status (publish, retire).
func tierStatusRequest(o jobj, status, reason string) jobj {
	body := jobj{"code": jstr(o, "code"), "name": jstr(o, "name"), "description": jstr(o, "description"), "status": status, "sortOrder": int(num(o, "sortOrder")),
		"monthlyPriceMinor": int64(num(o, "monthlyPriceMinor")), "yearlyPriceMinor": int64(num(o, "yearlyPriceMinor")), "trialDays": int(num(o, "trialDays")),
		"benefits": o["benefits"], "reason": reason, "expectedVersion": int64(num(o, "version"))}
	return omitEmpty(body)
}

func membershipReasonField() domain.FormField {
	return withMax(ff("reason", "Why it changes (at least 15 characters; kept in the history)", "textarea", true), 500)
}

func membershipTiersResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		rows, err := tierRows(ctx, sc)
		if err != nil || q.Status == "" {
			return rows, err
		}
		kept := rows[:0]
		for _, r := range rows {
			if jstr(r, "status") == q.Status {
				kept = append(kept, r)
			}
		}
		return kept, nil
	}
	list := listAll(load)
	publish := func(key, label, status, intro string, from ...string) staffAction {
		return staffAction{key: key, label: label, tone: map[string]string{"PUBLISHED": "primary", "RETIRED": "danger"}[status],
			permission: "payments:configure", when: inState(from...),
			form: noteFormReason("Why?", label, intro),
			run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
				return sc.call(ctx, sc.up.Payments, port.StaffCall{Method: http.MethodPut, Path: "/v1/memberships/admin/tiers/" + url.PathEscape(jstr(o, "code")),
					Body: tierStatusRequest(o, status, vs(v, "reason"))}, nil)
			}}
	}
	return &staffResource{
		key: "membership-tiers", module: "payouts", title: "Membership tiers", statusKey: "status", exportable: true,
		names: []nameRef{personRef("updatedBy", "Changed by"), personRef("changedBy", "Changed by").inList("history")},
		columns: []domain.Column{col("name", "Tier", "strong", 14), col("status", "Status", "status", 10), col("monthlyPriceMinor", "Monthly", "money", 9), col("yearlyPriceMinor", "Yearly", "money", 9),
			col("trialDays", "Trial days", "number", 8), col("members", "Members", "number", 8), col("trialing", "On a trial", "number", 8), col("benefitsText", "Benefits", "text", 34)},
		facets: domain.StaffFacets("DRAFT", "PUBLISHED", "RETIRED"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			o, err := findIn(list, "id")(ctx, sc, id)
			if err != nil {
				return nil, err
			}
			var raw any
			if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/tiers/"+url.PathEscape(id)+"/changes", url.Values{"limit": {"30"}}, &raw); err == nil {
				o["history"] = jpage(raw).items
			}
			return o, nil
		},
		titleOf: func(o jobj) string { return jstr(o, "name") + " membership" },
		subtitleOf: func(o jobj) string {
			return domain.Humanize(jstr(o, "status")) + " · " + dollarText(num(o, "monthlyPriceMinor")) + " a month or " + dollarText(num(o, "yearlyPriceMinor")) + " a year · " +
				jstr(o, "members") + " members"
		},
		version: func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			plan := domain.DetailSection{Title: "The plan", Fields: []domain.DetailField{
				fieldOf("Name", jstr(o, "name"), "text"),
				fieldOf("Code", jstr(o, "code"), "mono"),
				fieldOf("Description", jstr(o, "description"), "text"),
				fieldOf("Status", jstr(o, "status"), "status"),
				fieldOf("Order", jstr(o, "sortOrder"), "text"),
				fieldOf("Monthly price", jstr(o, "monthlyPriceMinor"), "money"),
				fieldOf("Yearly price", jstr(o, "yearlyPriceMinor"), "money"),
				fieldOf("Free trial", jstr(o, "trialDays")+" days", "text"),
			}}
			benefits := domain.DetailSection{Title: "Benefits", Fields: []domain.DetailField{}}
			for _, code := range membershipBenefitOrder {
				if b := benefitOf(o, code); b != nil {
					benefits.Fields = append(benefits.Fields, fieldOf(membershipBenefitLabels[code], benefitText(b), "text"))
				}
			}
			if len(benefits.Fields) == 0 {
				benefits.Fields = append(benefits.Fields, fieldOf("Benefits", "None", "text"))
			}
			return []domain.DetailSection{plan, benefits,
				{Title: "Members", Fields: []domain.DetailField{
					fieldOf("Members now", jstr(o, "members"), "text"),
					fieldOf("On a trial", jstr(o, "trialing"), "text"),
				}},
				{Title: "Last change", Fields: []domain.DetailField{
					fieldOf("Changed by", jstr(o, "updatedByName"), "text"),
					fieldOf("Changed at", jstr(o, "updatedAt"), "datetime"),
				}}}
		},
		tables: func(o jobj) []domain.DetailTable {
			rows := []jobj{}
			for _, h := range objs(o, "history") {
				rows = append(rows, jobj{"id": jstr(h, "id"), "changedAt": jstr(h, "changedAt"), "changedByName": jstr(h, "changedByName"),
					"action": domain.Humanize(jstr(h, "action")), "reason": jstr(h, "reason")})
			}
			return []domain.DetailTable{tableOf("Who changed it and why", rows, []domain.Column{col("changedAt", "When", "datetime", 20), col("changedByName", "Who", "text", 20),
				col("action", "What", "text", 14), col("reason", "Why", "text", 46)}, "id")}
		},
		actions: []staffAction{
			publish("publish", "Publish", "PUBLISHED", "Shoppers can join this tier at once. A published tier never goes back to a draft.", "DRAFT", "RETIRED"),
			publish("retire", "Retire", "RETIRED", "Stops new sign-ups. Current members keep their benefits and their price and can stay for as long as they like.", "PUBLISHED"),
		},
		edit: &staffEdit{permission: "payments:configure",
			form: func(o jobj) domain.Form {
				fields := append(tierFields(o), withValue(membershipReasonField(), ""))
				return domain.Form{Title: "Change the " + jstr(o, "name") + " tier", SubmitLabel: "Save", ChangeKeys: tierChangeKeys, Fields: fields,
					Intro: "Benefit changes apply to every member at once. Members keep the price they signed up at. A reason is required and is kept with who made the change."}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				body, err := tierRequest(jstr(o, "code"), v, int64(num(o, "version")))
				if err != nil {
					return err
				}
				return sc.call(ctx, sc.up.Payments, port.StaffCall{Method: http.MethodPut, Path: "/v1/memberships/admin/tiers/" + url.PathEscape(jstr(o, "code")), Body: body}, nil)
			}},
		create: &staffCreate{permission: "payments:configure",
			form: func(context.Context, staffCtx) domain.Form {
				fields := tierFields(nil)
				fields[3] = withValue(fields[3], 10.0)
				fields[6] = withValue(fields[6], 14.0)
				fields = append([]domain.FormField{withPattern(withMax(ff("code", "Code (lower case letters, numbers and dashes; cannot change later)", "text", true), 40),
					`^[a-z][a-z0-9-]{1,39}$`, "Use 2 to 40 lower case letters, numbers or dashes, starting with a letter")}, fields...)
				fields = append(fields, withValue(withMax(ff("reason", "Why this tier is added (at least 15 characters)", "textarea", true), 500), ""))
				return domain.Form{Title: "Add a membership tier", SubmitLabel: "Add tier", Fields: fields,
					Intro: "Define the price, the trial and the benefits from the menu. Start it as a draft to review it, or publish it to open it to shoppers at once."}
			},
			create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
				body, err := tierRequest(vs(v, "code"), v, nil)
				if err != nil {
					return "", err
				}
				var out jobj
				if err := sc.post(ctx, sc.up.Payments, "/v1/memberships/admin/tiers", body, &out); err != nil {
					return "", err
				}
				return jstr(out, "code"), nil
			}},
	}
}

// ---------------------------------------------------------------- members

func membershipTierNames(ctx context.Context, sc staffCtx) map[string]string {
	names := map[string]string{}
	rows, err := tierRows(ctx, sc)
	if err != nil {
		return names
	}
	for _, r := range rows {
		names[jstr(r, "code")] = jstr(r, "name")
	}
	return names
}

func membershipsResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error) {
		v := url.Values{"limit": {"200"}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/members", v, &raw); err != nil {
			return nil, err
		}
		tiers := membershipTierNames(ctx, sc)
		rows := jpage(raw).items
		for _, m := range rows {
			m["tierName"] = firstNonEmpty(tiers[jstr(m, "tierCode")], jstr(m, "tierCode"))
		}
		return rows, nil
	}
	list := listAll(load)
	return &staffResource{
		key: "memberships", module: "payouts", title: "Members", statusKey: "status", exportable: true,
		searchPlaceholder: "Member, tier or status",
		names:             []nameRef{customerRef("customerId")},
		columns: []domain.Column{col("customerName", "Member", "strong", 20), col("tierName", "Tier", "text", 12), col("status", "Status", "status", 11), col("interval", "Billed", "text", 9),
			col("priceMinor", "Price", "money", 9), col("source", "Source", "text", 11), col("startedAt", "Joined", "date", 12), col("periodEnd", "Renews or ends", "date", 14)},
		facets: domain.StaffFacets("TRIALING", "ACTIVE", "PAST_DUE", "CANCELED", "LAPSED", "EXPIRED"),
		list:   list,
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			m, err := findIn(list, "id")(ctx, sc, id)
			if err != nil {
				return nil, err
			}
			var mine jobj
			if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/customers/"+url.PathEscape(jstr(m, "customerId")), nil, &mine); err == nil {
				m["charges"], m["usage"], m["refund"] = mine["charges"], mine["usage"], mine["refund"]
				m["card"] = mine["card"]
			}
			var events any
			if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/members/"+url.PathEscape(id)+"/events", nil, &events); err == nil {
				m["events"] = jpage(events).items
			}
			return m, nil
		},
		titleOf: func(o jobj) string {
			return firstNonEmpty(jstr(o, "customerName"), "A member") + " · " + jstr(o, "tierName")
		},
		subtitleOf: func(o jobj) string {
			return domain.Humanize(jstr(o, "status")) + " · " + dollarText(num(o, "priceMinor")) + " " + strings.ToLower(domain.Humanize(jstr(o, "interval")))
		},
		sections: func(o jobj) []domain.DetailSection {
			cardText := ""
			if c := jstr(o, "card.last4"); c != "" {
				cardText = domain.Humanize(jstr(o, "card.brand")) + " ending " + c
			}
			return []domain.DetailSection{
				{Title: "Membership", Fields: []domain.DetailField{
					fieldOf("Member", jstr(o, "customerName"), "text"),
					fieldOf("Tier", jstr(o, "tierName"), "text"),
					fieldOf("Status", jstr(o, "status"), "status"),
					fieldOf("Source", domain.Humanize(jstr(o, "source")), "text"),
					fieldOf("Billed", domain.Humanize(jstr(o, "interval")), "text"),
					fieldOf("Price each period", jstr(o, "priceMinor"), "money"),
					fieldOf("Card", cardText, "text"),
					fieldOf("Joined", jstr(o, "startedAt"), "datetime"),
					fieldOf("Period ends", jstr(o, "periodEnd"), "datetime"),
					fieldOf("Trial ends", jstr(o, "trialEnd"), "datetime"),
				}},
				{Title: "Cancellation, changes and retries", Fields: []domain.DetailField{
					fieldOf("Stops renewing", map[bool]string{true: "Yes, at the end of the period", false: "No"}[boolean(o, "cancelAtPeriodEnd")], "text"),
					fieldOf("Moves to tier", jstr(o, "pendingTierCode"), "text"),
					fieldOf("Failed renewals", jstr(o, "retryCount"), "text"),
					fieldOf("Next retry", jstr(o, "nextRetryAt"), "datetime"),
					fieldOf("Ended", jstr(o, "endedAt"), "datetime"),
					fieldOf("Why it ended", domain.Humanize(jstr(o, "endReason")), "text"),
					fieldOf("Free days granted", jstr(o, "freeDaysGranted"), "text"),
				}},
				{Title: "Refund rule", Fields: []domain.DetailField{
					fieldOf("Refund possible", map[bool]string{true: "Yes", false: "No"}[boolean(o, "refund.eligible")], "text"),
					fieldOf("Why", domain.Humanize(jstr(o, "refund.reason")), "text"),
					fieldOf("Until", jstr(o, "refund.until"), "datetime"),
				}},
			}
		},
		tables: func(o jobj) []domain.DetailTable {
			charges := []jobj{}
			for _, c := range objs(o, "charges") {
				charges = append(charges, jobj{"id": jstr(c, "id"), "createdAt": jstr(c, "createdAt"), "kind": domain.Humanize(jstr(c, "kind")), "amountMinor": num(c, "amountMinor"),
					"status": jstr(c, "status"), "refundedMinor": num(c, "refundedMinor"), "reason": firstNonEmpty(jstr(c, "refundReason"), jstr(c, "failureMessage"))})
			}
			usage := []jobj{}
			for _, u := range objs(o, "usage") {
				usage = append(usage, jobj{"id": jstr(u, "benefit"), "benefit": membershipBenefitLabels[jstr(u, "benefit")], "uses": num(u, "uses"), "amountMinor": num(u, "amountMinor")})
			}
			events := []jobj{}
			for _, e := range objs(o, "events") {
				events = append(events, jobj{"id": jstr(e, "id"), "occurredAt": jstr(e, "occurredAt"), "type": domain.Humanize(jstr(e, "type")), "detail": jstr(e, "detail")})
			}
			return []domain.DetailTable{
				tableOf("Charges", charges, []domain.Column{col("createdAt", "When", "datetime", 20), col("kind", "Kind", "text", 12), col("amountMinor", "Amount", "money", 12),
					col("status", "Status", "status", 12), col("refundedMinor", "Refunded", "money", 12), col("reason", "Note", "text", 32)}, "id"),
				tableOf("What the benefits gave", usage, []domain.Column{col("benefit", "Benefit", "text", 50), col("uses", "Times", "number", 20), col("amountMinor", "Worth", "money", 30)}, "id"),
				tableOf("Timeline", events, []domain.Column{col("occurredAt", "When", "datetime", 22), col("type", "What happened", "text", 28), col("detail", "Detail", "text", 50)}, "id"),
			}
		},
		actions: []staffAction{
			{key: "refund", label: "Refund the latest fee to the card", tone: "danger", permission: "payments:configure",
				when: func(_ staffCtx, o jobj) string {
					if latestRefundable(o) == "" {
						return "There is no fee to refund."
					}
					return ""
				},
				confirm: "Refund the whole fee to the card it was charged on? When it paid the current period of a live membership, the membership ends now. Credit is never refunded to a card.",
				form:    noteFormReason("Why is the fee refunded?", "Refund", "The reason is kept on the charge and in the audit log."),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					id := latestRefundable(o)
					if id == "" {
						return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Nothing to refund", "There is no fee to refund.")
					}
					return sc.post(ctx, sc.up.Payments, "/v1/memberships/admin/charges/"+url.PathEscape(id)+"/refund", jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
	}
}

// latestRefundable is the id of the newest charge that succeeded and was not refunded; charges come newest first.
func latestRefundable(o jobj) string {
	for _, c := range objs(o, "charges") {
		if jstr(c, "status") == "SUCCEEDED" && num(c, "refundedMinor") == 0 && num(c, "amountMinor") > 0 {
			return jstr(c, "id")
		}
	}
	return ""
}

// ---------------------------------------------------------------- billing rules

func retryText(o jobj) string {
	days := []string{}
	for _, d := range at(o, "retryDays").([]any) {
		if f, ok := d.(float64); ok {
			days = append(days, strconv.FormatFloat(f, 'f', -1, 64))
		}
	}
	return strings.Join(days, ", ")
}

func membershipSettingsResource() *staffResource {
	get := func(ctx context.Context, sc staffCtx, _ string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/settings", nil, &o); err != nil {
			return nil, err
		}
		if _, ok := o["retryDays"].([]any); !ok {
			o["retryDays"] = []any{}
		}
		o["id"], o["retryText"] = "membership", retryText(o)
		return o, nil
	}
	withHistory := func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		o, err := get(ctx, sc, id)
		if err != nil {
			return nil, err
		}
		var raw any
		if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/settings/changes", url.Values{"limit": {"20"}}, &raw); err == nil {
			o["history"] = jpage(raw).items
		}
		return o, nil
	}
	return &staffResource{
		key: "membership-settings", module: "payouts", title: "Membership billing rules",
		names: []nameRef{personRef("updatedBy", "Changed by"), personRef("changedBy", "Changed by").inList("history")},
		columns: []domain.Column{col("refundWindowDays", "Refund window (days)", "number", 20), col("retryText", "Retries after a failed renewal (days)", "text", 30),
			col("updatedByName", "Last changed by", "text", 24), col("updatedAt", "Changed", "datetime", 20)},
		list: func(ctx context.Context, sc staffCtx, _ staffQuery) (staffList, error) {
			o, err := get(ctx, sc, "membership")
			if err != nil {
				return staffList{}, err
			}
			one := int64(1)
			return staffList{items: []jobj{o}, total: &one}, nil
		},
		get:        withHistory,
		titleOf:    func(jobj) string { return "Membership billing rules" },
		subtitleOf: func(o jobj) string { return "Version " + jstr(o, "version") + " · " + jstr(o, "updatedByName") },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{
				{Title: "Refund rule", Fields: []domain.DetailField{
					fieldOf("A member can cancel with a full refund for", jstr(o, "refundWindowDays")+" days after a charge, if no benefit was used", "text"),
				}},
				{Title: "When a renewal fails", Fields: []domain.DetailField{
					fieldOf("Retry after (days)", firstNonEmpty(jstr(o, "retryText"), "No retries"), "text"),
					fieldOf("Then", "The membership lapses and the benefits stop", "text"),
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
				return domain.Form{Title: "Change the membership billing rules", SubmitLabel: "Save", ChangeKeys: []string{"refundWindowDays", "retryDays"},
					Intro: "Takes effect at once. Every change is kept with who made it and why.",
					Fields: []domain.FormField{
						withValue(withRange(ff("refundWindowDays", "Full refund when canceling within (days; 0 turns the refund off)", "number", true), 60), num(o, "refundWindowDays")),
						withValue(withPattern(withMax(ff("retryDays", "Retry a failed renewal after these many days (up to 6, like 1, 3, 5)", "text", false), 40),
							`^\s*\d{1,2}(\s*,\s*\d{1,2}){0,5}\s*$`, "For example 1, 3, 5"), jstr(o, "retryText")),
						withValue(membershipReasonField(), ""),
					}}
			},
			save: func(ctx context.Context, sc staffCtx, o jobj, v jobj, _ string) error {
				days := []int{}
				for _, part := range strings.Split(vs(v, "retryDays"), ",") {
					part = strings.TrimSpace(part)
					if part == "" {
						continue
					}
					d, err := strconv.Atoi(part)
					if err != nil || d < 1 || d > 30 {
						return domain.ValidationProblem(map[string]string{"retryDays": "Use days from 1 to 30 separated by commas, like 1, 3, 5"})
					}
					days = append(days, d)
				}
				body := jobj{"refundWindowDays": int(vn(v, "refundWindowDays")), "retryDays": days, "reason": vs(v, "reason"), "expectedVersion": int64(num(o, "version"))}
				return sc.call(ctx, sc.up.Payments, port.StaffCall{Method: http.MethodPut, Path: "/v1/memberships/admin/settings", Body: body}, nil)
			}},
	}
}

// ---------------------------------------------------------------- report

func membershipReportResource() *staffResource {
	load := func(ctx context.Context, sc staffCtx, _ staffQuery) ([]jobj, error) {
		var r jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/admin/report", nil, &r); err != nil {
			return nil, err
		}
		return membershipReportRows(r), nil
	}
	list := listAll(load)
	return &staffResource{
		key: "membership-report", module: "payouts", title: "Membership: members, churn, cost and revenue", exportable: true,
		columns: []domain.Column{col("item", "What", "strong", 32), col("amountMinor", "Amount", "money", 14), col("count", "Count", "number", 10), col("note", "What it means", "text", 44)},
		list:    list,
		get:     findIn(list, "id"),
		titleOf: func(o jobj) string { return jstr(o, "item") },
		subtitleOf: func(o jobj) string {
			return jstr(o, "note")
		},
		sections: func(o jobj) []domain.DetailSection {
			return []domain.DetailSection{{Title: "This line", Fields: []domain.DetailField{
				fieldOf("What", jstr(o, "item"), "text"),
				fieldOf("Amount", jstr(o, "amountMinor"), "money"),
				fieldOf("Count", jstr(o, "count"), "text"),
				fieldOf("What it means", jstr(o, "note"), "text"),
			}}}
		},
	}
}

// membershipReportRows lays the payments report out as lines Finance reads and exports: members and churn, the revenue
// the fees bring, what the benefits cost (credit granted, fees waived) and the same per tier.
func membershipReportRows(r jobj) []jobj {
	row := func(id, item string, amount float64, count any, note string) jobj {
		return jobj{"id": id, "item": item, "amountMinor": amount, "count": count, "note": note}
	}
	period := "Last 30 days"
	rows := []jobj{
		row("members", "Members now", 0, int(num(r, "liveMembers")), "Trialing, active or past due."),
		row("trialing", "On a free trial", 0, int(num(r, "trialing")), "They pay nothing until the trial ends and get no credit benefits."),
		row("mrr", "Monthly recurring revenue", num(r, "mrrMinor"), nil, "What paying members pay per month (a yearly fee divided by twelve)."),
		row("arr", "Annual recurring revenue", num(r, "arrMinor"), nil, "Monthly recurring revenue times twelve."),
		row("charged", "Fees charged", num(r, "chargedMinor"), nil, period+". Joining, renewals and upgrades."),
		row("refunded", "Fees refunded", num(r, "refundedMinor"), int(num(r, "refunded")), period+". Refunded to the card."),
		row("net", "Net membership revenue", num(r, "netRevenueMinor"), nil, period+". Fees charged less fees refunded."),
		row("benefit-cost", "Cost of the benefits", num(r, "benefitCostMinor"), nil, period+". Credit granted plus delivery and shipping fees waived."),
		row("credit-granted", "Credit granted to members", num(r, "creditGrantedMinor"), nil, period+". Cashback and monthly credit; store credit only buys products and services."),
		row("fees-waived", "Delivery and shipping fees waived", num(r, "feesWaivedMinor"), nil, period+". Fees AfroTransact did not charge members."),
		row("joined", "Members who joined", 0, int(num(r, "joined")), period+"."),
		row("canceled", "Members who canceled", 0, int(num(r, "canceled")), period+". Benefits stay to the end of the paid period."),
		row("lapsed", "Memberships that lapsed", 0, int(num(r, "lapsed")), period+". Renewal failed after every retry."),
		row("churn", "Churn", 0, nil, fmt.Sprintf("%s. %s of the %d members at the start left (%d memberships ended).", period, percentText(num(r, "churnBps")),
			int(num(r, "membersAtStart")), int(num(r, "ended")))),
	}
	for _, t := range objs(r, "tiers") {
		code := jstr(t, "tierCode")
		rows = append(rows, row("tier-"+code, jstr(t, "tierName")+" tier", num(t, "mrrMinor"), int(num(t, "members")),
			fmt.Sprintf("%d trialing, %d active, %d past due, %d canceling. Joined %d, canceled %d, lapsed %d. Churn %s. Monthly recurring revenue shown.",
				int(num(t, "trialing")), int(num(t, "active")), int(num(t, "pastDue")), int(num(t, "cancelling")), int(num(t, "joined")), int(num(t, "canceled")),
				int(num(t, "lapsed")), percentText(num(t, "churnBps")))))
	}
	for _, c := range objs(r, "costs") {
		code := jstr(c, "benefit")
		rows = append(rows, row("cost-"+strings.ToLower(code), "Cost: "+firstNonEmpty(membershipBenefitLabels[code], domain.Humanize(code)), num(c, "amountMinor"), int(num(c, "uses")),
			period+". What this benefit gave members."))
	}
	return rows
}
