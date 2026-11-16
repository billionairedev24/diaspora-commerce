package app

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func stepsOf(steps []domain.ShipmentStep) map[string]domain.ShipmentStep {
	m := map[string]domain.ShipmentStep{}
	for _, s := range steps {
		m[s.To] = s
	}
	return m
}

func TestTransitionsListEveryLaterStepAndSayWhyOneIsOff(t *testing.T) {
	s := jobj{"status": "packed", "fulfilmentType": "FBA_AFROTRANSACT", "deliveryMethod": "CARRIER", "nextActions": []any{"ready", "cancelled"}}
	got := stepsOf(shipmentTransitions(s, "PACKED", true))
	if !got["READY"].Allowed || !got["CANCELLED"].Allowed {
		t.Fatalf("ready and cancelled are on offer: %+v", got)
	}
	if !got["CANCELLED"].NoteRequired || got["READY"].NoteRequired {
		t.Fatal("only the exceptional step needs a note")
	}
	if d := got["DELIVERED"]; d.Allowed || d.Blocked == nil || *d.Blocked != blockedSequence {
		t.Fatalf("delivered would skip steps: %+v", d)
	}
	if d := got["DELIVERY_FAILED"]; d.Allowed || d.Blocked == nil || *d.Blocked != blockedState || d.Reason == nil {
		t.Fatalf("a failed delivery is not reachable from packed: %+v", d)
	}
	if _, earlier := got["CONFIRMED"]; earlier {
		t.Fatal("steps behind the shipment are not listed")
	}
}

func TestTransitionsWithoutPermissionAreListedDisabled(t *testing.T) {
	s := jobj{"status": "confirmed", "fulfilmentType": "FBA_AFROTRANSACT", "deliveryMethod": "CARRIER", "nextActions": []any{"packed"}}
	for _, st := range shipmentTransitions(s, "CONFIRMED", false) {
		if st.Allowed || st.Blocked == nil || *st.Blocked != blockedPermission {
			t.Fatalf("%s should be off for lack of permission: %+v", st.To, st)
		}
	}
}

func TestSellerFulfilledPreparationStepsBelongToTheSeller(t *testing.T) {
	s := fba("placed", "cancelled")
	got := stepsOf(shipmentTransitions(s, "PLACED", true))
	if b := got["PACKED"].Blocked; b == nil || *b != blockedSeller {
		t.Fatalf("packing is the seller's: %+v", got["PACKED"])
	}
	// A seller who ships the parcel themselves updates every step; the sheet says so.
	own := jobj{"status": "placed", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "CARRIER", "nextActions": []any{"cancelled"}}
	if b := stepsOf(shipmentTransitions(own, "PLACED", true))["PACKED"].Blocked; b == nil || *b != blockedSellerUpdates {
		t.Fatalf("a seller-shipped parcel is updated by the seller: %v", b)
	}
}

func TestPickupHandoverAcceptsTheCodeAndDeliveryStepsGoThroughDelivery(t *testing.T) {
	pickup := jobj{"status": "ready", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "PICKUP", "handoverRequired": true, "nextActions": []any{"delivered"}}
	if d := stepsOf(shipmentTransitions(pickup, "READY", true))["DELIVERED"]; !d.Allowed || !d.CodeAccepted || !d.NoteRequired {
		t.Fatalf("a pickup handover takes the code or a note: %+v", d)
	}
	local := jobj{"status": "ready", "fulfilmentType": "FBA_AFROTRANSACT", "deliveryMethod": "AFROTRANSACT_DELIVERY", "nextActions": []any{"picked_up"}}
	if d := stepsOf(shipmentTransitions(local, "READY", true))["PICKED_UP"]; !d.Allowed || d.DriverStep || d.Via != viaDelivery {
		t.Fatalf("staff take the delivery step through the Delivery module, no driver involved: %+v", d)
	}
}

func TestFinishedShipmentOffersNothing(t *testing.T) {
	s := jobj{"status": "cancelled", "nextActions": []any{}}
	if got := shipmentTransitions(s, "CANCELLED", true); len(got) != 0 {
		t.Fatalf("got %+v", got)
	}
}

func TestOrderSectionsHideEmptyFactsAndKeepIdsApart(t *testing.T) {
	o := jobj{"id": "0f0f0f0f-0000-4000-8000-000000000001", "paymentId": "pay-1", "status": "in_progress", "channel": "SITE_DIRECT", "customerName": "Amara Nwosu",
		"itemsMinor": float64(2000), "shippingMinor": float64(599), "taxMinor": float64(165), "discountMinor": float64(0), "totalMinor": float64(2764), "placedAt": "2026-11-01T10:00:00Z",
		"shipTo":          "Houston, TX",
		"shippingAddress": map[string]any{"fullName": "Amara Nwosu", "city": "Houston", "state": "TX", "postalCode": "77042", "phone": "+17135550100", "line1": ""},
		"shipments":       []any{map[string]any{"captured": true}, map[string]any{"captured": false}}}
	secs := orderSections(o)
	titles := []string{}
	for _, s := range secs {
		titles = append(titles, s.Title)
		for _, f := range s.Fields {
			if strings.TrimSpace(f.Value) == "" || f.Value == "—" {
				t.Fatalf("%s: empty field %q", s.Title, f.Label)
			}
			if s.Title != "Technical details" && f.Kind == "mono" {
				t.Fatalf("%s: id shown outside technical details: %q", s.Title, f.Label)
			}
		}
	}
	if strings.Join(titles, "|") != "Order|Customer|Payment|Technical details" {
		t.Fatalf("sections %v", titles)
	}
	for _, f := range secs[2].Fields {
		if f.Label == "Status" && f.Value != "Captured for 1 of 2 shipments" {
			t.Fatalf("payment status %q", f.Value)
		}
		if f.Label == "Discount" {
			t.Fatal("no discount row when there is none")
		}
	}
}

func TestOrderShipmentsNameTheSellerAndSummariseItems(t *testing.T) {
	o := jobj{"shipments": []any{jobj{"shipmentId": "s1", "label": "Shipment 1 of 1", "sellerName": "Patel Grocers", "status": "packed", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "CARRIER",
		"trackingNumber": "1Z999", "nextActions": []any{}, "lines": []any{jobj{"title": "Basmati rice", "quantity": float64(2)}, jobj{"title": "Ghee", "quantity": float64(1)}},
		"timeline": []any{jobj{"to": "placed", "at": "2026-11-01T10:00:00Z", "actorType": "SYSTEM"}, jobj{"from": "placed", "to": "packed", "at": "2026-11-02T10:00:00Z", "actorType": "SELLER", "reason": "Boxed"}}}}}
	got := orderShipments(staffCtx{}, o)
	if len(got) != 1 || got[0].SellerName != "Patel Grocers" || got[0].ItemCount != 3 || len(got[0].ItemTitles) != 2 || got[0].FulfilmentLabel != "Seller" {
		t.Fatalf("got %+v", got)
	}
	if got[0].TrackingNumber == nil || *got[0].TrackingNumber != "1Z999" || got[0].Carrier != nil {
		t.Fatalf("tracking %+v", got[0])
	}
	if h := got[0].History; len(h) != 2 || h[0].To != "PACKED" || h[0].Note == nil || *h[0].Note != "Boxed" || h[0].Actor != "Seller" {
		t.Fatalf("history newest first: %+v", h)
	}
}

// ---------------------------------------------------------------- the three fulfilment modes

func stepFor(steps []domain.ShipmentStep, to string) domain.ShipmentStep { return stepsOf(steps)[to] }

func blockReason(s domain.ShipmentStep) string {
	if s.Reason == nil {
		return ""
	}
	return *s.Reason
}

// Fulfilled by AfroTransact (the owner's FBA): the owner's screenshot was a ready shipment whose menu had every step greyed.
func fba(status string, next ...any) jobj {
	return jobj{"status": status, "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "AFROTRANSACT_DELIVERY", "nextActions": next,
		"localDelivery": map[string]any{"roadMiles": 4.0}}
}

func TestFulfilledByAfroTransactMenuOffersTheDeliveryStepsToTheDeliveryPermission(t *testing.T) {
	caps := shipmentCaps{orders: true, delivery: true}
	got := shipmentTransitionsFor(fba("ready", "picked_up", "cancelled"), "READY", caps)
	if d := stepFor(got, "PICKED_UP"); !d.Allowed || d.Via != viaDelivery || d.NoteRequired {
		t.Fatalf("picked up from the seller is one step with an optional note: %+v", d)
	}
	for _, step := range []string{"IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"} {
		if d := stepFor(got, step); d.Allowed || d.Blocked == nil || *d.Blocked != blockedSequence {
			t.Fatalf("%s would skip a step: %+v", step, d)
		}
	}
	if d := stepFor(got, "CANCELLED"); !d.Allowed || d.Via != viaOrders || !d.NoteRequired {
		t.Fatalf("cancelling stays with the order permission: %+v", d)
	}
	if d := stepFor(shipmentTransitionsFor(fba("out_for_delivery", "delivered", "delivery_failed"), "OUT_FOR_DELIVERY", caps), "DELIVERED"); !d.Allowed || !d.ProofRequired || d.NoteRequired || d.Via != viaDelivery {
		t.Fatalf("delivered needs the proof photo, not a typed reason: %+v", d)
	}
	failed := shipmentTransitionsFor(fba("delivery_failed", "out_for_delivery", "returned"), "DELIVERY_FAILED", caps)
	if d := stepFor(failed, "OUT_FOR_DELIVERY"); !d.Allowed {
		t.Fatalf("a failed attempt is tried again: %+v", d)
	}
	if d := stepFor(failed, "RETURNED"); !d.Allowed || !d.NoteRequired || d.Via != viaDelivery {
		t.Fatalf("or the parcel goes back, with a reason: %+v", d)
	}
	if d := stepFor(shipmentTransitionsFor(fba("out_for_delivery", "delivered", "delivery_failed"), "OUT_FOR_DELIVERY", caps), "DELIVERY_FAILED"); !d.NoteRequired {
		t.Fatalf("a failed delivery needs a reason: %+v", d)
	}
}

func TestWithoutTheDeliveryPermissionTheMenuSaysSo(t *testing.T) {
	got := shipmentTransitionsFor(fba("ready", "cancelled"), "READY", shipmentCaps{orders: true, delivery: false})
	for _, step := range []string{"PICKED_UP", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED"} {
		d := stepFor(got, step)
		if d.Allowed || d.Blocked == nil || *d.Blocked != blockedDeliveryPermission || blockReason(d) != "You need the delivery permission" {
			t.Fatalf("%s: %+v", step, d)
		}
	}
	if d := stepFor(got, "CANCELLED"); !d.Allowed {
		t.Fatalf("an order manager can still cancel: %+v", d)
	}
	none := shipmentTransitionsFor(fba("ready"), "READY", shipmentCaps{})
	if d := stepFor(none, "CANCELLED"); d.Blocked == nil || *d.Blocked != blockedPermission {
		t.Fatalf("no permission at all: %+v", d)
	}
}

func TestLegacySellerShippedMenuSaysOnlyTheOverrideMovesIt(t *testing.T) {
	s := jobj{"status": "picked_up", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "SELLER_SHIPS", "nextActions": []any{}}
	got := shipmentTransitionsFor(s, "PICKED_UP", shipmentCaps{orders: true, delivery: true})
	for _, step := range []string{"IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED", "RETURNED"} {
		d := stepFor(got, step)
		if d.Allowed || d.Blocked == nil || *d.Blocked != blockedSellerUpdates || blockReason(d) != "Old seller-shipped order: use Override status" {
			t.Fatalf("%s: %+v", step, d)
		}
	}
	// Even a person with every permission is told it is history: only the override changes it.
	got = shipmentTransitionsFor(s, "PICKED_UP", shipmentCaps{})
	if d := stepFor(got, "DELIVERED"); *d.Blocked != blockedSellerUpdates {
		t.Fatalf("%+v", d)
	}
}

func TestPickupMenuIsReadyCollectedNotCollected(t *testing.T) {
	s := jobj{"status": "ready", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "PICKUP", "nextActions": []any{"delivered", "returned", "cancelled"}}
	got := shipmentTransitionsFor(s, "READY", shipmentCaps{orders: true, delivery: true})
	if d := stepFor(got, "DELIVERED"); !d.Allowed || d.Via != viaOrders {
		t.Fatalf("collected: %+v", d)
	}
	if d := stepFor(got, "RETURNED"); !d.Allowed || !d.NoteRequired {
		t.Fatalf("not collected needs a reason: %+v", d)
	}
	for _, step := range []string{"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERY_FAILED"} {
		d := stepFor(got, step)
		if d.Allowed || d.Blocked == nil || *d.Blocked != blockedPickupOrder || blockReason(d) != "Not available for pickup orders" {
			t.Fatalf("%s: %+v", step, d)
		}
	}
	confirmed := jobj{"status": "confirmed", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "PICKUP", "nextActions": []any{"ready"}}
	if d := stepFor(shipmentTransitionsFor(confirmed, "CONFIRMED", shipmentCaps{orders: true}), "READY"); !d.Allowed {
		t.Fatalf("staff can say a pickup order is ready: %+v", d)
	}
}

func TestOnlyHoldersOfTheOverridePermissionSeeOverrideSteps(t *testing.T) {
	ship := jobj{"shipmentId": "s1", "label": "Shipment 1 of 1", "status": "picked_up", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "SELLER_SHIPS",
		"nextActions": []any{}, "overrideActions": []any{"in_transit", "delivered"}}
	holder := orderShipments(staffCtx{access: domain.StaffAccess{Permissions: []string{"orders:override-shipment"}}}, jobj{"shipments": []any{ship}})
	if !holder[0].CanOverride || len(holder[0].OverrideSteps) != 2 || holder[0].Mode != domain.ModeLegacySellerShipped {
		t.Fatalf("%+v", holder[0])
	}
	other := orderShipments(staffCtx{access: domain.StaffAccess{Permissions: []string{"orders:manage"}}}, jobj{"shipments": []any{ship}})
	if other[0].CanOverride || len(other[0].OverrideSteps) != 0 {
		t.Fatalf("a manager without the permission gets nothing: %+v", other[0])
	}
	if opts := shipmentOverrideSteps(jobj{"shipments": []any{ship}}); len(opts) != 2 || opts[0].Value != "s1|IN_TRANSIT" || opts[1].Label != "Mark delivered" {
		t.Fatalf("%+v", opts)
	}
}

// The override also corrects Fulfilled by AfroTransact (orders-service answers forward steps only), never a pickup order.
func TestOverrideStepsAreOfferedForFulfilledByAfroTransactToo(t *testing.T) {
	ship := jobj{"shipmentId": "s2", "label": "Shipment 1 of 1", "status": "picked_up", "fulfilmentType": "FBA_AFROTRANSACT", "deliveryMethod": "AFROTRANSACT_DELIVERY",
		"nextActions": []any{}, "overrideActions": []any{"out_for_delivery", "delivered"}}
	holder := orderShipments(staffCtx{access: domain.StaffAccess{Permissions: []string{"orders:override-shipment"}}}, jobj{"shipments": []any{ship}})
	if !holder[0].CanOverride || holder[0].Mode != domain.ModeFulfilled || len(holder[0].OverrideSteps) != 2 {
		t.Fatalf("%+v", holder[0])
	}
	pickup := jobj{"shipmentId": "s3", "status": "ready", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "PICKUP", "nextActions": []any{}, "overrideActions": []any{}}
	p := orderShipments(staffCtx{access: domain.StaffAccess{Permissions: []string{"orders:override-shipment"}}}, jobj{"shipments": []any{pickup}})
	if p[0].CanOverride {
		t.Fatalf("pickup has no override: %+v", p[0])
	}
}

func TestHistoryNamesTheStaffMemberWhoMovedIt(t *testing.T) {
	s := jobj{"timeline": []any{jobj{"to": "picked_up", "at": "2026-11-02T10:00:00Z", "actorType": "DELIVERY_STAFF", "actorName": "Chidi Nwosu", "reason": "2 boxes"}}}
	if h := shipmentHistory(s); len(h) != 1 || h[0].Actor != "Chidi Nwosu" || h[0].Note == nil || *h[0].Note != "2 boxes" {
		t.Fatalf("%+v", h)
	}
}
