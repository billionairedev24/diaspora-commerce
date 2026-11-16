package app

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Seller self-shipping is retired: an old seller-shipped package is history. The seller gets no next step, no label and no
// shipping details form; AfroTransact is not in the picture either.
func TestLegacySellerShippedShipmentOffersTheSellerNothing(t *testing.T) {
	for _, dm := range []string{"SELLER_SHIPS", "AFROTRANSACT_DELIVERY"} { // the second is a package the old checkout mislabelled
		sh := shipmentOf(port.OrderShipmentView{ID: "s1", FulfilmentType: "SELLER_FULFILLED", DeliveryMethod: dm, Status: "READY", NextActions: []string{"picked_up"}})
		if sh.FulfilmentMode != domain.ModeLegacySellerShipped || len(sh.NextActions) != 0 || sh.CanBuyLabel || sh.HasLabel {
			t.Fatalf("%s: %+v", dm, sh)
		}
	}
	// Fulfilled by AfroTransact from the seller's location (seller-store snapshot): the seller packs, AfroTransact collects.
	opted := shipmentOf(port.OrderShipmentView{ID: "s2", FulfilmentType: "SELLER_FULFILLED", DeliveryMethod: "AFROTRANSACT_DELIVERY", Status: "READY",
		LocalDelivery: &port.LocalDeliveryView{ShipFromLocationID: "loc-1"}})
	if opted.FulfilmentMode != domain.ModeSellerStore {
		t.Fatalf("opted in: %+v", opted)
	}
	// Fulfilled by AfroTransact: every step is AfroTransact's.
	fba := shipmentOf(port.OrderShipmentView{ID: "s3", FulfilmentType: "FBA_AFROTRANSACT", DeliveryMethod: "AFROTRANSACT_DELIVERY", Status: "READY", NextActions: []string{"picked_up"}})
	if fba.FulfilmentMode != domain.ModeFulfilled || len(fba.NextActions) != 0 {
		t.Fatalf("fba: %+v", fba)
	}
}
