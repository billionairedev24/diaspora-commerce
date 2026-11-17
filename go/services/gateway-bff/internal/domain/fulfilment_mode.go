package domain

import "strings"

// Fulfilment modes of a shipment as the apps word them. The product has two: Seller pickup and Fulfilled by AfroTransact
// (from the seller's location, or from stock AfroTransact holds). They decide what a customer or seller is told: only an
// AfroTransact-delivered shipment may say that AfroTransact collects or delivers it or show a proof of delivery. Derived from
// what orders returns, never guessed. Seller self-shipping is not supported: what sellers shipped themselves before is
// ModeLegacySellerShipped, read-only history.
const (
	ModeLegacySellerShipped = "legacy_seller_shipped"
	// ModeSellerShips is the seller shipping themselves at their own flat charge: only while the staff switch is on (off by
	// default). The seller moves it; no AfroTransact queue, proof or delivery claim.
	ModeSellerShips   = "seller_ships"
	ModeSellerStore   = "afrotransact_delivery"
	ModeFulfilled     = "fulfilled_by_afrotransact"
	ModePickup        = "pickup"
	ModePreorderRun   = "preorder_run"
	deliveryMethodATD = "AFROTRANSACT_DELIVERY"
)

// FulfilmentModeOf mirrors orders' Shipment.fulfilmentMode(): a seller-packed shipment sent to AfroTransact Delivery without
// the seller-store snapshot (localDelivery) is one the old checkout mislabelled, and SELLER_SHIPS and CARRIER are the retired
// self-shipping methods: all of them are legacy history.
func FulfilmentModeOf(fulfilmentType, deliveryMethod string, hasLocalDelivery bool) string {
	ft, dm := strings.ToUpper(fulfilmentType), strings.ToUpper(deliveryMethod)
	switch {
	case ft == "PREORDER":
		return ModePreorderRun
	case dm == "PICKUP":
		return ModePickup
	case dm == "SELLER_SELF_SHIPS":
		return ModeSellerShips
	case dm == "SELLER_SHIPS" || dm == "CARRIER":
		return ModeLegacySellerShipped
	case ft == "FBA_AFROTRANSACT" || ft == "RETAIL_1P":
		return ModeFulfilled
	case dm == deliveryMethodATD && hasLocalDelivery:
		return ModeSellerStore
	}
	return ModeLegacySellerShipped
}

// IsLegacySellerShipped reports a shipment from before self-shipping was retired.
func IsLegacySellerShipped(mode string) bool { return mode == ModeLegacySellerShipped }

// DeliveredByAfroTransact reports whether AfroTransact itself delivers (or hands over) this mode: the only modes that may
// show delivery proof or say AfroTransact delivers.
func DeliveredByAfroTransact(mode string) bool {
	return mode == ModeSellerStore || mode == ModeFulfilled || mode == ModePreorderRun
}
