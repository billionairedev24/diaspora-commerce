package app

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestValidateForm(t *testing.T) {
	f := domain.Form{Fields: []domain.FormField{
		{Key: "email", Type: "email", Required: true},
		withRange(ff("rate", "Rate", "number", true), 0.2),
		withOptions(ff("status", "Status", "select", true), domain.StaffOptions("LIVE", "PAUSED")),
		withPattern(ff("zip", "ZIP", "text", false), `^[0-9]{3}$`, "Three digits"),
		ff("when", "When", "date", false),
		ff("on", "On", "toggle", true),
	}}
	errs := domain.ValidateForm(f, map[string]any{"email": "nope", "rate": 0.5, "status": "GONE", "zip": "12", "when": "10/10/2026"})
	want := map[string]string{"email": "Enter a valid email", "rate": "Must be at most 0.2", "status": "Choose one of the options", "zip": "Three digits", "when": "Enter a date (YYYY-MM-DD)"}
	for k, v := range want {
		if errs[k] != v {
			t.Errorf("%s = %q, want %q", k, errs[k], v)
		}
	}
	if _, ok := errs["on"]; ok {
		t.Error("toggles are never required")
	}
	if len(domain.ValidateForm(f, map[string]any{"email": "a@b.co", "rate": 0.1, "status": "LIVE", "zip": "770", "when": "2026-10-10"})) != 0 {
		t.Error("valid values rejected")
	}
}

func TestAutoSectionsAndPaging(t *testing.T) {
	secs := autoSections(jobj{"status": "LIVE", "amountMinor": 1200.0, "contacts": jobj{"phone": "+1"}, "lines": []any{jobj{"id": "1"}}, "tags": []any{"a", "b"}}, "status")
	if secs[0].Title != "Details" || len(secs) != 2 || secs[1].Title != "Contacts" {
		t.Fatalf("sections = %+v", secs)
	}
	kinds := map[string]string{}
	for _, f := range secs[0].Fields {
		kinds[f.Label] = f.Kind + "=" + f.Value
	}
	if kinds["Amount"] != "money=1200" || kinds["Status"] != "status=LIVE" || kinds["Tags"] != "text=a, b" {
		t.Fatalf("fields = %v", kinds)
	}
	all := []jobj{{"id": "1"}, {"id": "2"}, {"id": "3"}}
	p := sliceList(all, 0, 2)
	if len(p.items) != 2 || p.next == nil || *p.next != "2" {
		t.Fatalf("page = %+v", p)
	}
	if p := sliceList(all, 2, 2); len(p.items) != 1 || p.next != nil {
		t.Fatalf("last page = %+v", p)
	}
}

func TestShipmentMovesFollowTheShipmentsNextActions(t *testing.T) {
	o := jobj{"shipments": []any{
		jobj{"label": "Shipment 1 of 2", "status": "packed", "nextActions": []any{"ready", "cancelled"}},
		jobj{"label": "Shipment 2 of 2", "status": "in_transit", "nextActions": []any{"out_for_delivery", "delivered"}},
	}}
	got := shipmentMoves(o)
	want := []string{"READY", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED"}
	if len(got) != len(want) {
		t.Fatalf("moves = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("moves = %v, want %v", got, want)
		}
	}
	if all := shipmentMoves(jobj{"shipments": []any{jobj{"status": "placed"}}}); len(all) != len(shipmentLifecycle) {
		t.Fatalf("without nextActions every move is offered, got %v", all)
	}
	form := ordersResource().actions[0].form(nil, staffCtx{}, o)
	if form.Fields[0].Options[0].Label != "Shipment 1 of 2 · Packed → ready, cancelled" {
		t.Fatalf("shipment option = %q", form.Fields[0].Options[0].Label)
	}
}
