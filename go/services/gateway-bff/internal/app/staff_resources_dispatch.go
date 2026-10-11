package app

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- delivery dispatch (fulfilment, S281)
//
// The Delivery dispatch screen of the design: routes (with the route builder), the shipments waiting for a route, SOS
// alerts and vehicle defects raised from the driver app, and the driver event log. All of them read fulfilment's
// /v1/dispatch API with the staff member's token; fulfilment checks delivery:dispatch again.

const dispatchPermission = "delivery:dispatch"

// buildRoutesCreate is the route builder: it plans the depot's waiting shipments into routes per delivery zone.
func buildRoutesCreate() *staffCreate {
	return &staffCreate{permission: dispatchPermission,
		form: func(context.Context, staffCtx) domain.Form {
			return domain.Form{Title: "Build routes from the waiting shipments", SubmitLabel: "Build routes", Fields: []domain.FormField{
				withValue(withMax(ff("depotNodeId", "Depot (fulfilment centre)", "text", true), 40), "HOU-FC"),
				withValue(ff("serviceDate", "Service date", "date", true), time.Now().UTC().Format(time.DateOnly)),
				withValue(withRange(ff("maxDropoffs", "Drop-offs per route", "number", false), 200), float64(25)),
				withMax(ff("window", "Delivery window", "text", false), 60),
			}}
		},
		create: func(ctx context.Context, sc staffCtx, v jobj) (string, error) {
			body := omitEmpty(jobj{"depotNodeId": vs(v, "depotNodeId"), "serviceDate": vs(v, "serviceDate"), "window": vs(v, "window")})
			if n := vn(v, "maxDropoffs"); n > 0 {
				body["maxDropoffs"] = int(n)
			}
			var routes []jobj
			if err := sc.post(ctx, sc.up.Fulfilment, "/v1/dispatch/routes/build", body, &routes); err != nil {
				return "", err
			}
			if len(routes) == 0 {
				return "", domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Nothing to route",
					"No shipments are waiting at this depot for that date.")
			}
			return jstr(routes[0], "id"), nil
		}}
}

func dispatchShipmentsResource() *staffResource {
	list := listLimited("/v1/dispatch/shipments", upFulfilment)
	return &staffResource{
		key: "dispatch-shipments", module: dispatchModule, title: "Waiting shipments", statusKey: "status", exportable: true,
		columns: []domain.Column{col("orderNumber", "Order", "mono", 14), col("sellerName", "Seller", "strong", 16), col("origin", "Origin", "text", 10),
			col("place", "Deliver to", "text", 18), col("units", "Units", "number", 7), col("perishable", "Perishable", "text", 9),
			col("promisedBy", "Promised by", "date", 11), col("status", "Status", "status", 15)},
		idOf: func(o jobj) string { return jstr(o, "shipmentId") },
		cells: func(o jobj) map[string]any {
			return map[string]any{"place": strings.TrimSpace(jstr(o, "city") + ", " + jstr(o, "state") + " " + jstr(o, "postalCode")),
				"perishable": yesNo(o, "perishable")}
		},
		list: list,
		get:  findIn(list, "shipmentId"),
		titleOf: func(o jobj) string {
			return "Shipment " + firstNonEmpty(jstr(o, "label"), jstr(o, "orderNumber"), jstr(o, "shipmentId"))
		},
		subtitleOf: func(o jobj) string { return jstr(o, "sellerName") + " · " + jstr(o, "city") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "status") },
	}
}

func sosAlertsResource() *staffResource {
	list := listLimited("/v1/dispatch/sos", upFulfilment)
	return &staffResource{
		key: "sos-alerts", module: dispatchModule, title: "SOS alerts", exportable: true,
		columns: []domain.Column{col("kind", "Emergency", "strong", 14), col("driverId", "Driver", "mono", 16), col("routeId", "Route", "mono", 16),
			col("where", "Where (GPS)", "mono", 18), col("note", "Note", "text", 22), col("raisedAt", "Raised", "datetime", 14), col("status", "Status", "status", 10)},
		idOf:   func(o jobj) string { return jstr(o, "alertId") },
		status: func(jobj) string { return "ALERT" },
		cells: func(o jobj) map[string]any {
			return map[string]any{"where": gps(o), "kind": domain.Humanize(jstr(o, "kind"))}
		},
		list:       list,
		get:        findIn(list, "alertId"),
		titleOf:    func(o jobj) string { return "SOS: " + domain.Humanize(jstr(o, "kind")) },
		subtitleOf: func(o jobj) string { return "Driver " + jstr(o, "driverId") + " · route " + jstr(o, "routeId") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "") },
		actions: []staffAction{
			{key: "acknowledge", label: "Acknowledge", tone: "primary", permission: dispatchPermission, confirm: "Acknowledge this SOS? The driver sees that dispatch is on it.",
				run: func(ctx context.Context, sc staffCtx, o jobj, _ jobj) error {
					return sc.post(ctx, sc.up.Fulfilment, "/v1/dispatch/sos/"+url.PathEscape(jstr(o, "alertId"))+"/acknowledge", nil, nil)
				}},
		},
	}
}

func vehicleDefectsResource() *staffResource {
	list := listLimited("/v1/dispatch/defects", upFulfilment)
	return &staffResource{
		key: "vehicle-defects", module: dispatchModule, title: "Vehicle defects", exportable: true,
		columns: []domain.Column{col("vehicleId", "Vehicle", "mono", 12), col("driverId", "Driver", "mono", 14), col("routeId", "Route", "mono", 14),
			col("failed", "Failed checks", "text", 20), col("note", "Note", "text", 20), col("reportedAt", "Reported", "datetime", 12), col("status", "Status", "status", 10)},
		status: func(o jobj) string {
			if b, _ := o["critical"].(bool); b {
				return "BLOCKED"
			}
			return "OPEN"
		},
		cells:      func(o jobj) map[string]any { return map[string]any{"failed": strings.Join(strs(o, "items"), ", ")} },
		list:       list,
		get:        findIn(list, "id"),
		titleOf:    func(o jobj) string { return "Defect on " + firstNonEmpty(jstr(o, "vehicleId"), jstr(o, "id")) },
		subtitleOf: func(o jobj) string { return "Route " + jstr(o, "routeId") + " · driver " + jstr(o, "driverId") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "") },
		actions: []staffAction{
			{key: "resolve", label: "Resolve and unblock the route", tone: "primary", permission: dispatchPermission,
				form: noteForm("Resolve the defect (the driver re-inspects before starting)", "Resolve", false),
				run: func(ctx context.Context, sc staffCtx, o jobj, v jobj) error {
					return sc.post(ctx, sc.up.Fulfilment, "/v1/dispatch/defects/"+url.PathEscape(jstr(o, "id"))+"/resolve",
						omitEmpty(jobj{"routeId": jstr(o, "routeId"), "note": vs(v, "note")}), nil)
				}},
		},
	}
}

func driverEventsResource() *staffResource {
	list := listLimited("/v1/dispatch/events", upFulfilment)
	return &staffResource{
		key: "driver-events", module: dispatchModule, title: "Driver events", statusKey: "outcome", exportable: true,
		columns: []domain.Column{col("capturedAt", "When", "datetime", 14), col("type", "Event", "strong", 18), col("driverId", "Driver", "mono", 14),
			col("routeId", "Route", "mono", 14), col("stopId", "Stop", "mono", 12), col("message", "Detail", "text", 18), col("outcome", "Outcome", "status", 10)},
		idOf: func(o jobj) string { return jstr(o, "eventId") },
		cells: func(o jobj) map[string]any {
			return map[string]any{"type": domain.Humanize(strings.ReplaceAll(jstr(o, "type"), ".", " "))}
		},
		list:       list,
		get:        findIn(list, "eventId"),
		titleOf:    func(o jobj) string { return domain.Humanize(strings.ReplaceAll(jstr(o, "type"), ".", " ")) },
		subtitleOf: func(o jobj) string { return "Driver " + jstr(o, "driverId") + " · " + jstr(o, "capturedAt") },
		sections:   func(o jobj) []domain.DetailSection { return autoSections(o, "outcome") },
	}
}

func yesNo(o jobj, key string) string {
	if b, _ := o[key].(bool); b {
		return "Yes"
	}
	return "No"
}

func gps(o jobj) string {
	lat, okLat := o["lat"].(float64)
	lon, okLon := o["lon"].(float64)
	if !okLat || !okLon {
		return ""
	}
	return strconv.FormatFloat(lat, 'f', 5, 64) + ", " + strconv.FormatFloat(lon, 'f', 5, 64)
}
