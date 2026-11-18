package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Codes of a refusal about the location an offer names. Seller Center shows each next to the location field, with a link to the
// place where it is fixed; none of them is about the price.
const (
	// CodeOfferLocationUnknown: the chosen location is not one of the seller's.
	CodeOfferLocationUnknown = "OFFER_LOCATION_UNKNOWN"
	// CodePickupNotOn: Pick up is chosen at a location where customer pickup is switched off.
	CodePickupNotOn = "PICKUP_NOT_ON"
	// CodePickupHoursMissing: Pick up is chosen at a location that has no opening hours yet.
	CodePickupHoursMissing = "PICKUP_HOURS_MISSING"
	// CodeFbaLocationNotOn: Fulfilled by AfroTransact is chosen at a location AfroTransact does not collect from yet.
	CodeFbaLocationNotOn = "FBA_LOCATION_NOT_ON"
)

const offerLocationField = "shipFromLocationId"

func offerLocationProblem(code, message string) *domain.Problem {
	return domain.FieldProblem(code, "Check the location", offerLocationField, message)
}

// requireOfferLocation checks the location a Pick up or Fulfilled by AfroTransact offer names, and says exactly what is missing:
// it is not the seller's, customer pickup is off or has no hours (Pick up), or AfroTransact does not collect there yet (Fulfilled
// by AfroTransact). A location that has what the mode needs always passes. A pickup read that fails is not held against the seller
// (offers checks the rest).
func (s *sellerMatchImpl) requireOfferLocation(ctx context.Context, c *domain.Caller, orgID string, in *domain.OfferOnlyInput) error {
	pickUp := in.Fulfilment == domain.ShipsFromSeller
	if (!pickUp && in.Fulfilment != domain.AfroTransactDelivery) || in.ShipFromLocationID == "" || s.r.d.Sellers == nil {
		return nil
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, orgID)
	if err != nil {
		return mapErr("seller", err)
	}
	addr := shipFromList(list)
	idx := -1
	for i := range addr {
		if addr[i].ID == in.ShipFromLocationID {
			idx = i
		}
	}
	if idx < 0 {
		return offerLocationProblem(CodeOfferLocationUnknown, "Choose one of your locations.")
	}
	if !pickUp {
		if !addr[idx].AfrotransactPickup.Enabled {
			return offerLocationProblem(CodeFbaLocationNotOn, "Turn on Fulfilled by AfroTransact at this location first.")
		}
		return nil
	}
	if s.r.d.Carrier == nil {
		return nil
	}
	locs, err := s.r.d.Carrier.ManagedPickupLocations(ctx, c.BearerToken, orgID)
	if err != nil {
		s.r.d.Log.WarnContext(ctx, "pickup locations unavailable while checking an offer", "error", err)
		return nil
	}
	for _, l := range locs {
		if l.ID != in.ShipFromLocationID {
			continue
		}
		switch {
		case !l.Active:
			return offerLocationProblem(CodePickupNotOn, "Turn on pickup at this location and add the hours you are open.")
		case len(l.Hours) == 0:
			return offerLocationProblem(CodePickupHoursMissing, "Add pickup hours to this location.")
		}
		return nil
	}
	return offerLocationProblem(CodePickupNotOn, "Turn on pickup at this location and add the hours you are open.")
}

// offerFieldOf maps the field names offers uses for the location to the gateway's request name.
func offerFieldOf(field string) string {
	if strings.HasPrefix(field, "shipFrom") {
		return offerLocationField
	}
	return field
}
