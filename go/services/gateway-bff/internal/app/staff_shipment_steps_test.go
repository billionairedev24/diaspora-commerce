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

func TestDriverAndExceptionStepsAreNotStaffButtons(t *testing.T) {
	// The driver scans pickup and records proof of delivery in the app; delivered by hand, failed and cancelled keep the form.
	o := jobj{"shipments": []any{
		ship("s1", "Local", "READY", "AFROTRANSACT_DELIVERY", "picked_up", "cancelled"),
		ship("s2", "Carrier", "OUT_FOR_DELIVERY", "CARRIER", "delivered", "delivery_failed")}}
	// No buttons; the driver shipment only carries the "waiting for the driver" note.
	if got := keysOf(shipmentNextStepActions(o)); got != "driver:s1=Waiting for the driver · Local" {
		t.Fatalf("got %s", got)
	}
}

func TestDriverShipmentsShowWhoMovesThemNext(t *testing.T) {
	o := jobj{"shipments": []any{ship("s1", "Local", "OUT_FOR_DELIVERY", "AFROTRANSACT_DELIVERY", "delivered", "delivery_failed")}}
	got := shipmentNextStepActions(o)
	if len(got) != 1 || got[0].key != "driver:s1" || got[0].label != "Waiting for the driver" {
		t.Fatalf("got %s", keysOf(got))
	}
	if why := got[0].when(staffCtx{}, o); !strings.Contains(why, "proof in the driver app") || !strings.Contains(why, "More options") {
		t.Fatalf("reason %q", why)
	}
	if shipmentNextStepActions(jobj{"shipments": []any{ship("s2", "Carrier", "OUT_FOR_DELIVERY", "CARRIER", "delivered")}}) != nil && len(shipmentNextStepActions(jobj{"shipments": []any{ship("s2", "Carrier", "OUT_FOR_DELIVERY", "CARRIER", "delivered")}})) != 0 {
		t.Fatal("carrier shipments are not driver-owned")
	}
}
