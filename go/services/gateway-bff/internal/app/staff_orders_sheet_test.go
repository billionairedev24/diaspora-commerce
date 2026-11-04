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
	s := jobj{"status": "placed", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "CARRIER", "nextActions": []any{"cancelled"}}
	got := stepsOf(shipmentTransitions(s, "PLACED", true))
	if b := got["PACKED"].Blocked; b == nil || *b != blockedSeller {
		t.Fatalf("packing is the seller's: %+v", got["PACKED"])
	}
}

func TestPickupHandoverAcceptsTheCodeAndDriverStepsAreFlagged(t *testing.T) {
	pickup := jobj{"status": "ready", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": "PICKUP", "handoverRequired": true, "nextActions": []any{"delivered"}}
	if d := stepsOf(shipmentTransitions(pickup, "READY", true))["DELIVERED"]; !d.Allowed || !d.CodeAccepted || !d.NoteRequired {
		t.Fatalf("a pickup handover takes the code or a note: %+v", d)
	}
	local := jobj{"status": "ready", "fulfilmentType": "FBA_AFROTRANSACT", "deliveryMethod": "AFROTRANSACT_DELIVERY", "nextActions": []any{"picked_up"}}
	if d := stepsOf(shipmentTransitions(local, "READY", true))["PICKED_UP"]; !d.Allowed || !d.DriverStep {
		t.Fatalf("staff may do the driver's step, flagged: %+v", d)
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
		"itemsMinor": float64(2000), "shippingMinor": float64(599), "taxMinor": float64(165), "discountMinor": float64(0), "totalMinor": float64(2764),
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
	if strings.Join(titles, "|") != "Customer|Payment|Technical details" {
		t.Fatalf("sections %v", titles)
	}
	for _, f := range secs[1].Fields {
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
