package app

import (
	"strings"
	"testing"
)

func ship(id, label, status, method string, next ...any) jobj {
	return jobj{"shipmentId": id, "label": label, "status": status, "deliveryMethod": method, "nextActions": next}
}

func keysOf(as []staffAction) string {
	ks := []string{}
	for _, a := range as {
		ks = append(ks, a.key+"="+a.label)
	}
	return strings.Join(ks, ";")
}

func TestShipmentNextStepIsOneTap(t *testing.T) {
	o := jobj{"shipments": []any{ship("s1", "Mama Put", "CONFIRMED", "CARRIER", "packed", "cancelled")}}
	got := shipmentNextStepActions(o)
	if keysOf(got) != "step:s1:PACKED=Mark packed" {
		t.Fatalf("got %s", keysOf(got))
	}
	if got[0].form != nil {
		t.Fatal("a daily step must not ask for a form")
	}
}

func TestShipmentNextStepNamesTheShipmentWhenThereAreSeveral(t *testing.T) {
	o := jobj{"shipments": []any{ship("s1", "Mama Put", "PACKED", "CARRIER", "ready"), ship("s2", "Lagos Textile", "CONFIRMED", "CARRIER", "packed")}}
	if keysOf(shipmentNextStepActions(o)) != "step:s1:READY=Mark ready · Mama Put;step:s2:PACKED=Mark packed · Lagos Textile" {
		t.Fatalf("got %s", keysOf(shipmentNextStepActions(o)))
	}
}

func TestDeliveryAndExceptionStepsAreNotOneTapButtons(t *testing.T) {
	// AfroTransact-delivered steps are taken with the delivery steps in the order sheet's menu (with the proof and the audit);
	// delivered by hand, failed and cancelled keep the form. There is no "in the Delivery queue" placeholder any more.
	o := jobj{"shipments": []any{
		ship("s1", "Local", "READY", "AFROTRANSACT_DELIVERY", "picked_up", "cancelled"),
		ship("s2", "Carrier", "OUT_FOR_DELIVERY", "CARRIER", "delivered", "delivery_failed")}}
	if got := keysOf(shipmentNextStepActions(o)); got != "" {
		t.Fatalf("got %s", got)
	}
}

// The regression behind "waiting for admin to assign a driver": a seller-shipped order never shows the Delivery queue note,
// whatever its status, and the sheet never talks about a driver.
func TestSellerShippedOrdersNeverWaitForADriver(t *testing.T) {
	for _, st := range []string{"CONFIRMED", "PACKED", "READY", "PICKED_UP", "OUT_FOR_DELIVERY"} {
		o := jobj{"shipments": []any{ship("s1", "Mama Put", st, "SELLER_SHIPS", "delivered")}}
		for _, a := range shipmentNextStepActions(o) {
			if strings.HasPrefix(a.key, "driver:") || strings.Contains(strings.ToLower(a.label), "driver") || strings.Contains(a.label, "Delivery queue") {
				t.Fatalf("%s: seller-shipped shipment shows %q", st, a.label)
			}
		}
	}
}
