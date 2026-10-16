package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"

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
		name: domain.ToolPayouts, description: "The payout balance (available, on hold, paid) and the latest payouts.",
		schema: noArgsSchema,
		label:  func(map[string]any) string { return "Checking your payouts" },
		run:    toolPayouts,
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
	return map[string]any{"available": moneyLabel(b.AvailableMinor), "onHold": moneyLabel(b.OnHoldMinor), "inTransit": moneyLabel(b.RequestedOrInTransitMinor),
		"paid": moneyLabel(b.PaidMinor), "minimumPayout": moneyLabel(b.MinimumPayoutMinor), "recentPayouts": recent,
		"note": "Money is held for 7 days after delivery, then becomes available."}, nil
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
