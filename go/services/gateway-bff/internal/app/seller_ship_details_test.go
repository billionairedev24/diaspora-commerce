package app

import (
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// A seller who ships it themselves marks it shipped with details; no label is offered and AfroTransact is not in the picture.
func TestSellerShippedShipmentAsksForShipDetailsAndNeverOffersALabel(t *testing.T) {
	for _, dm := range []string{"SELLER_SHIPS", "AFROTRANSACT_DELIVERY"} { // the second is a package the old checkout mislabelled
		sh := shipmentOf(port.OrderShipmentView{ID: "s1", FulfilmentType: "SELLER_FULFILLED", DeliveryMethod: dm, Status: "READY", NextActions: []string{"picked_up"}})
		if sh.FulfilmentMode != domain.ModeSellerShips || !sh.NeedsShipDetails || sh.CanBuyLabel || sh.HasLabel {
			t.Fatalf("%s: %+v", dm, sh)
		}
	}
	// The seller opted into delivery by AfroTransact (seller-store snapshot): the seller packs, AfroTransact collects.
	opted := shipmentOf(port.OrderShipmentView{ID: "s2", FulfilmentType: "SELLER_FULFILLED", DeliveryMethod: "AFROTRANSACT_DELIVERY", Status: "READY",
		LocalDelivery: &port.LocalDeliveryView{ShipFromLocationID: "loc-1"}})
	if opted.FulfilmentMode != domain.ModeSellerStore || opted.NeedsShipDetails {
		t.Fatalf("opted in: %+v", opted)
	}
	// Fulfilled by AfroTransact: every step is AfroTransact's.
	fba := shipmentOf(port.OrderShipmentView{ID: "s3", FulfilmentType: "FBA_AFROTRANSACT", DeliveryMethod: "AFROTRANSACT_DELIVERY", Status: "READY", NextActions: []string{"picked_up"}})
	if fba.FulfilmentMode != domain.ModeFulfilled || len(fba.NextActions) != 0 || fba.NeedsShipDetails {
		t.Fatalf("fba: %+v", fba)
	}
}

func TestShipDetailsAreValidated(t *testing.T) {
	ok := domain.ShipDetails{Note: "  Handed to Tunde's courier at 3pm ", TrackingNumber: " 9400111 ", TrackingCarrier: "local"}.Clean()
	if ok.Note != "Handed to Tunde's courier at 3pm" || ok.TrackingNumber != "9400111" || ok.Validate() != nil {
		t.Fatalf("clean = %+v", ok)
	}
	if (domain.ShipDetails{}).Validate() != nil {
		t.Fatal("details are optional here; orders decides whether they are required")
	}
	for name, d := range map[string]domain.ShipDetails{
		"long note":       {Note: strings.Repeat("n", 501)},
		"spaces":          {TrackingNumber: "94 00"},
		"path characters": {TrackingNumber: "94/00"},
		"long tracking":   {TrackingNumber: strings.Repeat("1", 81)},
		"long carrier":    {TrackingCarrier: strings.Repeat("c", 41)},
	} {
		if d.Validate() == nil {
			t.Errorf("%s: expected a validation problem", name)
		}
	}
}
