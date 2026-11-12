package app

import (
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// pickupReason says, machine-readably, why a package has no pickup option (domain.PickupWhy*). It is empty when the package
// has pickup points. The shopper UI maps the code to plain words; the code carries no ids, and support reads it next to the
// log line "no store pickup offered for the package". lookupFailed is true when carrier-gateway could not be read.
//
// What must be true for a seller's package to offer Collect from the store:
//  1. the seller turned on "Customers can pick up their orders here" for a ship-from address in Seller Center (that creates an
//     ACTIVE carrier-gateway pickup location of kind SELLER owned by the seller's organisation, with opening hours);
//  2. the shopper has a delivery address with a 5 digit ZIP;
//  3. the place is within pickupRadiusMiles of the address when both are located, else in the same ZIP area (first 5, then 3 digits).
func pickupReason(p domain.CheckoutPackage, platform, own []port.PickupLocation, addr *port.IdentityAddress, zip string, lookupFailed bool) string {
	if len(p.PickupPoints) > 0 {
		return ""
	}
	if addr == nil || len(zip) < 5 {
		return domain.PickupWhyNeedsAddress
	}
	if lookupFailed {
		return domain.PickupWhyLookupFailed
	}
	isFC := p.Fulfilment == domain.FulfilledByAfroTransact
	if isFC {
		return domain.PickupWhyNoPlatformPoint
	}
	mine := 0
	farAway := false
	for _, l := range own {
		if !l.Active || l.Kind != "SELLER" || l.OwnerOrg != p.Seller.ID {
			continue
		}
		mine++
		if pt := pickupPoint(l, addr); pt.DistanceMiles != nil && *pt.DistanceMiles > pickupRadiusMiles {
			farAway = true
		}
	}
	switch {
	case mine == 0:
		return domain.PickupWhySellerNotEnabled
	case farAway:
		return domain.PickupWhyTooFar
	default:
		return domain.PickupWhyOtherArea
	}
}
