package app

import (
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The order sheet of the staff console: packages with the statuses staff may set on each, and the facts grouped the way
// support reads them (customer, payment, origin, notes). Business rules stay in orders-service: a shipment's own
// nextActions are what the caller may do now; everything else below only explains why a status is not on offer.

// Why a status is not on offer (domain.ShipmentStep.Blocked).
const (
	blockedPermission = "PERMISSION"
	blockedSeller     = "SELLER"
	blockedSequence   = "SEQUENCE"
	blockedState      = "STATE"
)

// forwardSteps is the happy path in order; the steps after it are the exceptions, which are always candidates.
var forwardSteps = []string{"CONFIRMED", "PACKED", "READY", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"}
var exceptionSteps = []string{"DELIVERY_FAILED", "RETURNED", "CANCELLED"}

// terminalStatuses end a shipment's life (a delivered one can still be returned).
var terminalStatuses = []string{"DELIVERED", "RETURNED", "CANCELLED"}

var blockedReasons = map[string]string{
	blockedPermission: "No permission",
	blockedSeller:     "Only the seller does this step",
	blockedSequence:   "Earlier steps come first",
	blockedState:      "Not available from here",
}

func fulfilmentLabel(fulfilmentType string) string {
	if strings.EqualFold(fulfilmentType, "SELLER_FULFILLED") {
		return "Seller"
	}
	return "AfroTransact"
}

func deliveryLabel(method string) string {
	switch strings.ToUpper(method) {
	case "AFROTRANSACT_DELIVERY":
		return "AfroTransact Delivery"
	case "PICKUP":
		return "Pickup"
	case "CARRIER":
		return "Carrier"
	}
	return domain.Humanize(method)
}

// mayChangeShipments: the permission and module level the "shipment-status" action needs.
func mayChangeShipments(sc staffCtx) bool {
	return (sc.access.Can("orders:manage") || sc.access.CanAny("orders:update-status")) && sc.access.Allows("orders", domain.LevelEdit)
}

// orderShipments lays out every package of the order for the sheet.
func orderShipments(sc staffCtx, o jobj) []domain.RecordShipment {
	may := mayChangeShipments(sc)
	out := []domain.RecordShipment{}
	for _, s := range objs(o, "shipments") {
		status := strings.ToUpper(jstr(s, "status"))
		titles := []string{}
		count := 0
		for _, l := range objs(s, "lines") {
			count += max(1, int(num(l, "quantity")))
			if t := jstr(l, "title"); t != "" && len(titles) < 3 {
				titles = append(titles, t)
			}
		}
		sh := domain.RecordShipment{
			ID: firstNonEmpty(jstr(s, "shipmentId"), jstr(s, "id")), Label: jstr(s, "label"), SellerName: firstNonEmpty(jstr(s, "sellerName"), "AfroTransact"),
			Status: status, Tone: domain.StatusTone(status), FulfilmentLabel: fulfilmentLabel(jstr(s, "fulfilmentType")), DeliveryLabel: deliveryLabel(jstr(s, "deliveryMethod")),
			ItemCount: count, ItemTitles: titles, Transitions: shipmentTransitions(s, status, may), History: shipmentHistory(s),
		}
		if v := jstr(s, "carrier"); v != "" {
			sh.Carrier = &v
		}
		if v := jstr(s, "trackingNumber"); v != "" {
			sh.TrackingNumber = &v
		}
		out = append(out, sh)
	}
	return out
}

// shipmentTransitions lists what staff can set on the shipment now (its nextActions: orders-service applies the state
// machine for the caller) and, so nothing silently goes missing, the later steps and exceptions they cannot, each with
// the reason.
func shipmentTransitions(s jobj, status string, may bool) []domain.ShipmentStep {
	next := map[string]bool{}
	for _, a := range strs(s, "nextActions") {
		next[strings.ToUpper(a)] = true
	}
	fulfilment, method := strings.ToUpper(jstr(s, "fulfilmentType")), strings.ToUpper(jstr(s, "deliveryMethod"))
	sellerFulfilled := fulfilment == "SELLER_FULFILLED"
	handover := boolean(s, "handoverRequired")
	pos := slices.Index(forwardSteps, status)
	firstOpen := -1 // the first forward step on offer: anything beyond it would skip a step
	for i, step := range forwardSteps {
		if next[step] {
			firstOpen = i
			break
		}
	}
	candidates := []string{}
	for i, step := range forwardSteps {
		if i > pos || next[step] {
			candidates = append(candidates, step)
		}
	}
	for _, step := range exceptionSteps {
		if step != status {
			candidates = append(candidates, step)
		}
	}
	if slices.Contains(terminalStatuses, status) && len(next) == 0 {
		return []domain.ShipmentStep{} // nothing more can happen to it
	}
	out := make([]domain.ShipmentStep, 0, len(candidates))
	for _, step := range candidates {
		st := domain.ShipmentStep{To: step, NoteRequired: reasonRequired(step), CodeAccepted: step == "DELIVERED" && handover && method == "PICKUP",
			DriverStep: method == "AFROTRANSACT_DELIVERY" && driverSteps[step]}
		blocked := ""
		switch {
		case !may:
			blocked = blockedPermission
		case next[step]:
			st.Allowed = true
		case sellerFulfilled && (step == "CONFIRMED" || step == "PACKED" || step == "READY"):
			blocked = blockedSeller
		case firstOpen >= 0 && slices.Index(forwardSteps, step) > firstOpen:
			blocked = blockedSequence
		default:
			blocked = blockedState
		}
		if blocked != "" {
			st.Blocked = ptr(blocked)
			st.Reason = ptr(blockedReasons[blocked])
		}
		out = append(out, st)
	}
	return out
}

// shipmentHistory is the shipment's status history, newest first.
func shipmentHistory(s jobj) []domain.ShipmentChange {
	out := []domain.ShipmentChange{}
	for _, t := range objs(s, "timeline") {
		c := domain.ShipmentChange{At: timeOf(t, "at"), To: strings.ToUpper(jstr(t, "to")), Actor: domain.Humanize(firstNonEmpty(jstr(t, "actorType"), jstr(t, "source")))}
		if v := jstr(t, "from"); v != "" {
			c.From = ptr(strings.ToUpper(v))
		}
		if v := strings.TrimSpace(jstr(t, "reason")); v != "" {
			c.Note = &v
		}
		out = append(out, c)
	}
	slices.Reverse(out)
	return out
}

// orderSections groups the order's facts for support: customer, payment, origin, notes and tags, then the ids (collapsed
// in the console). Empty facts are left out.
func orderSections(o jobj) []domain.DetailSection {
	var out []domain.DetailSection
	group := func(title string, fields ...domain.DetailField) {
		kept := []domain.DetailField{}
		for _, f := range fields {
			if strings.TrimSpace(f.Value) != "" {
				kept = append(kept, f)
			}
		}
		if len(kept) > 0 {
			out = append(out, domain.DetailSection{Title: title, Fields: kept})
		}
	}
	text := func(label, value string) domain.DetailField {
		return domain.DetailField{Label: label, Value: value, Kind: "text"}
	}
	money := func(label string, minor float64) domain.DetailField {
		return domain.DetailField{Label: label, Value: displayValue(minor), Kind: "money"}
	}

	addr := func(key string) string { return strings.TrimSpace(jstr(o, "shippingAddress."+key)) }
	cityLine := strings.TrimSpace(strings.Join(slices.DeleteFunc([]string{addr("city"), strings.TrimSpace(addr("state") + " " + addr("postalCode"))}, func(s string) bool { return s == "" }), ", "))
	street := strings.Join(slices.DeleteFunc([]string{addr("line1"), addr("line2"), cityLine}, func(s string) bool { return s == "" }), ", ")
	group("Customer",
		text("Name", firstNonEmpty(jstr(o, "customerName"), addr("fullName"))),
		text("Ship to", firstNonEmpty(street, jstr(o, "shipTo"))),
		text("Phone", addr("phone")),
		text("Delivery instructions", addr("instructions")))

	shipments := objs(o, "shipments")
	captured := 0
	for _, s := range shipments {
		if boolean(s, "captured") {
			captured++
		}
	}
	payment := ""
	switch strings.ToUpper(jstr(o, "status")) {
	case "PENDING_PAYMENT":
		payment = "Awaiting payment"
	case "CANCELLED":
		payment = "Not charged"
	default:
		switch {
		case len(shipments) > 0 && captured == len(shipments):
			payment = "Captured"
		case captured > 0:
			payment = "Captured for " + displayValue(float64(captured)) + " of " + displayValue(float64(len(shipments))) + " shipments"
		default:
			payment = "Authorised, captured when shipped"
		}
	}
	paymentFields := []domain.DetailField{text("Status", payment), money("Items", num(o, "itemsMinor")), money("Shipping", num(o, "shippingMinor")), money("Tax", num(o, "taxMinor"))}
	if d := num(o, "discountMinor"); d > 0 {
		paymentFields = append(paymentFields, money("Discount", -d))
	}
	paymentFields = append(paymentFields, money("Total", num(o, "totalMinor")))
	group("Payment", paymentFields...)

	group("Origin",
		text("Placed via", channelLabel(jstr(o, "channel"))),
		text("Source", jstr(o, "attribution.src")),
		text("Campaign slot", domain.Humanize(jstr(o, "attribution.cr"))),
		text("Pre-order window", jstr(o, "attribution.po")))

	group("Notes and tags",
		text("Cancellation reason", jstr(o, "cancelReason")),
		text("Tags", strings.Join(strs(o, "tags"), ", ")))

	group("Technical details",
		domain.DetailField{Label: "Order ID", Value: jstr(o, "id"), Kind: "mono"},
		domain.DetailField{Label: "Payment ID", Value: jstr(o, "paymentId"), Kind: "mono"})
	return out
}

// channelLabel names where the order came from; a plain visit to the site says nothing worth a row.
func channelLabel(channel string) string {
	if strings.EqualFold(channel, "SITE_DIRECT") {
		return ""
	}
	return domain.Humanize(channel)
}
