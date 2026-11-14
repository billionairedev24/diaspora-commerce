package domain

import "strings"

// Fulfilment modes of a shipment as the apps word them. They decide what a customer or seller is told: only an
// AfroTransact-delivered shipment (the seller opted into delivery by AfroTransact, or Fulfilled by AfroTransact) may say that
// AfroTransact collects or delivers it or show a proof of delivery. Derived from what orders returns, never guessed.
const (
	ModeSellerShips   = "seller_ships"
	ModeSellerStore   = "afrotransact_delivery"
	ModeFulfilled     = "fulfilled_by_afrotransact"
	ModePickup        = "pickup"
	ModePreorderRun   = "preorder_run"
	deliveryMethodATD = "AFROTRANSACT_DELIVERY"
)

// FulfilmentModeOf mirrors orders' Shipment.fulfilmentMode(): a seller-packed shipment sent to AfroTransact Delivery without
// the seller-store snapshot (localDelivery) is one the old checkout mislabelled; the seller ships it.
func FulfilmentModeOf(fulfilmentType, deliveryMethod string, hasLocalDelivery bool) string {
	ft, dm := strings.ToUpper(fulfilmentType), strings.ToUpper(deliveryMethod)
	switch {
	case ft == "PREORDER":
		return ModePreorderRun
	case dm == "PICKUP":
		return ModePickup
	case dm == "SELLER_SHIPS" || dm == "CARRIER":
		return ModeSellerShips
	case ft == "FBA_AFROTRANSACT" || ft == "RETAIL_1P":
		return ModeFulfilled
	case dm == deliveryMethodATD && hasLocalDelivery:
		return ModeSellerStore
	}
	return ModeSellerShips
}

// DeliveredByAfroTransact reports whether AfroTransact itself delivers (or hands over) this mode: the only modes that may
// show delivery proof or say AfroTransact delivers.
func DeliveredByAfroTransact(mode string) bool {
	return mode == ModeSellerStore || mode == ModeFulfilled || mode == ModePreorderRun
}
