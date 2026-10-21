package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// assistantRun is the state of one question: who asks, what her tier allows, and what she looked up and prepared.
type assistantRun struct {
	s         *sellerAssistantImpl
	c         *domain.Caller
	sc        sellerCtx
	tier      domain.AssistantTier
	limits    domain.AssistantLimits
	steps     []domain.AssistantStep
	proposals []domain.AssistantProposal
}

type assistantToolDef struct {
	name, description, schema string
	// label says what she is doing, for the seller ("Checking your orders").
	label func(args map[string]any) string
	run   func(ctx context.Context, r *assistantRun, args map[string]any) (any, error)
}

const (
	maxRows        = 10
	maxStockRows   = 15
	noArgsSchema   = `{"type":"object","properties":{},"additionalProperties":false}`
	orderStatuses  = `["placed","confirmed","packed","ready","picked_up","in_transit","out_for_delivery","delivered","delivery_failed","returned","cancelled"]`
	listingStatuse = `["live","paused","in_review","rejected","taken_down"]`
)

var assistantTools = []assistantToolDef{
	{
		name: domain.ToolMyOrders, description: "The seller's recent orders (one row per shipment), newest first, with status, items, total and the next step. Optionally only one status.",
		schema: `{"type":"object","properties":{"status":{"type":"string","enum":` + orderStatuses + `},"limit":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}`,
		label:  func(a map[string]any) string { return withStatus("Checking your orders", argStr(a, "status")) },
		run:    toolMyOrders,
	},
	{
		name: domain.ToolOrderDetail, description: "One order: its shipments, items, tracking and what can be done next. Needs the order number (like AT-2026-ABC123).",
		schema: `{"type":"object","properties":{"orderNumber":{"type":"string"}},"required":["orderNumber"],"additionalProperties":false}`,
		label:  func(a map[string]any) string { return "Opening order " + argStr(a, "orderNumber") },
		run:    toolOrderDetail,
	},
	{
		name: domain.ToolMyListings, description: "The seller's listings with price, stock and status. Optionally a search text or one status.",
		schema: `{"type":"object","properties":{"query":{"type":"string"},"status":{"type":"string","enum":` + listingStatuse + `},"limit":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}`,
		label:  func(a map[string]any) string { return "Checking your listings" },
		run:    toolMyListings,
	},
	{
		name: domain.ToolStockLevels, description: "Stock held by AfroTransact for the seller's SKUs (on hand, reserved, available, inbound). lowOnly shows only SKUs running low. Seller-shipped stock is in my_listings.",
		schema: `{"type":"object","properties":{"lowOnly":{"type":"boolean"}},"additionalProperties":false}`,
		label:  func(a map[string]any) string { return "Checking stock" },
		run:    toolStockLevels,
	},
	{
		name: domain.ToolFeesAndPlan, description: "The seller's plan and the fees that apply: commission by category, card fees, fulfilment fee.",
		schema: noArgsSchema,
		label:  func(map[string]any) string { return "Reading your plan and fees" },
		run:    toolFeesAndPlan,
	},
	{
		name: domain.ToolPayouts, description: "When the seller gets paid: the payout balance (available now, on hold, in transit, paid), every held order with the date its money is released, why payouts might be paused, fees owed and the latest payouts. Use it for any question about money, payouts or release dates.",
		schema: noArgsSchema,
		label:  func(map[string]any) string { return "Checking your payouts" },
		run:    toolPayouts,
	},
	{
		name: domain.ToolAccountHealth, description: "What Stripe and AfroTransact still need from the seller (verification items, deadlines), whether charges and payouts are on, and whether payouts or listings are paused. Use it when a payout is late or paused.",
		schema: noArgsSchema,
		label:  func(map[string]any) string { return "Checking your account health" },
		run:    toolAccountHealth,
	},
	{
		name: domain.ToolMyReturns, description: "Returns customers started on the seller's orders: return number, order, items and reasons, where each stands (requested, label issued, in transit, received, refunded, rejected) and the refund. Optionally one status. Use it for 'any returns waiting' or 'did the return arrive'.",
		schema: `{"type":"object","properties":{"status":{"type":"string","enum":["REQUESTED","APPROVED","LABEL_ISSUED","IN_TRANSIT","RECEIVED","INSPECTED","REFUNDED","REJECTED","CANCELLED"]}},"additionalProperties":false}`,
		label:  func(a map[string]any) string { return withStatus("Checking your returns", argStr(a, "status")) },
		run:    toolMyReturns,
	},
	{
		name: domain.ToolMyDisputes, description: "Disputes and A-to-z claims against the seller: reference, type, order, amount, status, the deadline to respond and whether it is at risk, and the outcome. Optionally one status. Use it for 'do I have disputes' or 'when must I respond'.",
		schema: `{"type":"object","properties":{"status":{"type":"string","enum":["AWAITING_SELLER","UNDER_REVIEW","ESCALATED","RESOLVED_REFUND","RESOLVED_DENIED","WITHDRAWN"]}},"additionalProperties":false}`,
		label:  func(a map[string]any) string { return withStatus("Checking your disputes", argStr(a, "status")) },
		run:    toolMyDisputes,
	},
	{
		name: domain.ToolPlanBilling, description: "The seller's plan subscription and invoices: plan, price, next charge and date, trial or free months, platform credit, any condition on charging, how the plan is paid (from sales first or card), unpaid or declined invoices with the reason, and rewards earned or in progress. Use it for 'why was I charged', 'what do I owe' or 'when is my next bill'.",
		schema: noArgsSchema,
		label:  func(map[string]any) string { return "Checking your plan and invoices" },
		run:    toolPlanBilling,
	},
	{
		name: domain.ToolMyCoupons, description: "The seller's own coupons: code, discount, minimum spend, scope and whether each is active or paused.",
		schema: noArgsSchema,
		label:  func(map[string]any) string { return "Checking your coupons" },
		run:    toolMyCoupons,
	},
	{
		name: domain.ToolSalesSummary, description: "Sales summary for the last 7, 30 or 90 days: headline numbers and what needs attention.",
		schema: `{"type":"object","properties":{"days":{"type":"integer","enum":[7,30,90]}},"additionalProperties":false}`,
		label:  func(a map[string]any) string { return "Summarising your sales" },
		run:    toolSalesSummary,
	},
	{
		name: domain.ToolProposeStock, description: "Prepare setting the stock of a seller-shipped SKU to a quantity. The seller sees a button and must tap it; nothing changes until then.",
		schema: `{"type":"object","properties":{"sku":{"type":"string"},"quantity":{"type":"integer","minimum":0,"maximum":100000}},"required":["sku","quantity"],"additionalProperties":false}`,
		label:  func(a map[string]any) string { return "Preparing a stock change for " + argStr(a, "sku") },
		run:    toolProposeStock,
	},
	{
		name: domain.ToolProposeNext, description: "Prepare moving a shipment one step on: CONFIRMED, PACKED or READY. The seller sees a button and must tap it. Shipping and handing over need a label or a code and are done in the order page.",
		schema: `{"type":"object","properties":{"orderNumber":{"type":"string"},"shipmentId":{"type":"string"},"step":{"type":"string","enum":["CONFIRMED","PACKED","READY"]}},"required":["orderNumber","shipmentId","step"],"additionalProperties":false}`,
		label:  func(a map[string]any) string { return "Preparing the next step for " + argStr(a, "orderNumber") },
		run:    toolProposeNext,
	},
}

func (r *assistantRun) specs() []port.AssistantTool {
	out := []port.AssistantTool{}
	for _, t := range assistantTools {
		if slices.Contains(r.limits.Tools, t.name) {
			out = append(out, port.AssistantTool{Name: t.name, Description: t.description, InputSchema: t.schema})
		}
	}
	return out
}

// execute runs one tool the model asked for and returns what the model sees: JSON inside <untrusted> (the seller's own data
// contains text others wrote). A refusal or an error is returned as text too, so she can tell the seller why.
func (r *assistantRun) execute(ctx context.Context, call port.AssistantToolCall) string {
	var def *assistantToolDef
	for i := range assistantTools {
		if assistantTools[i].name == call.Name && slices.Contains(r.limits.Tools, call.Name) {
			def = &assistantTools[i]
		}
	}
	if def == nil {
		return untrusted(map[string]any{"error": "That tool is not available on this plan."})
	}
	args := map[string]any{}
	if strings.TrimSpace(call.Arguments) != "" {
		if err := json.Unmarshal([]byte(call.Arguments), &args); err != nil {
			return untrusted(map[string]any{"error": "The arguments were not valid JSON."})
		}
	}
	r.steps = append(r.steps, domain.AssistantStep{Tool: def.name, Label: def.label(args)})
	out, err := def.run(ctx, r, args)
	if err != nil {
		return untrusted(map[string]any{"error": problemText(err)})
	}
	return untrusted(out)
}

func untrusted(v any) string {
	b, err := json.Marshal(v) // json escapes < and >, so data cannot close the tag
	if err != nil {
		return "<untrusted>{}</untrusted>"
	}
	return "<untrusted>" + string(b) + "</untrusted>"
}

// problemText is what a failed lookup says, in the seller's terms (never an internal error).
func problemText(err error) string {
	var p *domain.Problem
	if errors.As(err, &p) && len(p.Errors) > 0 {
		// A refused argument says which one and why, so the model can fix it or tell the person.
		keys := make([]string, 0, len(p.Errors))
		for k := range p.Errors {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		parts := make([]string, 0, len(keys))
		for _, k := range keys {
			parts = append(parts, k+": "+p.Errors[k])
		}
		return strings.Join(parts, "; ")
	}
	if errors.As(err, &p) && p.Detail != "" {
		return p.Detail
	}
	return "That could not be read right now."
}

func argStr(a map[string]any, k string) string {
	s, _ := a[k].(string)
	return strings.TrimSpace(s)
}

func argInt(a map[string]any, k string, def, lo, hi int) int {
	f, ok := a[k].(float64)
	if !ok {
		return def
	}
	return min(hi, max(lo, int(f)))
}

func withStatus(base, status string) string {
	if status == "" {
		return base
	}
	return base + " (" + strings.ReplaceAll(status, "_", " ") + ")"
}

// ---------------------------------------------------------------- lookups

func toolMyOrders(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	status := strings.ToUpper(argStr(a, "status"))
	page, err := r.s.a.Orders.List(ctx, r.c, SellerTableQuery{Status: status, Size: argInt(a, "limit", maxRows, 1, maxRows)})
	if err != nil {
		return nil, err
	}
	rows := make([]map[string]any, 0, len(page.Items))
	for _, o := range page.Items {
		rows = append(rows, map[string]any{"orderNumber": o.OrderNumber, "shipmentId": o.ShipmentID, "placed": o.PlacedAt.UTC().Format("2006-01-02"),
			"items": o.ItemsSummary, "itemCount": o.ItemCount, "total": moneyLabel(o.TotalMinor), "fulfilment": o.Fulfilment,
			"status": o.StatusLabel, "nextSteps": o.NextActions, "canBuyLabel": o.CanBuyLabel})
	}
	return map[string]any{"orders": rows, "totalMatching": page.Total, "showing": len(rows)}, nil
}

func toolOrderDetail(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	number := argStr(a, "orderNumber")
	if number == "" {
		return nil, domain.ValidationProblem(map[string]string{"orderNumber": "Which order?"})
	}
	o, err := r.s.a.Orders.Get(ctx, r.c, number)
	if err != nil {
		return nil, err
	}
	ships := make([]map[string]any, 0, len(o.Shipments))
	for _, s := range o.Shipments {
		lines := make([]map[string]any, 0, len(s.Lines))
		for _, l := range s.Lines {
			lines = append(lines, map[string]any{"title": l.Title, "quantity": l.Quantity, "unitPrice": moneyLabel(l.UnitPriceMinor)})
		}
		m := map[string]any{"shipmentId": s.ShipmentID, "label": s.Label, "status": s.StatusLabel, "fulfilment": s.Fulfilment, "lines": lines,
			"total": moneyLabel(s.TotalMinor), "nextSteps": s.NextActions, "canBuyLabel": s.CanBuyLabel, "hasLabel": s.HasLabel}
		if s.Carrier != nil && s.TrackingNumber != nil {
			m["tracking"] = strings.ToUpper(*s.Carrier) + " " + *s.TrackingNumber
		}
		ships = append(ships, m)
	}
	return map[string]any{"orderNumber": o.OrderNumber, "placed": o.PlacedAt.UTC().Format("2006-01-02"), "status": o.Status, "shipments": ships}, nil
}

func toolMyListings(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	q := SellerTableQuery{Q: argStr(a, "query"), Status: strings.ToUpper(argStr(a, "status")), Size: argInt(a, "limit", maxRows, 1, maxRows)}
	page, err := r.s.a.Catalog.Listings(ctx, r.c, q)
	if err != nil {
		return nil, err
	}
	rows := make([]map[string]any, 0, len(page.Items))
	for _, l := range page.Items {
		row := map[string]any{"title": l.Title, "sku": l.SKU, "variants": l.VariantCount, "price": moneyLabel(l.PriceMinor), "stock": l.Stock,
			"status": l.StatusLabel, "fulfilment": l.Fulfilment}
		if l.ATIN != nil {
			row["atin"] = *l.ATIN
		}
		rows = append(rows, row)
	}
	return map[string]any{"listings": rows, "totalMatching": page.Total, "showing": len(rows)}, nil
}

func toolStockLevels(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	low, _ := a["lowOnly"].(bool)
	v, err := r.s.a.Catalog.Inventory(ctx, r.c, low)
	if err != nil {
		return nil, err
	}
	pos := v.Positions
	if len(pos) > maxStockRows {
		pos = pos[:maxStockRows]
	}
	rows := make([]map[string]any, 0, len(pos))
	for _, p := range pos {
		rows = append(rows, map[string]any{"sku": p.SKU, "onHand": p.OnHand, "reserved": p.Reserved, "available": p.Available, "inbound": p.Inbound, "low": p.LowStock})
	}
	return map[string]any{"positions": rows, "totals": map[string]any{"available": v.Totals.Available, "reserved": v.Totals.Reserved,
		"inbound": v.Totals.Inbound, "lowStockSkus": v.Totals.LowStockSKUs}, "showing": len(rows), "of": len(v.Positions)}, nil
}

func toolFeesAndPlan(ctx context.Context, r *assistantRun, _ map[string]any) (any, error) {
	v, err := r.s.a.Fees.Fees(ctx, r.c)
	if err != nil {
		return nil, err
	}
	cats := []map[string]any{}
	for i, c := range v.Current.Categories {
		if i >= 8 {
			break
		}
		cats = append(cats, map[string]any{"category": c.Label, "commission": bpsLabel(c.CommissionBps)})
	}
	out := map[string]any{"defaultCommission": bpsLabel(v.Current.DefaultCommissionBps), "categories": cats,
		"cardFee": bpsLabel(v.Current.CardPctBps) + " + " + moneyLabel(v.Current.CardFixedMinor), "fbaPerUnit": moneyLabel(v.Current.FbaPerUnitMinor),
		"effectiveFrom": v.Current.EffectiveFrom.UTC().Format("2006-01-02")}
	if v.PlanName != nil {
		out["plan"] = *v.PlanName
	}
	if v.Retail {
		out["note"] = "AfroTransact Retail pays no commission or fees."
	}
	if v.Upcoming != nil {
		out["upcomingFrom"] = v.Upcoming.EffectiveFrom.UTC().Format("2006-01-02")
	}
	return out, nil
}

func toolPayouts(ctx context.Context, r *assistantRun, _ map[string]any) (any, error) {
	v, err := r.s.a.Money.Payouts(ctx, r.c)
	if err != nil {
		return nil, err
	}
	recent := []map[string]any{}
	for i, p := range v.Payouts {
		if i >= 5 {
			break
		}
		recent = append(recent, map[string]any{"amount": moneyLabel(p.AmountMinor), "status": p.Status, "requested": p.RequestedAt})
	}
	b := v.Balance
	now := r.s.a.Now().UTC()
	// Held orders, soonest release first: this is the answer to "when will I get my money".
	holds := append([]domain.PayoutHold{}, b.Holds...)
	sort.SliceStable(holds, func(i, j int) bool { return holdRelease(holds[i]) < holdRelease(holds[j]) })
	held := []map[string]any{}
	for i, h := range holds {
		if i >= maxRows {
			break
		}
		row := map[string]any{"order": h.OrderID, "amount": moneyLabel(h.AmountMinor)}
		if t, ok := parseRelease(h); ok {
			row["releasedOn"] = t.Format("2006-01-02")
			if days := int(t.Sub(now).Hours()/24 + 0.999); days > 0 {
				row["daysLeft"] = days
			} else {
				row["daysLeft"] = 0
				row["note"] = "The hold has ended; the money moves to available within minutes (a few hours at most)."
			}
		} else {
			row["note"] = "Not released yet: the 7 days start when the order is delivered (or the service completed)."
		}
		held = append(held, row)
	}
	out := map[string]any{"available": moneyLabel(b.AvailableMinor), "onHold": moneyLabel(b.OnHoldMinor), "inTransit": moneyLabel(b.RequestedOrInTransitMinor),
		"paid": moneyLabel(b.PaidMinor), "minimumPayout": moneyLabel(b.MinimumPayoutMinor), "canRequestPayoutToday": b.CanRequestToday,
		"heldOrders": held, "heldOrdersTotal": len(b.Holds), "recentPayouts": recent,
		"rules": "Money is held for 7 days after delivery, then becomes available. The seller then requests a payout (minimum " + moneyLabel(b.MinimumPayoutMinor) + ", a limited number per day); the bank usually receives it in a few business days."}
	if b.PayoutsPaused {
		msg := ""
		if b.PayoutsPausedMessage != nil {
			msg = *b.PayoutsPausedMessage
		}
		out["payoutsPaused"] = msg
	}
	if b.ProviderFeesMinor > 0 {
		out["serviceFeesOwed"] = moneyLabel(b.ProviderFeesMinor)
	}
	if b.PlanFeesMinor > 0 {
		out["planFeesOwed"] = moneyLabel(b.PlanFeesMinor)
	}
	if b.AvailableMinor > 0 && b.AvailableMinor < b.MinimumPayoutMinor {
		out["belowMinimum"] = true
	}
	return out, nil
}

func parseRelease(h domain.PayoutHold) (time.Time, bool) {
	if h.ReleaseAt == nil || *h.ReleaseAt == "" {
		return time.Time{}, false
	}
	t, err := time.Parse(time.RFC3339, *h.ReleaseAt)
	return t.UTC(), err == nil
}

// holdRelease sorts holds by release time; holds with no date yet (not delivered) go last.
func holdRelease(h domain.PayoutHold) string {
	if t, ok := parseRelease(h); ok {
		return t.Format(time.RFC3339)
	}
	return "9999"
}

func toolAccountHealth(ctx context.Context, r *assistantRun, _ map[string]any) (any, error) {
	h, err := r.s.a.Shell.Compliance(ctx, r.c)
	if err != nil {
		return nil, err
	}
	items := []map[string]any{}
	for i, it := range h.Items {
		if i >= maxRows {
			break
		}
		items = append(items, map[string]any{"needed": it.Label, "urgency": it.Bucket})
	}
	out := map[string]any{"status": h.Status, "openItems": h.OpenItems, "needs": items, "chargesEnabled": h.ChargesEnabled,
		"payoutsEnabled": h.PayoutsEnabled, "payoutsPaused": h.PayoutsPaused, "listingsHidden": h.ListingsHidden}
	if h.Deadline != nil {
		out["deadline"] = h.Deadline.UTC().Format("2006-01-02")
	}
	if h.ShowBanner {
		out["summary"] = h.Title + ": " + h.Message
	}
	if h.PayoutBank != nil && h.PayoutBank.Last4 != nil {
		out["bankAccountEndingIn"] = *h.PayoutBank.Last4
	}
	out["whereToFix"] = "Seller Center > Account health"
	return out, nil
}

func toolSalesSummary(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	days := argInt(a, "days", 30, 7, 90)
	if days != 7 && days != 30 {
		days = 90
	}
	d, err := r.s.a.Dashboard.Dashboard(ctx, r.c, days)
	if err != nil {
		return nil, err
	}
	kpis := []map[string]any{}
	for _, k := range d.Kpis {
		kpis = append(kpis, map[string]any{"label": k.Label, "value": k.Value, "detail": k.Detail})
	}
	todo := []string{}
	for _, t := range d.Todo {
		todo = append(todo, t.Label)
	}
	return map[string]any{"days": d.RangeDays, "headline": kpis, "needsAttention": todo}, nil
}

func toolMyReturns(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	if r.s.a.Cases == nil {
		return map[string]any{"note": "Returns are not available right now."}, nil
	}
	list, err := r.s.a.Cases.Returns(ctx, r.c.BearerToken, strings.ToUpper(argStr(a, "status")))
	if err != nil {
		return nil, mapErr("tax-returns", err)
	}
	rows := make([]map[string]any, 0, maxRows)
	for i, x := range list {
		if i >= maxRows {
			break
		}
		lines := []map[string]any{}
		for _, l := range x.Lines {
			lines = append(lines, map[string]any{"item": l.Title, "quantity": l.Quantity, "reason": l.Reason})
		}
		rows = append(rows, map[string]any{"return": x.Number, "order": x.OrderNumber, "status": strings.ToLower(x.Status), "method": x.Method,
			"items": lines, "refund": moneyLabel(x.EstimateMinor), "refundState": x.RefundState, "started": datePart(x.CreatedAt)})
	}
	out := map[string]any{"returns": rows, "showing": len(rows), "of": len(list), "whereToFix": "Seller Center > Orders"}
	if len(list) == 0 {
		out["note"] = "No returns found."
	}
	return out, nil
}

func toolMyDisputes(ctx context.Context, r *assistantRun, a map[string]any) (any, error) {
	if r.s.a.Cases == nil {
		return map[string]any{"note": "Disputes are not available right now."}, nil
	}
	list, err := r.s.a.Cases.Claims(ctx, r.c.BearerToken, r.sc.org.ID, strings.ToUpper(argStr(a, "status")))
	if err != nil {
		return nil, mapErr("trust-ops", err)
	}
	rows := make([]map[string]any, 0, maxRows)
	for i, x := range list {
		if i >= maxRows {
			break
		}
		row := map[string]any{"reference": x.Reference, "type": x.Type, "order": x.OrderNumber, "amount": moneyLabel(x.AmountMinor),
			"status": x.Status, "escalated": x.Escalated, "opened": datePart(x.OpenedAt), "youResponded": x.SellerResponse != ""}
		if x.SLA != nil && x.Status == "AWAITING_SELLER" {
			row["respondBy"] = datePart(x.SLA.DueAt)
			row["deadlineState"] = x.SLA.State
		}
		if x.Description != "" {
			row["customerSays"] = x.Description
		}
		if x.Outcome != "" {
			row["outcome"] = x.Outcome
			if x.RefundMinor > 0 {
				row["refunded"] = moneyLabel(x.RefundMinor)
			}
		}
		rows = append(rows, row)
	}
	out := map[string]any{"disputes": rows, "showing": len(rows), "of": len(list)}
	if len(list) == 0 {
		out["note"] = "No disputes found."
	}
	return out, nil
}

// datePart is the date of an RFC 3339 timestamp ("" when absent).
func datePart(ts string) string {
	if len(ts) >= 10 {
		return ts[:10]
	}
	return ts
}

func dateLabel(t *time.Time) string {
	if t == nil {
		return ""
	}
	return t.UTC().Format("2006-01-02")
}

func toolPlanBilling(ctx context.Context, r *assistantRun, _ map[string]any) (any, error) {
	b, err := r.s.a.Money.Billing(ctx, r.c)
	if err != nil {
		return nil, err
	}
	sub := b.Subscription
	out := map[string]any{"plan": sub.PlanName, "cadence": sub.Cadence, "status": sub.Status, "price": sub.PriceLabel, "billed": sub.BilledLabel,
		"platformCredit": moneyLabel(sub.CreditBalanceMinor), "cancelsAtPeriodEnd": sub.CancelAtPeriodEnd}
	if sub.NextChargeLabel != "" {
		out["nextCharge"] = sub.NextChargeLabel
	}
	if d := dateLabel(sub.NextChargeAt); d != "" {
		out["nextChargeOn"] = d
	}
	if d := dateLabel(sub.TrialEndsAt); d != "" {
		out["trialEndsOn"] = d
	}
	if sub.Dunning != nil {
		out["paymentProblem"] = *sub.Dunning
	}
	if g := sub.ChargeGate; g != nil && g.Label != "" {
		out["chargingCondition"] = g.Label
	}
	if f := b.PlanFeeCollection; f != nil && f.Available {
		out["planPaidFrom"] = f.Effective
	}
	invoices := []map[string]any{}
	for i, inv := range b.Invoices {
		if i >= 5 {
			break
		}
		row := map[string]any{"number": inv.Number, "status": inv.Status, "due": moneyLabel(inv.AmountDueMinor), "paid": moneyLabel(inv.AmountPaidMinor),
			"created": inv.CreatedAt.UTC().Format("2006-01-02")}
		if inv.DiscountMinor > 0 {
			row["discount"] = moneyLabel(inv.DiscountMinor)
		}
		if d := dateLabel(inv.NextAttemptAt); d != "" {
			row["nextAttempt"] = d
		}
		if inv.LastError != nil {
			row["failure"] = *inv.LastError
		}
		invoices = append(invoices, row)
	}
	out["recentInvoices"] = invoices
	if rw, err := r.s.a.Money.Rewards(ctx, r.c); err == nil {
		progress := []string{}
		for i, a := range rw.Available {
			if i >= 3 {
				break
			}
			progress = append(progress, a.Label+": "+a.RewardLabel)
		}
		earned := []string{}
		for i, e := range rw.Earned {
			if i >= 3 {
				break
			}
			earned = append(earned, e.Summary)
		}
		out["rewardsInProgress"] = progress
		out["rewardsEarned"] = earned
	}
	out["whereToFix"] = "Seller Center > Plan & billing"
	return out, nil
}

func toolMyCoupons(ctx context.Context, r *assistantRun, _ map[string]any) (any, error) {
	list, err := r.s.a.Coupons.List(ctx, r.c)
	if err != nil {
		return nil, err
	}
	rows := make([]map[string]any, 0, maxRows)
	for i, c := range list {
		if i >= maxRows {
			break
		}
		off := moneyLabel(c.Value) + " off"
		if strings.Contains(strings.ToUpper(c.Type), "PERCENT") {
			off = fmt.Sprintf("%d%% off", c.Value)
		}
		row := map[string]any{"code": c.Code, "discount": off, "scope": c.Scope, "status": c.Status, "funding": c.Funding}
		if c.MinSpendMinor > 0 {
			row["minimumSpend"] = moneyLabel(c.MinSpendMinor)
		}
		rows = append(rows, row)
	}
	out := map[string]any{"coupons": rows, "total": len(list)}
	if len(list) == 0 {
		out["note"] = "The seller has no coupons."
	}
	return out, nil
}

func bpsLabel(bps int) string { return fmt.Sprintf("%.1f%%", float64(bps)/100) }

// ---------------------------------------------------------------- proposals (nothing is done here)

func proposed(msg string) map[string]any {
	return map[string]any{"status": "PROPOSED", "message": msg + " The seller sees a button and must tap it to confirm. Do not say it is done."}
}

func toolProposeStock(_ context.Context, r *assistantRun, a map[string]any) (any, error) {
	sku := strings.ToUpper(argStr(a, "sku"))
	q, ok := a["quantity"].(float64)
	if sku == "" || len(sku) > 64 || !ok || q < 0 || q > 100000 || q != float64(int(q)) {
		return nil, domain.ValidationProblem(map[string]string{"quantity": "Give a SKU and a whole quantity from 0 to 100,000"})
	}
	n := int(q)
	r.proposals = append(r.proposals, domain.AssistantProposal{Kind: "SET_STOCK", Label: fmt.Sprintf("Set %s to %d", sku, n), SKU: sku, Quantity: &n})
	return proposed(fmt.Sprintf("Stock of %s set to %d.", sku, n)), nil
}

func toolProposeNext(_ context.Context, r *assistantRun, a map[string]any) (any, error) {
	order, ship, step := argStr(a, "orderNumber"), argStr(a, "shipmentId"), strings.ToUpper(argStr(a, "step"))
	if order == "" || ship == "" || !slices.Contains([]string{"CONFIRMED", "PACKED", "READY"}, step) {
		return nil, domain.ValidationProblem(map[string]string{"step": "Give the order, the shipment and CONFIRMED, PACKED or READY"})
	}
	verb := map[string]string{"CONFIRMED": "Accept", "PACKED": "Mark packed", "READY": "Mark ready"}[step]
	r.proposals = append(r.proposals, domain.AssistantProposal{Kind: "NEXT_STEP", Label: verb + " · " + order, OrderNumber: order, ShipmentID: ship, Step: step})
	return proposed("Order " + order + " moved to " + strings.ToLower(step) + "."), nil
}
