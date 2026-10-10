package app

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
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
	if len(sum.Packages) == 0 {
		return sum, nil
	}
	var addr *port.IdentityAddress
	if addrs, err := s.d.Identity.Addresses(ctx, c.BearerToken); err == nil {
		for i := range addrs {
			a := &addrs[i]
			if (addressID != "" && a.ID == addressID) || (addressID == "" && a.IsDefault) || (addr == nil && addressID == "") {
				addr = a
			}
		}
	}
	s.priceLocalDelivery(ctx, &sum, addr)
	if s.d.Delivery == nil || addr == nil || len(addr.PostalCode) < 5 {
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
		if p.Fulfilment == domain.AfroTransactDelivery {
			continue // priced by distance above; the driver collects at the store, no pickup points
		}
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

// ---------------------------------------------------------------- AfroTransact Delivery from the seller's store (S255)

// localTrip is one pickup location of a package and the offers it carries.
type localTrip struct {
	locationID string
	city       string
}

// priceLocalDelivery quotes every AFROTRANSACT_DELIVERY package for the chosen address the way orders prices it when
// the order is placed: the offers' pickup location (seller), the road distance store → address (geo) and payments'
// delivery pricing. A package beyond the radius is flagged (remove it or change the address); when a fact can't be
// read the quote is left to orders (QUOTE_UNAVAILABLE). Shipping and the total follow the quoted charges.
func (s *checkoutPaymentServiceImpl) priceLocalDelivery(ctx context.Context, sum *domain.CheckoutSummary, addr *port.IdentityAddress) {
	changed := false
	for i := range sum.Packages {
		p := &sum.Packages[i]
		if p.Fulfilment != domain.AfroTransactDelivery {
			continue
		}
		changed = true
		p.LocalDelivery = s.quotePackage(ctx, p, addr)
		ld := p.LocalDelivery
		opt := domain.DeliveryOption{Key: "afrotransact_delivery", ETA: "Same or next day"}
		if len(p.DeliveryOptions) > 0 {
			opt = p.DeliveryOptions[0]
		}
		from := domain.LocalDeliveryHeading(ld.FromCity)
		switch {
		case !ld.Available:
			opt.FeeMinor, opt.Free, opt.Label = 0, false, "Not available to this address · "+from
		case ld.ChargeMinor != nil:
			opt.FeeMinor, opt.Free = *ld.ChargeMinor, *ld.ChargeMinor == 0
			price := domain.Money(opt.FeeMinor)
			if opt.Free {
				price = "FREE"
			}
			opt.Label = price + " · " + from
		default:
			opt.FeeMinor, opt.Label = 0, from+" · priced when you place your order"
		}
		p.DeliveryOptions = []domain.DeliveryOption{opt}
	}
	if !changed {
		return
	}
	sum.ShippingMinor = 0
	for _, p := range sum.Packages {
		if len(p.DeliveryOptions) > 0 {
			sum.ShippingMinor += p.DeliveryOptions[0].FeeMinor
		}
	}
	sum.TotalMinor = sum.SubtotalMinor + sum.ShippingMinor + sum.TaxMinor
}

func localUnavailable(ld *domain.CheckoutLocalDelivery, reason, message string) *domain.CheckoutLocalDelivery {
	ld.Available, ld.ChargeMinor, ld.UnavailableReason, ld.Message = reason == domain.LocalQuoteUnavailable, nil, &reason, &message
	return ld
}

func miles(m float64) string { return strconv.FormatFloat(float64(int(m*10+0.5))/10, 'f', -1, 64) }

func (s *checkoutPaymentServiceImpl) quotePackage(ctx context.Context, p *domain.CheckoutPackage, addr *port.IdentityAddress) *domain.CheckoutLocalDelivery {
	ld := &domain.CheckoutLocalDelivery{FromCity: p.Seller.City, OfferIDs: []string{}}
	for _, l := range p.Lines {
		ld.OfferIDs = append(ld.OfferIDs, l.OfferID)
	}
	log := s.d.log()
	if addr == nil {
		return localUnavailable(ld, domain.LocalQuoteUnavailable, "Choose a delivery address to see the delivery charge.")
	}
	if s.d.LocalDelivery == nil || s.d.Offers == nil {
		return localUnavailable(ld, domain.LocalQuoteUnavailable, "We'll price the delivery when you place your order.")
	}
	// The package's pickup locations (normally one per seller; orders splits a package per location).
	trips := []localTrip{}
	seen := map[string]bool{}
	for _, l := range p.Lines {
		o, err := s.d.Offers.Offer(ctx, l.OfferID)
		if err != nil {
			log.WarnContext(ctx, "offer unavailable; delivery priced at order time", "error", err)
			return localUnavailable(ld, domain.LocalQuoteUnavailable, "We'll price the delivery when you place your order.")
		}
		id := o.Offer.ShipFromLocationID
		if id == "" {
			return localUnavailable(ld, domain.LocalPickupUnavailable, p.Seller.Name+"'s store isn't set up for AfroTransact Delivery right now.")
		}
		if ld.FromCity == "" {
			ld.FromCity = o.Offer.Seller.City
		}
		if !seen[id] {
			seen[id] = true
			trips = append(trips, localTrip{locationID: id, city: o.Offer.Seller.City})
		}
	}
	to := port.GeoPlace{Address: &port.GeoAddressPart{Line1: addr.Line1, Line2: addr.Line2, City: addr.City, State: addr.State, PostalCode: addr.PostalCode,
		Country: firstNonEmpty(addr.Country, "US")}}
	if addr.Latitude != nil && addr.Longitude != nil {
		to = port.GeoPlace{Lat: addr.Latitude, Lng: addr.Longitude}
	}
	var charge int64
	var road, minutes float64
	for _, t := range trips {
		loc, err := s.d.LocalDelivery.Location(ctx, t.locationID)
		switch {
		case errors.Is(err, port.ErrNotFound):
			return localUnavailable(ld, domain.LocalPickupUnavailable, p.Seller.Name+"'s store isn't set up for AfroTransact Delivery right now.")
		case err != nil:
			log.WarnContext(ctx, "pickup location unavailable; delivery priced at order time", "error", err)
			return localUnavailable(ld, domain.LocalQuoteUnavailable, "We'll price the delivery when you place your order.")
		case !loc.PickupEnabled || loc.Position == nil || !strings.EqualFold(loc.Position.Precision, "ADDRESS") && !strings.EqualFold(loc.Position.Precision, "POINT") ||
			(loc.OrgID != "" && p.Seller.ID != "" && loc.OrgID != p.Seller.ID):
			return localUnavailable(ld, domain.LocalPickupUnavailable, p.Seller.Name+"'s store isn't set up for AfroTransact Delivery right now.")
		}
		if city := strings.TrimSuffix(loc.City+", "+loc.State, ", "); city != "" {
			ld.FromCity = city
		}
		lat, lng := loc.Position.Lat, loc.Position.Lng
		d, err := s.d.LocalDelivery.Distance(ctx, port.GeoPlace{Lat: &lat, Lng: &lng}, to)
		if err != nil {
			if up, ok := port.AsUpstreamProblem(err); ok && (up.Status == http.StatusNotFound || up.Status == http.StatusUnprocessableEntity) {
				return localUnavailable(ld, domain.LocalAddressNotLocated, "We couldn't locate this address for AfroTransact Delivery. Check the address or choose another one.")
			}
			log.WarnContext(ctx, "road distance unavailable; delivery priced at order time", "error", err)
			return localUnavailable(ld, domain.LocalQuoteUnavailable, "We'll price the delivery when you place your order.")
		}
		q, err := s.d.LocalDelivery.Quote(ctx, p.Seller.ID, d.RoadMiles, 1)
		if err != nil {
			log.WarnContext(ctx, "delivery pricing unavailable; delivery priced at order time", "error", err)
			return localUnavailable(ld, domain.LocalQuoteUnavailable, "We'll price the delivery when you place your order.")
		}
		ld.MaxRadiusMiles = q.MaxRadiusMiles
		if !q.Available {
			if strings.EqualFold(q.UnavailableReason, "BEYOND_MAX_RADIUS") {
				where := strings.TrimSuffix(addr.City+", "+addr.State, ", ")
				msg := fmt.Sprintf("%s is %s miles from %s: too far for AfroTransact Delivery", p.Seller.Name, miles(d.RoadMiles), where)
				if q.MaxRadiusMiles != nil {
					msg += fmt.Sprintf(" (up to %s miles)", miles(*q.MaxRadiusMiles))
				}
				ld.RoadMiles, ld.DurationMinutes = &d.RoadMiles, &d.DurationMinutes
				return localUnavailable(ld, domain.LocalOutOfRange, msg+". Remove it or choose another address.")
			}
			return localUnavailable(ld, domain.LocalUnavailable, "AfroTransact Delivery from sellers' stores isn't offered right now.")
		}
		charge += q.CustomerChargeMinor
		road, minutes = max(road, d.RoadMiles), max(minutes, d.DurationMinutes)
	}
	ld.Available, ld.ChargeMinor, ld.RoadMiles, ld.DurationMinutes = true, &charge, &road, &minutes
	return ld
}
