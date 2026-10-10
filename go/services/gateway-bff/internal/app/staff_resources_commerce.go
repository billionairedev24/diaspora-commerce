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
		columns: []domain.Column{col("orderNumber", "Order", "mono", 13), col("customer", "Customer", "strong", 18), col("sellers", "Sellers", "text", 20),
			col("totalMinor", "Total", "money", 11), col("placedAt", "Placed", "datetime", 16), col("shipTo", "Ship to", "text", 12), col("status", "Status", "status", 10)},
		facets: domain.StaffFacets("PENDING_PAYMENT", "PLACED", "PARTIALLY_SHIPPED", "COMPLETED", "CANCELLED"),
		cells: func(o jobj) map[string]any {
			return map[string]any{"customer": cellValue(firstNonEmpty(jstr(o, "shippingAddress.fullName"), jstr(o, "customerId"))), "sellers": cellValue(sellers(o))}
		},
		idOf: func(o jobj) string { return jstr(o, "orderNumber") },
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
		get:     getByPath("/v1/orders/", upOrders),
		titleOf: func(o jobj) string { return "Order " + jstr(o, "orderNumber") },
		subtitleOf: func(o jobj) string {
			return strings.Trim(firstNonEmpty(jstr(o, "shippingAddress.fullName"), jstr(o, "customerId"))+" · "+jstr(o, "shipTo"), " ·")
		},
		sections: func(o jobj) []domain.DetailSection {
			return autoSections(o, "status", "shipments")
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
					out = append(out, domain.ActivityEntry{At: timeOf(t, "at"), Actor: firstNonEmpty(jstr(t, "source"), jstr(t, "actorType")), Text: text})
				}
			}
			return out
		},
		links: func(o jobj) []domain.RecordLink {
			return []domain.RecordLink{{Label: "Open in customer support", Href: "/support?customer=" + url.QueryEscape(jstr(o, "customerId"))}}
		},
		actions: []staffAction{
			{key: "shipment-status", label: "Update shipment status", tone: "primary", permission: "orders:manage", also: []string{"orders:update-status"},
				form: func(_ context.Context, _ staffCtx, o jobj) *domain.Form {
					opts := []domain.FieldOption{}
					for _, s := range objs(o, "shipments") {
						opts = append(opts, domain.FieldOption{Value: firstNonEmpty(jstr(s, "shipmentId"), jstr(s, "id")), Label: jstr(s, "label") + " · " + domain.Humanize(jstr(s, "status"))})
					}
					return &domain.Form{Title: "Move a shipment to another status", SubmitLabel: "Update", Fields: []domain.FormField{
						withOptions(ff("shipmentId", "Shipment", "select", true), opts),
						withOptions(ff("to", "New status", "select", true), domain.StaffOptions("CONFIRMED", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED", "RETURNED", "CANCELLED")),
						withMax(ff("reason", "Reason", "text", false), 500)}}
				},
				when: func(_ staffCtx, o jobj) string {
					if len(objs(o, "shipments")) == 0 {
						return "This order has no shipments."
					}
					return ""
				},
				run: func(ctx context.Context, sc staffCtx, _ jobj, v jobj) error {
					return sc.post(ctx, sc.up.Orders, "/v1/shipments/"+url.PathEscape(vs(v, "shipmentId"))+"/transitions", omitEmpty(jobj{"to": vs(v, "to"), "reason": vs(v, "reason")}), nil)
				}},
		},
	}
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
		columns: []domain.Column{col("atin", "ATIN", "mono", 15), col("title", "Product", "strong", 30), col("ownerOrgId", "Seller", "text", 20),
			col("productType", "Type", "text", 15), col("status", "Status", "status", 20)},
		facets: domain.StaffFacets("IN_REVIEW", "PENDING_CATEGORY_APPROVAL", "LIVE", "PAUSED", "REJECTED", "TAKEN_DOWN"),
		list: listPaged("/v1/products", upCatalog, func(q staffQuery, v url.Values) {
			if q.Q != "" {
				v.Set("q", q.Q)
			}
		}),
		get:        getByPath("/v1/products/", upCatalog),
		titleOf:    func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string { return jstr(o, "atin") + " · " + jstr(o, "ownerOrgId") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "status", "variants", "images") },
		tables: func(o jobj) []domain.DetailTable {
			return []domain.DetailTable{tableOf("Variants", objs(o, "variants"), []domain.Column{col("atin", "ATIN", "mono", 30), col("sku", "SKU", "mono", 30), col("status", "Status", "status", 40)}, "atin")}
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
		columns: []domain.Column{col("title", "Listing", "strong", 28), col("sellerOrgId", "Seller", "text", 16), col("type", "Type", "text", 14),
			col("priority", "Priority", "text", 10), col("sla.dueAt", "Due", "datetime", 14), col("assignee", "Assignee", "text", 8), col("status", "Status", "status", 10)},
		facets:     domain.StaffFacets("PENDING", "ESCALATED", "APPROVED", "REJECTED", "CLOSED"),
		list:       listPaged("/v1/moderation/items", upTrustOps, nil),
		get:        getByPath("/v1/moderation/items/", upTrustOps),
		titleOf:    func(o jobj) string { return jstr(o, "title") },
		subtitleOf: func(o jobj) string { return domain.Humanize(jstr(o, "type")) + " · " + jstr(o, "atin") },
		actions: []staffAction{
			{key: "assign", label: "Assign to me", tone: "neutral", permission: "catalog:moderate", confirm: "Take this item?", when: inState("PENDING", "ESCALATED"),
				run: act("/assign", func(jobj) jobj { return jobj{} })},
			{key: "approve", label: "Allow", tone: "primary", permission: "catalog:moderate", level: domain.LevelApprove, form: noteForm("Allow the listing", "Allow", false), when: inState("PENDING", "ESCALATED"),
				run: act("/approve", func(v jobj) jobj { return omitEmpty(jobj{"note": vs(v, "note")}) })},
			{key: "reject", label: "Remove", tone: "danger", permission: "catalog:moderate", level: domain.LevelApprove, when: inState("PENDING", "ESCALATED"),
				form: func(context.Context, staffCtx, jobj) *domain.Form {
					return &domain.Form{Title: "Remove the listing", SubmitLabel: "Remove", Fields: []domain.FormField{
						withOptions(ff("reasonCode", "Reason", "select", true), moderationReasonCodes), withMax(ff("note", "Note", "textarea", false), 1000)}}
				},
				run: act("/reject", func(v jobj) jobj { return omitEmpty(jobj{"reasonCode": vs(v, "reasonCode"), "note": vs(v, "note")}) })},
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
		columns: []domain.Column{col("sku", "SKU", "mono", 14), col("lotNo", "Lot", "mono", 16), col("nodeId", "Centre", "text", 13), col("qtyOnHand", "On hand", "number", 10),
			col("qtyReserved", "Reserved", "number", 10), col("qtyAvailable", "Available", "number", 10), col("expDate", "Expires", "date", 13), col("status", "Status", "status", 14)},
		facets: domain.StaffFacets("SELLABLE", "QUARANTINED", "DAMAGED", "EXPIRED"),
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
				out = append(out, domain.ActivityEntry{At: timeOf(h, "at"), Actor: jstr(h, "by"), Text: text})
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
		columns: []domain.Column{col("name", "Route", "mono", 16), col("zone", "Zone", "text", 14), col("window", "Window", "text", 12), col("driverId", "Driver", "strong", 16),
			col("stopCount", "Stops", "number", 8), col("serviceDate", "Date", "date", 12), col("status", "Status", "status", 22)},
		cells:      func(o jobj) map[string]any { return map[string]any{"stopCount": float64(len(objs(o, "stops")))} },
		facets:     domain.StaffFacets("PLANNED", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "CANCELLED"),
		list:       listLimited("/v1/dispatch/routes", upFulfilment, nil),
		get:        getByPath("/v1/dispatch/routes/", upFulfilment),
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
					return &domain.Form{Title: "Assign a driver", SubmitLabel: "Assign", Fields: []domain.FormField{withMax(ff("driverId", "Driver id", "text", true), 64), withMax(ff("vehicleId", "Vehicle", "text", false), 40)}}
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
