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

func TestShipmentStepsFollowEachShipmentsNextActionsAndNameThemAsActions(t *testing.T) {
	o := jobj{"shipments": []any{
		jobj{"id": "s1", "label": "Shipment 1 of 2", "status": "packed", "nextActions": []any{"ready", "cancelled"}},
		jobj{"id": "s2", "label": "Shipment 2 of 2", "status": "in_transit", "nextActions": []any{"out_for_delivery", "delivered"}},
	}}
	got := shipmentSteps(o)
	want := []domain.FieldOption{
		{Value: "s1|READY", Label: "Shipment 1 of 2 · Mark ready"},
		{Value: "s1|CANCELLED", Label: "Shipment 1 of 2 · Cancel the shipment"},
		{Value: "s2|OUT_FOR_DELIVERY", Label: "Shipment 2 of 2 · Mark out for delivery"},
		{Value: "s2|DELIVERED", Label: "Shipment 2 of 2 · Mark delivered"},
	}
	if len(got) != len(want) {
		t.Fatalf("steps = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("steps = %v, want %v", got, want)
		}
	}
	if none := shipmentSteps(jobj{"shipments": []any{jobj{"status": "placed"}}}); len(none) != 0 {
		t.Fatalf("without nextActions no steps are offered, got %v", none)
	}
	single := shipmentSteps(jobj{"shipments": []any{jobj{"id": "s1", "label": "Shipment 1 of 1", "nextActions": []any{"packed"}}}})
	if len(single) != 1 || single[0].Label != "Mark packed" {
		t.Fatalf("one shipment needs no shipment prefix, got %v", single)
	}
	form := ordersResource().actions[0].form(nil, staffCtx{}, o)
	if form.Fields[0].Key != "step" || len(form.Fields[0].Options) != 4 {
		t.Fatalf("form = %+v", form.Fields)
	}
}

func TestProofSummaryNamesTheHandoverAndTheEvidence(t *testing.T) {
	cases := []struct {
		proof jobj
		want  string
	}{
		{jobj{"handoverMethod": "CUSTOMER", "photoAssetId": "a1"}, "handed to the customer, code checked, photo"},
		{jobj{"handoverMethod": "SOMEONE_ELSE", "recipientName": "Ngozi", "relationship": "Neighbour", "photoAssetId": "a1", "signatureAssetId": "a2"}, "handed to Ngozi, neighbour, photo, signature"},
		{jobj{"handoverMethod": "SAFE_PLACE", "safePlace": "Back porch", "photoAssetId": "a1"}, "left at back porch, photo"},
		{jobj{"failureReason": "Nobody home"}, "driver: Nobody home"},
	}
	for _, c := range cases {
		if got := proofSummary(jobj{"proof": c.proof}); got != c.want {
			t.Errorf("proofSummary(%v) = %q, want %q", c.proof, got, c.want)
		}
	}
	if got := proofSummary(jobj{"to": "delivered"}); got != "" {
		t.Errorf("no proof, got %q", got)
	}
}
