package app

import (
	"context"
	"fmt"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AfroTransactDeliveryFeeMinor is the AfroTransact Delivery fee when the package does not ship free.
const AfroTransactDeliveryFeeMinor = 399

var weekdays = []string{"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"}

// fcFor picks the AfroTransact fulfilment centre that serves a ZIP (the promise service's FC ids): north-east from
// Edison, south-east from Atlanta, everything else from Houston.
func fcFor(zip string) string {
	if zip == "" {
		return "fc_houston_tx"
	}
	switch zip[0] {
	case '0', '1':
		return "fc_edison_nj"
	case '2', '3':
		return "fc_atlanta_ga"
	}
	return "fc_houston_tx"
}

func hoursLabel(l port.PickupLocation) string {
	if len(l.Hours) == 0 {
		return "Hours not listed"
	}
	days := make([]string, 0, len(l.Hours))
	for _, h := range l.Hours {
		if h.Weekday >= 0 && h.Weekday < len(weekdays) {
			days = append(days, weekdays[h.Weekday])
		}
	}
	first := l.Hours[0]
	if len(days) > 2 {
		return fmt.Sprintf("%s–%s %s–%s", days[0], days[len(days)-1], first.Open, first.Close)
	}
	return fmt.Sprintf("%s %s–%s", strings.Join(days, ", "), first.Open, first.Close)
}

func pickupPoint(l port.PickupLocation) domain.PickupPoint {
	setBy := "platform"
	if l.Kind == "SELLER" {
		setBy = "seller"
	}
	ready := "Ready the same day"
	if l.ReadyHours > 0 {
		ready = fmt.Sprintf("Ready about %d h after you order", l.ReadyHours)
	}
	return domain.PickupPoint{ID: l.ID, Name: l.Name, Hours: hoursLabel(l), Ready: ready, SetBy: setBy,
		Address: strings.TrimSpace(fmt.Sprintf("%s, %s, %s %s", l.Address.Line1, l.Address.City, l.Address.State, l.Address.PostalCode))}
}

func hasOption(p domain.CheckoutPackage, key string) bool {
	for _, o := range p.DeliveryOptions {
		if o.Key == key {
			return true
		}
	}
	return false
}

// Summary is the checkout page: the selected cart (checkout service) plus, per package, pickup points
// (carrier-gateway), AfroTransact Delivery where a delivery zone of the package's origin covers the address (geo),
// and promise dates for every option (promise). Each fact is best effort: without it the cart's estimate stays.
func (s *checkoutPaymentServiceImpl) Summary(ctx context.Context, c *domain.Caller, addressID string) (domain.CheckoutSummary, error) {
	sum, err := s.d.Checkout.Summary(ctx, c, addressID)
	if err != nil {
		return sum, fmt.Errorf("checkout summary: %w", err)
	}
	sum.PublishableKey = s.d.PublishableKey
	if s.d.Delivery == nil || len(sum.Packages) == 0 {
		return sum, nil
	}
	addrs, err := s.d.Identity.Addresses(ctx, c.BearerToken)
	if err != nil {
		return sum, nil //nolint:nilerr // the summary already has the addresses; delivery facts are optional
	}
	var addr *port.IdentityAddress
	for i := range addrs {
		a := &addrs[i]
		if (addressID != "" && a.ID == addressID) || (addressID == "" && a.IsDefault) || (addr == nil && addressID == "") {
			addr = a
		}
	}
	if addr == nil || len(addr.PostalCode) < 5 {
		return sum, nil
	}
	zip := addr.PostalCode[:5]
	fc := fcFor(zip)
	log := s.d.log()

	atd := false
	if addr.Latitude != nil && addr.Longitude != nil {
		zones, err := s.d.Delivery.ZonesCovering(ctx, *addr.Latitude, *addr.Longitude, "DELIVERY")
		if err != nil {
			log.WarnContext(ctx, "delivery zones unavailable; AfroTransact Delivery not offered", "error", err)
		}
		for _, z := range zones {
			atd = atd || z.OwnerID == fc
		}
	}
	platformPoints, err := s.d.Delivery.PickupLocations(ctx, "", zip[:3])
	if err != nil {
		log.WarnContext(ctx, "pickup locations unavailable", "error", err)
	}

	var offers []port.PromiseOffer
	for i := range sum.Packages {
		p := &sum.Packages[i]
		isFC := p.Fulfilment == domain.FulfilledByAfroTransact
		carrierFree := len(p.DeliveryOptions) > 0 && p.DeliveryOptions[0].Free
		var points []domain.PickupPoint
		if isFC {
			for _, l := range platformPoints {
				if l.Active && l.Kind != "SELLER" {
					points = append(points, pickupPoint(l))
				}
			}
			if atd && !hasOption(*p, "afrotransact_delivery") {
				o := domain.DeliveryOption{Key: "afrotransact_delivery", Label: "AfroTransact Delivery", ETA: "Same or next day", FeeMinor: AfroTransactDeliveryFeeMinor}
				if carrierFree {
					o.FeeMinor, o.Free = 0, true
				}
				p.DeliveryOptions = append(p.DeliveryOptions, o)
			}
		} else {
			own, err := s.d.Delivery.PickupLocations(ctx, p.Seller.ID, zip[:3])
			if err != nil {
				log.WarnContext(ctx, "seller pickup locations unavailable", "error", err)
			}
			for _, l := range own {
				if l.Active && l.OwnerOrg == p.Seller.ID {
					points = append(points, pickupPoint(l))
				}
			}
		}
		if len(points) > 0 {
			p.PickupPoints = points
			if !hasOption(*p, "pickup") {
				p.DeliveryOptions = append(p.DeliveryOptions, domain.DeliveryOption{Key: "pickup", Label: "Pick up", ETA: points[0].Ready, Free: true})
			}
		}
		if !isFC {
			continue // seller ship-from ZIPs are not exposed to the BFF; the cart's estimate stays
		}
		origin := map[string]any{"type": "FC", "id": fc}
		for _, o := range p.DeliveryOptions {
			method := map[string]any{"type": "CARRIER", "serviceLevel": "GROUND"}
			switch o.Key {
			case "express":
				method["serviceLevel"] = "OVERNIGHT"
			case "afrotransact_delivery":
				method = map[string]any{"type": "AFROTRANSACT_DELIVERY"}
			case "pickup":
				method = map[string]any{"type": "PICKUP"}
			}
			offers = append(offers, port.PromiseOffer{OfferID: p.PackageID + "|" + o.Key, Origin: origin, Method: method})
		}
	}
	if len(offers) > 0 {
		quotes, err := s.d.Delivery.Promises(ctx, zip, offers)
		if err != nil {
			log.WarnContext(ctx, "promise unavailable; keeping estimated delivery dates", "error", err)
		}
		byID := map[string]port.PromiseQuote{}
		for _, q := range quotes {
			byID[q.OfferID] = q
		}
		for i := range sum.Packages {
			p := &sum.Packages[i]
			kept := p.DeliveryOptions[:0]
			for _, o := range p.DeliveryOptions {
				q, ok := byID[p.PackageID+"|"+o.Key]
				if ok && !q.Available && o.Key == "afrotransact_delivery" {
					continue // promise says the driver service can't reach this address in time
				}
				if ok && q.Available {
					o.ETA = firstNonEmpty(q.Label, o.ETA)
					o.EarliestDate, o.LatestDate, o.CutoffLabel = q.EarliestDate, q.LatestDate, q.CutoffLabel
				}
				kept = append(kept, o)
			}
			p.DeliveryOptions = kept
			if len(kept) > 0 && kept[0].EarliestDate != "" {
				p.Delivery.Label = kept[0].ETA
			}
		}
	}
	return sum, nil
}
