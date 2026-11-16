package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type fakeProofLinks map[string]string

func (f fakeProofLinks) URLs(_ context.Context, ids ...string) map[string]string {
	out := map[string]string{}
	for _, id := range ids {
		if u, ok := f[id]; ok {
			out[id] = u
		}
	}
	return out
}

func TestDeliveredProofIsTheDeliveredStepWithAHandover(t *testing.T) {
	entries := []proofEntry{
		{to: "out_for_delivery", at: "2026-10-16T10:00:00Z"},
		{to: "DELIVERED", at: "2026-10-16T11:00:00Z", proof: &port.UpDeliveryProof{HandoverMethod: "someone_else", RecipientName: "Ngozi",
			Relationship: "Neighbour", PhotoAssetID: "p1", SignatureAssetID: "s1"}},
	}
	p := deliveredProof(entries)
	if p == nil || p.Method != "SOMEONE_ELSE" || *p.RecipientName != "Ngozi" || p.At != "2026-10-16T11:00:00Z" {
		t.Fatalf("proof = %+v", p)
	}
	if deliveredProof([]proofEntry{{to: "delivered", at: "x"}}) != nil {
		t.Fatal("a delivery without a driver's proof (carrier, pickup, by hand) has none")
	}
	if deliveredProof([]proofEntry{{to: "delivery_failed", at: "x", proof: &port.UpDeliveryProof{FailureReason: "Nobody home"}}}) != nil {
		t.Fatal("a failed attempt is not a delivery proof")
	}
}

func TestProofLinksAreBestEffort(t *testing.T) {
	p := &domain.DeliveryProof{PhotoAssetID: "p1", SignatureAssetID: "s1"}
	attachProofLinks(context.Background(), fakeProofLinks{"p1": "https://media.example/p1?sig=x"}, []*domain.DeliveryProof{p, nil})
	if p.PhotoURL == nil || *p.PhotoURL != "https://media.example/p1?sig=x" {
		t.Fatalf("photo = %v", p.PhotoURL)
	}
	if p.SignatureURL != nil {
		t.Fatalf("a link media cannot sign is left out, got %v", *p.SignatureURL)
	}
	attachProofLinks(context.Background(), nil, []*domain.DeliveryProof{p}) // no media client: no panic, no change
}

func TestStaffSeesTheProofWithItsPicturesAndPosition(t *testing.T) {
	order := jobj{"shipments": []any{jobj{"label": "Shipment 1 of 1", "timeline": []any{
		jobj{"to": "delivered", "proof": jobj{"handoverMethod": "SAFE_PLACE", "safePlace": "Back porch", "photoAssetId": "p1", "latitude": 29.76, "longitude": -95.36}},
	}}}}
	enrichStaffProof(context.Background(), fakeProofLinks{"p1": "https://media.example/p1"}, order)
	secs := proofSections(order)
	if len(secs) != 1 || secs[0].Title != "Proof of delivery · Shipment 1 of 1" {
		t.Fatalf("sections = %+v", secs)
	}
	got := map[string]domain.DetailField{}
	for _, f := range secs[0].Fields {
		got[f.Label] = f
	}
	if got["Left at"].Value != "Back porch" || got["Photo"].Kind != "image" || got["Photo"].Value != "https://media.example/p1" {
		t.Fatalf("fields = %+v", secs[0].Fields)
	}
	if got["Driver position"].Value != "29.76, -95.36" {
		t.Fatalf("position = %+v", got["Driver position"])
	}
	if len(proofSections(jobj{"shipments": []any{jobj{"timeline": []any{jobj{"to": "delivered"}}}}})) != 0 {
		t.Fatal("no proof, no section")
	}
}

// Delivery by AfroTransact staff: several photos, an optional recipient, no driver handover details.
func TestStaffPhotoProofCarriesEveryPhotoAndTheRecipient(t *testing.T) {
	entries := []proofEntry{{to: "DELIVERED", at: "2026-11-14T16:00:00Z", proof: &port.UpDeliveryProof{HandoverMethod: "PHOTO", RecipientName: "Ada Obi",
		PhotoAssetID: "p1", PhotoAssetIDs: []string{"p1", "p2", "p3"}}}}
	p := deliveredProof(entries)
	if p == nil || p.Method != "PHOTO" || *p.RecipientName != "Ada Obi" || len(p.PhotoAssetIDs) != 3 {
		t.Fatalf("proof = %+v", p)
	}
	attachProofLinks(context.Background(), fakeProofLinks{"p1": "https://m/p1", "p3": "https://m/p3"}, []*domain.DeliveryProof{p})
	if len(p.PhotoURLs) != 2 || p.PhotoURLs[0] != "https://m/p1" || p.PhotoURLs[1] != "https://m/p3" || *p.PhotoURL != "https://m/p1" {
		t.Fatalf("a photo that cannot be signed is left out and the first one stays photoUrl: %+v", p.PhotoURLs)
	}
	// No recipient named is fine: the customer is told only when it was delivered.
	if q := deliveredProof([]proofEntry{{to: "delivered", at: "x", proof: &port.UpDeliveryProof{HandoverMethod: "PHOTO", PhotoAssetID: "p1"}}}); q == nil || q.RecipientName != nil || len(q.PhotoAssetIDs) != 1 {
		t.Fatalf("proof without recipient = %+v", q)
	}
}

func TestFulfilmentModeDecidesWhoMayShowProof(t *testing.T) {
	cases := []struct {
		ft, dm string
		local  bool
		want   string
		proof  bool
	}{
		{"SELLER_FULFILLED", "SELLER_SHIPS", false, domain.ModeLegacySellerShipped, false},
		{"SELLER_FULFILLED", "CARRIER", false, domain.ModeLegacySellerShipped, false},
		// A package the old checkout mislabelled: AfroTransact Delivery with no seller-store snapshot is shipped by its seller.
		{"SELLER_FULFILLED", "AFROTRANSACT_DELIVERY", false, domain.ModeLegacySellerShipped, false},
		{"SELLER_FULFILLED", "AFROTRANSACT_DELIVERY", true, domain.ModeSellerStore, true},
		{"FBA_AFROTRANSACT", "AFROTRANSACT_DELIVERY", false, domain.ModeFulfilled, true},
		{"RETAIL_1P", "AFROTRANSACT_DELIVERY", false, domain.ModeFulfilled, true},
		{"SELLER_FULFILLED", "PICKUP", false, domain.ModePickup, false},
		{"PREORDER", "AFROTRANSACT_DELIVERY", false, domain.ModePreorderRun, true},
	}
	for _, c := range cases {
		got := domain.FulfilmentModeOf(c.ft, c.dm, c.local)
		if got != c.want || domain.DeliveredByAfroTransact(got) != c.proof {
			t.Errorf("%s/%s local=%v: mode %s proof %v, want %s %v", c.ft, c.dm, c.local, got, domain.DeliveredByAfroTransact(got), c.want, c.proof)
		}
	}
}
