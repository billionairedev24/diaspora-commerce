package app

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var moderationReasonCodes = domain.StaffOptions("PROHIBITED_ITEM", "UNAPPROVED_HEALTH_CLAIM", "RESTRICTED_INGREDIENT", "COUNTERFEIT", "IMAGE_POLICY",
	"MISLEADING_CONTENT", "LABELLING", "SHIPPING_METHOD", "DUPLICATE_LISTING", "WRONG_CATEGORY", "OTHER")

// ---------------------------------------------------------------- orders & shipments (orders)

func ordersResource() *staffResource {
	sellers := func(o jobj) string {
		names := []string{}
		for _, s := range objs(o, "shipments") {
			if n := jstr(s, "sellerName"); n != "" && !strings.Contains(strings.Join(names, "|"), n) {
				names = append(names, n)
			}
		}
		return strings.Join(names, ", ")
	}
	return &staffResource{
		key: "orders", module: "orders", title: "Orders", statusKey: "status", exportable: true, searchPlaceholder: "Order number (AT-…)",
		columns: []domain.Column{col("orderNumber", "Order", "mono", 13), col("customerName", "Customer", "strong", 18), col("sellers", "Sellers", "text", 20),
			col("totalMinor", "Total", "money", 11), col("placedAt", "Placed", "datetime", 16), col("shipTo", "Ship to", "text", 12), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PENDING_PAYMENT", "PLACED", "PARTIALLY_SHIPPED", "COMPLETED", "CANCELLED"),
		cells: func(o jobj) map[string]any {
			return map[string]any{"sellers": cellValue(sellers(o))}
		},
		names: []nameRef{customerRef("customerId").orFrom("shippingAddress.fullName"), sellerRef("sellerOrgId").inList("shipments").named("sellerName")},
		idOf:  func(o jobj) string { return jstr(o, "orderNumber") },
		list: func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
			v := url.Values{"limit": {strconv.Itoa(q.Size)}}
			if q.Status != "" {
				v.Set("status", q.Status)
			}
			if q.Q != "" {
				v.Set("number", strings.ToUpper(q.Q))
			}
			if q.Cursor != "" {
				v.Set("cursor", q.Cursor)
			}
			var raw any
			if err := sc.get(ctx, sc.up.Orders, "/v1/admin/orders", v, &raw); err != nil {
				return staffList{}, err
			}
			return jpage(raw), nil
		},
		get: func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
			o, err := getByPath("/v1/orders/", upOrders)(ctx, sc, id)
			if err != nil {
				return nil, err
			}
			enrichStaffProof(ctx, sc.up.Proof, o)
			return o, nil
		},
		titleOf: func(o jobj) string { return "Order " + jstr(o, "orderNumber") },
		subtitleOf: func(o jobj) string {
			return strings.Trim(jstr(o, "customerName")+" · "+jstr(o, "shipTo"), " ·")
		},
		sections: func(o jobj) []domain.DetailSection {
			return append(autoSections(o, "status", "shipments"), proofSections(o)...)
		},
		tables: func(o jobj) []domain.DetailTable {
			lines := []jobj{}
			for _, s := range objs(o, "shipments") {
				for _, l := range objs(s, "lines") {
					l["shipment"] = jstr(s, "label")
					lines = append(lines, l)
				}
			}
			return []domain.DetailTable{
				tableOf("Shipments", objs(o, "shipments"), []domain.Column{col("label", "Shipment", "strong", 14), col("sellerName", "Seller", "text", 20),
					col("status", "Status", "status", 16), col("carrier", "Carrier", "text", 12), col("trackingNumber", "Tracking", "mono", 22), col("totalMinor", "Total", "money", 16)}, "id"),
				tableOf("Items", lines, []domain.Column{col("shipment", "Shipment", "text", 14), col("title", "Item", "strong", 34), col("category", "Department", "text", 18),
					col("quantity", "Qty", "number", 10), col("itemsMinor", "Amount", "money", 24)}, "id"),
			}
		},
		activity: func(_ context.Context, _ staffCtx, o jobj) []domain.ActivityEntry {
			out := []domain.ActivityEntry{}
			for _, s := range objs(o, "shipments") {
				for _, t := range objs(s, "timeline") {
					text := jstr(s, "label") + ": " + strings.ToLower(domain.Humanize(jstr(t, "to")))
					if r := jstr(t, "reason"); r != "" {
						text += " — " + r
					}
					if p := proofSummary(t); p != "" {
						text += " (" + p + ")"
					}
					out = append(out, domain.ActivityEntry{At: timeOf(t, "at"), Actor: firstNonEmpty(jstr(t, "source"), jstr(t, "actorType")), Text: text})
				}
			}
			return out
		},
		links: func(o jobj) []domain.RecordLink {
			links := []domain.RecordLink{{Label: "Open in customer support", Href: "/support?customer=" + url.QueryEscape(jstr(o, "customerId"))}}
			// An AfroTransact driver takes it from the dock: assign one in Delivery dispatch, then the driver app does the rest
			// (scan at pickup, the route, proof of delivery).
			for _, sh := range objs(o, "shipments") {
				st := strings.ToUpper(jstr(sh, "status"))
				if strings.EqualFold(jstr(sh, "deliveryMethod"), domain.AfroTransactDelivery) && (st == "READY" || st == "PACKED") {
					links = append(links, domain.RecordLink{Label: "Assign a driver", Href: "/dispatch?tab=pickups"})
					break
				}
			}
			return links
		},
		// The steps people take every day are one tap each ("Mark packed", "Mark ready"); the form below is for the rare
		// ones: cancel, a failed delivery, handing over a pickup order with the customer's code.
		actionsFor: shipmentNextStepActions,
		actions: []staffAction{
			{key: "shipment-status", label: "More options", tone: "neutral", permission: "orders:manage", also: []string{"orders:update-status"},
				form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
					return &domain.Form{Title: "What happened to the shipment?", SubmitLabel: "Save", Fields: []domain.FormField{
						withOptions(ff("step", "Next step", "select", true), shipmentSteps(o)),
						withMax(ff("reason", "Reason or note", "text", false), 500),
						withPattern(ff("handoverCode", "Customer's code (handing over a pickup order)", "text", false), `^\d{4}$`, "The 4-digit code the customer shows. Without it, say why in the reason.")}}
				},
				when: func(_ staffCtx, o jobj) string {
					if len(objs(o, "shipments")) == 0 {
						return "This order has no shipments."
					}
					if len(shipmentSteps(o)) == 0 {
						return "No shipment steps are available for your role on this order."
					}
					return ""
				},
				run: func(ctx context.Context, sc staffCtx, _ jobj, v jobj) error {
					shipmentID, to, ok := strings.Cut(vs(v, "step"), "|")
					if !ok || shipmentID == "" || to == "" {
						return domain.ValidationProblem(map[string]string{"step": "Choose the next step"})
					}
					code := strings.TrimSpace(vs(v, "handoverCode"))
					if code != "" && !domain.IsHandoverCode(code) {
						return domain.ValidationProblem(map[string]string{"handoverCode": "Enter the 4 digits"})
					}
					if reasonRequired(to) && strings.TrimSpace(vs(v, "reason")) == "" && !(to == "DELIVERED" && code != "") {
						return domain.ValidationProblem(map[string]string{"reason": "Say why (required to " + strings.ToLower(shipmentStepVerbs[to]) + ")"})
					}
					return sc.post(ctx, sc.up.Orders, "/v1/shipments/"+url.PathEscape(shipmentID)+"/transitions", omitEmpty(jobj{"to": to, "reason": vs(v, "reason"), "handoverCode": code}), nil)
				}},
		},
	}
}

// driverSteps are the steps an AfroTransact driver takes in the driver app (scan at pickup, route, proof of delivery), so
// staff do not tap them for an AfroTransact Delivery shipment.
var driverSteps = map[string]bool{"PICKED_UP": true, "IN_TRANSIT": true, "OUT_FOR_DELIVERY": true, "DELIVERED": true}

// shipmentNextStepActions gives each shipment its one next step as a single button, with no form. A step that needs a
// reason or the customer's code (delivered), exceptions (cancel, failed, returned) and the driver's own steps are left out.
func shipmentNextStepActions(o jobj) []staffAction {
	ships := objs(o, "shipments")
	out := []staffAction{}
	for _, s := range ships {
		id := firstNonEmpty(jstr(s, "shipmentId"), jstr(s, "id"))
		next := map[string]bool{}
		for _, a := range strs(s, "nextActions") {
			next[strings.ToUpper(a)] = true
		}
		driverOwned := strings.EqualFold(jstr(s, "deliveryMethod"), domain.AfroTransactDelivery)
		for _, step := range shipmentLifecycle {
			if !next[step] || reasonRequired(step) || step == "RETURNED" || (driverOwned && driverSteps[step]) {
				continue
			}
			label := shipmentStepVerbs[step]
			if len(ships) > 1 {
				label += " · " + jstr(s, "label")
			}
			shipmentID, to := id, step
			out = append(out, staffAction{
				key: "step:" + shipmentID + ":" + to, label: label, tone: "primary", permission: "orders:manage", also: []string{"orders:update-status"},
				run: func(ctx context.Context, sc staffCtx, _ jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Orders, "/v1/shipments/"+url.PathEscape(shipmentID)+"/transitions", jobj{"to": to}, nil)
				}})
			break // one next step per shipment
		}
		if hint := driverHint(jstr(s, "status"), driverOwned); hint != "" {
			label := "Waiting for the driver"
			if len(ships) > 1 {
				label += " · " + jstr(s, "label")
			}
			// A button that cannot be pressed: it says who moves this shipment next and where.
			out = append(out, staffAction{key: "driver:" + id, label: label, tone: "neutral", permission: "orders:manage", also: []string{"orders:update-status"},
				when: func(staffCtx, jobj) string { return hint },
				run:  func(context.Context, staffCtx, jobj, jobj) error { return nil }})
		}
	}
	return out
}

// driverHint says what happens next to an AfroTransact Delivery shipment that is with the driver side of the flow.
func driverHint(status string, driverOwned bool) string {
	if !driverOwned {
		return ""
	}
	switch strings.ToUpper(status) {
	case "READY":
		return "Assign a driver in Delivery dispatch. The driver scans the packages at pickup in the driver app, which marks it picked up."
	case "PICKED_UP", "IN_TRANSIT":
		return "With the driver. It becomes out for delivery when the driver starts the route in the driver app."
	case "OUT_FOR_DELIVERY":
		return "Out with the driver. It becomes delivered when the driver records proof in the driver app (the customer's code, a signature or a photo). To confirm it yourself, use More options and give a reason."
	}
	return ""
}

// shipmentLifecycle is the order of the steps staff may take on a shipment (orders-service state machine).
var shipmentLifecycle = []string{"CONFIRMED", "PACKED", "READY", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED",
	"DELIVERY_FAILED", "RETURNED", "CANCELLED"}

// shipmentStepVerbs names each step as the action a person takes, not the status it leaves behind.
var shipmentStepVerbs = map[string]string{
	"CONFIRMED": "Confirm", "PACKED": "Mark packed", "READY": "Mark ready", "PICKED_UP": "Mark picked up",
	"IN_TRANSIT": "Mark in transit", "OUT_FOR_DELIVERY": "Mark out for delivery", "DELIVERED": "Mark delivered",
	"DELIVERY_FAILED": "Record a failed delivery", "RETURNED": "Mark returned", "CANCELLED": "Cancel the shipment",
}

// reasonRequired: the steps orders-service wants a reason for (a cancellation, a failure, a delivery confirmed by hand).
func reasonRequired(to string) bool {
	return to == "CANCELLED" || to == "DELIVERY_FAILED" || to == "DELIVERED"
}

// shipmentSteps lists, for each shipment, the steps the caller may take on it next (the shipment's own nextActions:
// orders-service applies the state machine for the caller), one choice per shipment and step, as "<shipment> · <step>".
// The value is "<shipmentId>|<STATUS>".
func shipmentSteps(o jobj) []domain.FieldOption {
	out := []domain.FieldOption{}
	multi := len(objs(o, "shipments")) > 1
	for _, s := range objs(o, "shipments") {
		next := map[string]bool{}
		for _, a := range strs(s, "nextActions") {
			next[strings.ToUpper(a)] = true
		}
		id := firstNonEmpty(jstr(s, "shipmentId"), jstr(s, "id"))
		for _, step := range shipmentLifecycle {
			if !next[step] {
				continue
			}
			label := shipmentStepVerbs[step]
			if multi {
				label = jstr(s, "label") + " · " + label
			}
			out = append(out, domain.FieldOption{Value: id + "|" + step, Label: label})
		}
	}
	return out
}

// ---------------------------------------------------------------- products (catalog)

func productsResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.Catalog, "/v1/products/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	return &staffResource{
		key: "products", module: "catalog", title: "Products", statusKey: "status", exportable: true, searchPlaceholder: "Title or ATIN",
		columns: []domain.Column{col("atin", "ATIN", "mono", 15), col("title", "Product", "strong", 30), col("ownerOrgName", "Seller", "text", 20),
			col("productType", "Type", "text", 15), col("status", "Status", "status", 20)},
		facets: domain.StaffFacets("IN_REVIEW", "PENDING_CATEGORY_APPROVAL", "LIVE", "PAUSED", "REJECTED", "TAKEN_DOWN"),
		list: listPaged("/v1/products", upCatalog, func(q staffQuery, v url.Values) {
			if q.Q != "" {
				v.Set("q", q.Q)
			}
		}),
		get:        getByPath("/v1/products/", upCatalog),
		names:      []nameRef{sellerRef("ownerOrgId")},
		titleOf:    func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string { return jstr(o, "atin") + " · " + jstr(o, "ownerOrgName") },
		sections: func(o jobj) []domain.DetailSection {
			secs := autoSections(o, "status", "variants", "images")
			// What the shopper sees first: the listing's photos (reviewers cannot judge a listing without them).
			photos := domain.DetailSection{Title: "Photos", Fields: []domain.DetailField{}}
			for i, img := range objs(o, "images") {
				if u := jstr(img, "url"); strings.HasPrefix(u, "https://") || strings.HasPrefix(u, "http://") {
					photos.Fields = append(photos.Fields, domain.DetailField{Label: "Photo " + strconv.Itoa(i+1), Value: u, Kind: "image"})
				}
			}
			if len(photos.Fields) > 0 {
				secs = append([]domain.DetailSection{photos}, secs...)
			}
			return secs
		},
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Variants", objs(o, "variants"), []domain.Column{col("atin", "ATIN", "mono", 22), col("sellerSku", "SKU", "mono", 22),
				col("priceMinor", "Price", "money", 16), col("quantity", "In stock", "number", 14)}, "atin")}
		},
		actions: []staffAction{
			{key: "approve", label: "Approve", tone: "primary", permission: "catalog:moderate", confirm: "Approve this product and make it live?", when: inState("IN_REVIEW"),
				run: act("/approve", func(jobj) jobj { return jobj{} })},
			{key: "reject", label: "Reject", tone: "danger", permission: "catalog:moderate", form: noteForm("Reject the product", "Reject", true), when: inState("IN_REVIEW"),
				run: act("/reject", func(v jobj) jobj { return jobj{"note": vs(v, "note")} })},
			{key: "takedown", label: "Take down", tone: "danger", permission: "catalog:moderate", when: inState("LIVE", "PAUSED"),
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Take the listing down", SubmitLabel: "Take down", Fields: []domain.FormField{
						withOptions(ff("reasonCode", "Reason", "select", true), moderationReasonCodes), withMax(ff("note", "Note", "textarea", false), 1000)}}
				},
				run: act("/takedown", func(v jobj) jobj { return omitEmpty(jobj{"reasonCode": vs(v, "reasonCode"), "note": vs(v, "note")}) })},
			{key: "restore", label: "Restore", tone: "neutral", permission: "catalog:moderate", form: noteForm("Restore the listing", "Restore", false), when: inState("TAKEN_DOWN"),
				run: act("/restore", func(v jobj) jobj { return omitEmpty(jobj{"note": vs(v, "note")}) })},
		},
	}
}

// ---------------------------------------------------------------- moderation queue (trust-ops)

func moderationResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.TrustOps, "/v1/moderation/items/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	return &staffResource{
		key: "moderation", module: "catalog", title: "Moderation queue", statusKey: "status", exportable: true,
		columns: []domain.Column{col("title", "Item", "strong", 28), col("sellerOrgName", "Seller", "text", 16), col("type", "Type", "text", 14),
			col("priority", "Priority", "text", 10), col("sla.dueAt", "Due", "datetime", 14), col("assigneeName", "Assignee", "text", 12), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PENDING", "ESCALATED", "APPROVED", "REJECTED", "CLOSED"),
		list:   liftedList(listPaged("/v1/moderation/items", upTrustOps, nil)),
		get:    liftedGet(getByPath("/v1/moderation/items/", upTrustOps)),
		// Reviews (booking and product) show their author by name: liftReviewAuthor puts the id where the console resolves it.
		names: []nameRef{sellerRef("sellerOrgId"), customerRef("customerId"), personRef("assignee", "Assignee"), personRef("decidedBy", "Decided by")},
		sections: func(o jobj) []domain.DetailSection {
			if jstr(o, "review.reviewerName") != "" {
				return reviewItemSections(o)
			}
			return autoSections(o, "status")
		},
		titleOf:    func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string { return domain.Humanize(jstr(o, "type")) + " · " + jstr(o, "atin") },
		actions: []staffAction{
			{key: "assign", label: "Assign to me", tone: "neutral", permission: "catalog:moderate", confirm: "Take this item?", when: inState("PENDING", "ESCALATED"),
				run: act("/assign", func(jobj) jobj { return jobj{} })},
			{key: "approve", label: "Allow", tone: "primary", permission: "catalog:moderate", level: domain.LevelApprove, form: moderationNoteForm, when: inState("PENDING", "ESCALATED"),
				run: act("/approve", func(v jobj) jobj { return omitEmpty(jobj{"note": vs(v, "note")}) })},
			{key: "reject", label: "Remove", tone: "danger", permission: "catalog:moderate", level: domain.LevelApprove, when: inState("PENDING", "ESCALATED"),
				form: moderationRemoveForm,
				run:  act("/reject", func(v jobj) jobj { return omitEmpty(jobj{"reasonCode": vs(v, "reasonCode"), "note": vs(v, "note")}) })},
			{key: "escalate", label: "Escalate", tone: "neutral", permission: "catalog:moderate", form: reasonForm("Escalate to senior review", "Escalate"), when: inState("PENDING"),
				run: act("/escalate", func(v jobj) jobj { return jobj{"reason": vs(v, "reason")} })},
		},
	}
}

// ---------------------------------------------------------------- inventory lots (inventory)

func inventoryResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.post(ctx, sc.up.Inventory, "/v1/lots/"+url.PathEscape(jstr(o, "id"))+path, body(v), nil)
		}
	}
	return &staffResource{
		key: "inventory", module: "inventory", title: "Lots", statusKey: "status", exportable: true, searchPlaceholder: "SKU",
		columns: []domain.Column{col("sku", "SKU", "mono", 12), col("lotNo", "Lot", "mono", 13), col("nodeId", "Centre", "text", 10), col("qtyOnHand", "On hand", "number", 8),
			col("qtyReserved", "Reserved", "number", 8), col("qtyAvailable", "Available", "number", 8), col("costPaid", "Unit cost paid", "text", 13),
			col("unitCostUsdMinor", "Unit cost (USD)", "money", 10), col("expDate", "Expires", "date", 10), col("status", "Status", "status", 8)},
		facets: domain.StaffFacets("SELLABLE", "QUARANTINED", "DAMAGED", "EXPIRED"),
		// Stock is received in the currency actually paid; the table shows it with the rate and the USD value alongside.
		cells: func(o jobj) map[string]any {
			paid := formatPaid(int64(num(o, "unitLandedCostMinor")), jstr(o, "currency"))
			if jstr(o, "currency") != "USD" {
				paid += " @ " + jstr(o, "fxRateToUsd")
			}
			return map[string]any{"costPaid": paid}
		},
		list: listPaged("/v1/lots", upInventory, func(q staffQuery, v url.Values) {
			if q.Q != "" {
				v.Set("sku", strings.ToUpper(q.Q))
			}
		}),
		get:        getByPath("/v1/lots/", upInventory),
		titleOf:    func(o jobj) string { return jstr(o, "sku") + " · " + jstr(o, "lotNo") },
		subtitleOf: func(o jobj) string { return jstr(o, "nodeId") + " · bin " + jstr(o, "bin") },
		version:    func(o jobj) string { return jstr(o, "version") },
		actions: []staffAction{
			{key: "cycle-count", label: "Cycle count", tone: "primary", permission: "inventory:adjust",
				form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
					return &domain.Form{Title: "Post a counted quantity", SubmitLabel: "Post count", Fields: []domain.FormField{
						withValue(withRange(ff("countedQty", "Counted quantity", "number", true), 1_000_000), num(o, "qtyOnHand")),
						withMax(ff("reason", "Reason", "text", true), 200)}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Inventory, "/v1/lots/"+url.PathEscape(jstr(o, "id"))+"/adjust", jobj{"countedQty": int(vn(v, "countedQty")), "reason": vs(v, "reason")}, nil)
				}},
			{key: "quarantine", label: "Quarantine", tone: "danger", permission: "inventory:adjust", form: reasonForm("Quarantine the lot", "Quarantine"), when: inState("SELLABLE"),
				run: act("/quarantine", func(v jobj) jobj { return jobj{"reason": vs(v, "reason")} })},
			{key: "release", label: "Release to sale", tone: "neutral", permission: "inventory:adjust", form: reasonForm("Release after inspection", "Release"), when: inState("QUARANTINED", "DAMAGED"),
				run: act("/release", func(v jobj) jobj { return jobj{"reason": vs(v, "reason")} })},
		},
	}
}

// ---------------------------------------------------------------- retail purchase orders (retail)

func purchaseOrdersResource() *staffResource {
	act := func(path string, body func(v jobj) jobj) func(context.Context, staffCtx, jobj, jobj) error {
		return func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
			return sc.call(ctx, sc.up.Retail, port.StaffCall{Method: http.MethodPost, Path: "/v1/purchase-orders/" + url.PathEscape(jstr(o, "id")) + path,
				Body: body(v), IfMatch: jstr(o, "version")}, nil)
		}
	}
	note := func(v jobj) jobj { return omitEmpty(jobj{"note": vs(v, "note")}) }
	return &staffResource{
		key: "purchase-orders", module: "retail", title: "Purchase orders", statusKey: "status", exportable: true,
		columns: []domain.Column{col("number", "PO", "mono", 12), col("supplierCode", "Supplier", "strong", 20), col("destinationNodeId", "Ship to", "text", 14),
			col("expectedDate", "Expected", "date", 13), col("totalMinor", "Total", "money", 14), col("approvalTier", "Approval", "text", 10), col("status", "Status", "status", 17)},
		facets:     domain.StaffFacets("DRAFT", "PENDING_APPROVAL", "APPROVED", "SENT", "CONFIRMED", "RECEIVED", "CLOSED", "CANCELLED"),
		list:       listPaged("/v1/purchase-orders", upRetail, nil),
		get:        getByPath("/v1/purchase-orders/", upRetail),
		names:      []nameRef{personRef("createdBy", "Created by"), personRef("approvedBy", "Approved by"), personRef("by", "By").inList("history")},
		titleOf:    func(o jobj) string { return jstr(o, "number") + " · " + jstr(o, "supplierCode") },
		subtitleOf: func(o jobj) string { return jstr(o, "incoterm") + " → " + jstr(o, "destinationNodeId") },
		version:    func(o jobj) string { return jstr(o, "version") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "status", "lines", "history") },
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Lines", objs(o, "lines"), []domain.Column{col("sku", "SKU", "mono", 16), col("description", "Item", "strong", 30),
				col("qtyOrdered", "Ordered", "number", 12), col("qtyReceived", "Received", "number", 12), col("unitCostMinor", "Unit cost", "money", 14), col("amountMinor", "Amount", "money", 16)}, "lineNo")}
		},
		activity: func(_ context.Context, _ staffCtx, o jobj) []domain.ActivityEntry {
			out := []domain.ActivityEntry{}
			for _, h := range objs(o, "history") {
				text := domain.Humanize(jstr(h, "status"))
				if n := jstr(h, "note"); n != "" {
					text += " — " + n
				}
				out = append(out, domain.ActivityEntry{At: timeOf(h, "at"), Actor: jstr(h, "byName"), Text: text})
			}
			return out
		},
		actions: []staffAction{
			{key: "submit", label: "Submit for approval", tone: "primary", permission: "retail:po:write", confirm: "Submit this PO for approval?", when: inState("DRAFT"), run: act("/submit", func(jobj) jobj { return nil })},
			{key: "approve", label: "Approve", tone: "primary", permission: "retail:po:approve", level: domain.LevelApprove, form: noteForm("Approve the PO", "Approve", false), when: inState("PENDING_APPROVAL"), run: act("/approve", note)},
			{key: "send-back", label: "Send back", tone: "neutral", permission: "retail:po:approve", level: domain.LevelApprove, form: noteForm("Send back to the buyer", "Send back", true), when: inState("PENDING_APPROVAL"), run: act("/send-back", note)},
			{key: "cancel", label: "Cancel PO", tone: "danger", permission: "retail:po:write", form: noteForm("Cancel the PO", "Cancel PO", true), when: inState("DRAFT", "PENDING_APPROVAL", "APPROVED", "SENT", "CONFIRMED"), run: act("/cancel", note)},
		},
	}
}

// ---------------------------------------------------------------- dispatch routes (fulfilment)

func routesResource() *staffResource {
	return &staffResource{
		key: "routes", module: "dispatch", title: "Routes", statusKey: "status", exportable: true,
		columns: []domain.Column{col("name", "Route", "mono", 16), col("zone", "Zone", "text", 14), col("window", "Window", "text", 12), col("driverName", "Driver", "strong", 16),
			col("stopCount", "Stops", "number", 8), col("serviceDate", "Date", "date", 12), col("status", "Status", "status", 22)},
		cells:      func(o jobj) map[string]any { return map[string]any{"stopCount": float64(len(objs(o, "stops")))} },
		facets:     domain.StaffFacets("PLANNED", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "CANCELLED"),
		list:       listLimited("/v1/dispatch/routes", upFulfilment),
		get:        getByPath("/v1/dispatch/routes/", upFulfilment),
		names:      []nameRef{driverRef("driverId")},
		create:     buildRoutesCreate(),
		titleOf:    func(o jobj) string { return "Route " + firstNonEmpty(jstr(o, "name"), jstr(o, "id")) },
		subtitleOf: func(o jobj) string { return jstr(o, "zone") + " · " + jstr(o, "window") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "status", "stops", "packages") },
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{
				tableOf("Stops", objs(o, "stops"), []domain.Column{col("sequence", "#", "number", 8), col("name", "Stop", "strong", 22), col("addressLine", "Address", "text", 30),
					col("orderNumber", "Order", "mono", 16), col("status", "Status", "status", 24)}, "id"),
				tableOf("Packages", objs(o, "packages"), []domain.Column{col("label", "Package", "strong", 40), col("orderNumber", "Order", "mono", 30), col("status", "Status", "status", 30)}, "id"),
			}
		},
		actions: []staffAction{
			{key: "assign", label: "Assign driver", tone: "primary", permission: "delivery:dispatch", when: inState("PLANNED", "ASSIGNED"),
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Assign a driver", SubmitLabel: "Assign", Fields: []domain.FormField{withLookup(withMax(ff("driverId", "Driver", "text", true), 64), lookupDrivers), withMax(ff("vehicleId", "Vehicle", "text", false), 40)}}
				},
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Fulfilment, "/v1/dispatch/routes/"+url.PathEscape(jstr(o, "id"))+"/assign", omitEmpty(jobj{"driverId": vs(v, "driverId"), "vehicleId": vs(v, "vehicleId")}), nil)
				}},
			{key: "cancel", label: "Cancel route", tone: "danger", permission: "delivery:dispatch", form: reasonForm("Cancel the route (shipments go back to the pool)", "Cancel route"), when: inState("PLANNED", "ASSIGNED"),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Fulfilment, "/v1/dispatch/routes/"+url.PathEscape(jstr(o, "id"))+"/cancel", jobj{"reason": vs(v, "reason")}, nil)
				}},
		},
	}
}

// proofSummary is the driver's proof of delivery on a timeline entry in one line: how it was handed over, to whom or
// where, and which evidence was captured ("handed to the customer, code checked, photo"). Empty when there is none.
func proofSummary(t jobj) string {
	if jstr(t, "proof.handoverMethod") == "" && jstr(t, "proof.failureReason") == "" && jstr(t, "proof.photoAssetId") == "" {
		return ""
	}
	parts := []string{}
	switch jstr(t, "proof.handoverMethod") {
	case "CUSTOMER":
		parts = append(parts, "handed to the customer, code checked")
	case "SOMEONE_ELSE":
		who := jstr(t, "proof.recipientName")
		if rel := jstr(t, "proof.relationship"); rel != "" {
			who += ", " + strings.ToLower(rel)
		}
		parts = append(parts, "handed to "+firstNonEmpty(strings.TrimSpace(who), "someone else"))
	case "SAFE_PLACE":
		parts = append(parts, "left at "+firstNonEmpty(strings.ToLower(jstr(t, "proof.safePlace")), "a safe place"))
	}
	if r := jstr(t, "proof.failureReason"); r != "" {
		parts = append(parts, "driver: "+r)
	}
	if jstr(t, "proof.photoAssetId") != "" {
		parts = append(parts, "photo")
	}
	if jstr(t, "proof.signatureAssetId") != "" {
		parts = append(parts, "signature")
	}
	return strings.Join(parts, ", ")
}

// moderationNoteForm asks for the optional note of "Allow"; the title says what is allowed.
func moderationNoteForm(ctx context.Context, sc staffCtx, o jobj) *domain.Form {
	title := "Allow the listing"
	if strings.HasSuffix(jstr(o, "type"), "_REVIEW") {
		title = "Allow the review"
	}
	return noteForm(title, "Allow", false)(ctx, sc, o)
}

// moderationRemoveForm asks why an item is removed: listing codes for listings, review codes for reviews (trust-ops
// refuses a code that does not fit the kind of item).
func moderationRemoveForm(_ context.Context, _ staffCtx, o jobj) *domain.Form {
	title, codes := "Remove the listing", moderationReasonCodes
	if strings.HasSuffix(jstr(o, "type"), "_REVIEW") {
		title, codes = "Remove the review", reviewReasonCodes
	}
	return &domain.Form{Title: title, SubmitLabel: "Remove", Fields: []domain.FormField{
		withOptions(ff("reasonCode", "Reason", "select", true), codes), withMax(ff("note", "Note", "textarea", false), 1000)}}
}
