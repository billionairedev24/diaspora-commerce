package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

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

// pickupRadiusMiles is how far a pickup point may be from the delivery address to be offered (straight line).
const pickupRadiusMiles = 60.0

func pickupPoint(l port.PickupLocation, addr *port.IdentityAddress) domain.PickupPoint {
	setBy := "platform"
	if l.Kind == "SELLER" {
		setBy = "seller"
	}
	ready := "Ready the same day"
	if l.ReadyHours > 0 {
		ready = fmt.Sprintf("Ready about %d h after you order", l.ReadyHours)
	}
	p := domain.PickupPoint{ID: l.ID, Name: l.Name, Hours: hoursLabel(l), Ready: ready, SetBy: setBy, Kind: l.Kind,
		Address: strings.TrimSpace(fmt.Sprintf("%s, %s, %s %s", l.Address.Line1, l.Address.City, l.Address.State, l.Address.PostalCode))}
	if l.Position != nil && addr != nil && addr.Latitude != nil && addr.Longitude != nil {
		d := math.Round(domain.HaversineMiles(*addr.Latitude, *addr.Longitude, l.Position.Lat, l.Position.Lng)*10) / 10
		p.DistanceMiles = &d
	}
	return p
}

// platformPickupLocations lists AfroTransact counters and lockers near the destination ZIP. A 2-digit prefix covers the
// metro; when that returns nothing (e.g. seed not yet run), all platform points are loaded and filtered by distance.
func platformPickupLocations(ctx context.Context, d port.DeliveryFactsClient, zip string) ([]port.PickupLocation, error) {
	if d == nil {
		return nil, nil
	}
	if len(zip) >= 2 {
		pts, err := d.PickupLocations(ctx, "", zip[:2])
		if err != nil {
			return nil, err
		}
		if len(pts) > 0 {
			return pts, nil
		}
	}
	return d.PickupLocations(ctx, "", "")
}

// nearby: within pickupRadiusMiles of the address when both are located, else same 5- or 3-digit ZIP prefix.
func nearby(p domain.PickupPoint, l port.PickupLocation, zip string) bool {
	if p.DistanceMiles != nil {
		return *p.DistanceMiles <= pickupRadiusMiles
	}
	locZip := strings.TrimSpace(l.Address.PostalCode)
	if len(zip) >= 5 && len(locZip) >= 5 && strings.HasPrefix(locZip, zip[:5]) {
		return true
	}
	return len(zip) >= 3 && len(locZip) >= 3 && strings.HasPrefix(locZip, zip[:3])
}

// maxPickupPoints is how many pickup points one package lists (nearest first).
const maxPickupPoints = 5

// pickupPointsFor lists the active pickup points a package can be collected from, nearest first: AfroTransact
// counters and lockers (staff-configured, kind PLATFORM_COUNTER / LOCKER) for platform-fulfilled packages
// (AfroTransact Retail and Fulfilled by AfroTransact); the seller's own locations (kind SELLER: its store address or
// another place it set up in Seller Center) for the seller's packages.
func pickupPointsFor(p domain.CheckoutPackage, platform, own []port.PickupLocation, addr *port.IdentityAddress, zip string) []domain.PickupPoint {
	src, isFC := own, p.Fulfilment == domain.FulfilledByAfroTransact
	if isFC {
		src = platform
	}
	points := []domain.PickupPoint{}
	for _, l := range src {
		if !l.Active || (isFC && l.Kind == "SELLER") || (!isFC && (l.Kind != "SELLER" || l.OwnerOrg != p.Seller.ID)) {
			continue
		}
		if pt := pickupPoint(l, addr); nearby(pt, l, zip) {
			points = append(points, pt)
		}
	}
	sort.SliceStable(points, func(i, j int) bool {
		a, b := points[i].DistanceMiles, points[j].DistanceMiles
		return a != nil && (b == nil || *a < *b)
	})
	if len(points) > maxPickupPoints {
		points = points[:maxPickupPoints]
	}
	return points
}

// addPickupOption offers pickup (no shipping charge) on a package with pickup points: "Pickup at an AfroTransact point" for
// platform-fulfilled packages, "Collect from the store" for a seller's own.
func addPickupOption(p *domain.CheckoutPackage, points []domain.PickupPoint) {
	if len(points) == 0 {
		return
	}
	p.PickupPoints = points
	if !hasOption(*p, domain.OptionPickup) {
		label := domain.LabelStorePickup
		if p.Fulfilment == domain.FulfilledByAfroTransact {
			label = domain.LabelPickupPoint
		}
		p.DeliveryOptions = append(p.DeliveryOptions, domain.DeliveryOption{Key: domain.OptionPickup, Label: label, ETA: points[0].Ready, Free: true})
	}
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
func (s *checkoutPaymentServiceImpl) summary(ctx context.Context, c *domain.Caller, addressID string,
	packageDelivery map[string]string, couponCode string) (domain.CheckoutSummary, error) {
	sum, err := s.d.Checkout.Summary(ctx, c, addressID)
	if err != nil {
		return sum, fmt.Errorf("checkout summary: %w", err)
	}
	sum.PublishableKey = s.d.PublishableKey
	sum.CanPlaceOrder, sum.BlockedLines, sum.TaxLines = true, []domain.CheckoutBlockedLine{}, []domain.CheckoutTaxLine{}
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
	if s.d.ShippingSettings != nil {
		s.d.ShippingSettings.Ensure(ctx)
	}
	s.priceLocalDelivery(ctx, &sum, addr)
	if s.d.Delivery == nil || addr == nil || len(addr.PostalCode) < 5 {
		applyCheckoutShipping(&sum, packageDelivery)
		s.blockUndeliverable(&sum, packageDelivery)
		recalcCheckoutTotal(&sum)
		return sum, nil
	}
	zip := addr.PostalCode[:5]
	fc := fcFor(zip)
	log := s.d.log()

	outside := outsideDeliveryArea(ctx, s.d.Delivery, addr, log)
	platformPoints, err := platformPickupLocations(ctx, s.d.Delivery, zip)
	if err != nil {
		log.WarnContext(ctx, "pickup locations unavailable", "error", err)
	}

	var offers []port.PromiseOffer
	for i := range sum.Packages {
		p := &sum.Packages[i]
		if len(p.Preorder) > 0 {
			continue // a pre-order is its own package: the window's date and its own fee (see packageShippingFee)
		}
		isFC := p.Fulfilment == domain.FulfilledByAfroTransact
		var own []port.PickupLocation
		if !isFC {
			if own, err = s.d.Delivery.PickupLocations(ctx, p.Seller.ID, ""); err != nil {
				log.WarnContext(ctx, "seller pickup locations unavailable", "error", err)
			}
		}
		addPickupOption(p, pickupPointsFor(*p, platformPoints, own, addr, zip))
		if outside {
			p.DeliveryOptions = quoteRequiredOptions(*p)
			p.LocalDelivery = nil
			continue
		}
		if p.Fulfilment == domain.AfroTransactDelivery || !isFC {
			continue // priced above (flat fee); the promise service only dates packages AfroTransact fulfils
		}
		origin := map[string]any{"type": "FC", "id": fc}
		for _, o := range p.DeliveryOptions {
			method := map[string]any{"type": "CARRIER", "serviceLevel": "GROUND"}
			switch o.Key {
			case domain.OptionDelivery:
				method = map[string]any{"type": "AFROTRANSACT_DELIVERY"}
			case domain.OptionPickup:
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
			for k := range p.DeliveryOptions {
				o := &p.DeliveryOptions[k]
				// The promise service only dates an option; it never removes one: the shopper always keeps delivery
				// at the flat fee (and pickup), so there is no dead end.
				if q, ok := byID[p.PackageID+"|"+o.Key]; ok && q.Available {
					o.ETA = firstNonEmpty(q.Label, o.ETA)
					o.EarliestDate, o.LatestDate, o.CutoffLabel = q.EarliestDate, q.LatestDate, q.CutoffLabel
				}
			}
			if len(p.DeliveryOptions) > 0 && p.DeliveryOptions[0].EarliestDate != "" {
				p.Delivery.Label = p.DeliveryOptions[0].ETA
			}
		}
	}
	if s.merch != nil {
		if rail, err := s.merch.CheckoutCrossSell(ctx, c); err == nil && rail != nil {
			sum.CrossSell = rail
		} else if err != nil {
			s.d.log().InfoContext(ctx, "checkout cross-sell unavailable", "error", err)
		}
	}
	applyCheckoutShipping(&sum, packageDelivery)
	s.priceMembership(ctx, c, &sum, packageDelivery)
	s.priceCoupon(ctx, c, &sum, packageDelivery, couponCode)
	s.priceTax(ctx, &sum, addr, packageDelivery)
	s.blockUndeliverable(&sum, packageDelivery)
	recalcCheckoutTotal(&sum)
	return sum, nil
}

// outsideDeliveryArea is true when the address is outside every active DELIVERY zone (geo), so the shopper must ask for a
// quote (listing rules D21: inside any zone the flat fee applies; there is no separate mile limit). When zones can't be
// read, or the address has no position, the shopper is not turned away: the flat fee applies.
func outsideDeliveryArea(ctx context.Context, d port.DeliveryFactsClient, addr *port.IdentityAddress, log *slog.Logger) bool {
	if d == nil || addr == nil || addr.Latitude == nil || addr.Longitude == nil {
		return false
	}
	zones, err := d.ZonesCovering(ctx, *addr.Latitude, *addr.Longitude, "DELIVERY")
	if err != nil {
		log.WarnContext(ctx, "delivery zones unavailable; treating the address as inside the delivery area", "error", err)
		return false
	}
	return len(zones) == 0
}

// quoteRequiredOptions is what a package outside every delivery zone offers: the single delivery option
// quote_required (disabled for payment; the storefront shows a button to ask for a quote), plus free pickup when a point is
// near, so the shopper always has a way forward.
func quoteRequiredOptions(p domain.CheckoutPackage) []domain.DeliveryOption {
	out := []domain.DeliveryOption{{Key: domain.OptionQuoteRequired, Label: domain.LabelQuoteRequired}}
	for _, o := range p.DeliveryOptions {
		if o.Key == domain.OptionPickup {
			out = append(out, o)
		}
	}
	return out
}

// Plain words for what stops the order (the storefront shows them next to the button).
const (
	quoteRequiredMessage = "Part of your order is too far for standard delivery. Ask us for a quote, choose pickup, or choose another address."
	feeUnknownMessage    = "We can't confirm the delivery fee right now. Please try again in a moment."
)

// blockUndeliverable stops the order while a package's chosen option is quote_required or its fee is unknown, and says why
// in plain words. It never lifts a block another rule (a blocked line) set.
func (s *checkoutPaymentServiceImpl) blockUndeliverable(sum *domain.CheckoutSummary, packageDelivery map[string]string) {
	for _, p := range sum.Packages {
		if len(p.Preorder) > 0 {
			continue
		}
		o, ok := chosenOption(p, packageDelivery)
		switch {
		case ok && o.Key == domain.OptionQuoteRequired:
			sum.CanPlaceOrder, sum.DeliveryBlockedReason = false, quoteRequiredMessage
			return
		case ok && o.FeeUnknown:
			sum.CanPlaceOrder, sum.DeliveryBlockedReason = false, feeUnknownMessage
		}
	}
}

// ---------------------------------------------------------------- AfroTransact Delivery from the seller's store (S255)

// localTrip is one pickup location of a package and the offers it carries.
type localTrip struct {
	locationID string
	city       string
}

// priceLocalDelivery prices every AFROTRANSACT_DELIVERY package (AfroTransact collects from the seller's store and
// delivers) for the chosen address the way orders prices it when the order is placed: the charge is the staff flat fee
// (listing rules D16, free above the optional threshold), the same record orders reads. The road distance store →
// address (geo) and payments' delivery pricing only decide whether the store can be served (its radius) and keep the
// route; a package beyond the radius is flagged (remove it, change the address or collect from the store). When a fact
// can't be read the package keeps the flat-fee option (orders re-checks at placement, QUOTE_UNAVAILABLE).
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
		opt := domain.DeliveryOption{Key: domain.OptionDelivery, Label: domain.LabelStoreDelivery, ETA: "Same or next day"}
		if len(p.DeliveryOptions) > 0 {
			opt = p.DeliveryOptions[0]
		}
		switch {
		case !ld.Available:
			opt.FeeMinor, opt.Free = 0, false
			opt.Label = domain.LabelStoreDelivery + " · not available to this address"
		case ld.ChargeMinor != nil:
			opt.FeeMinor, opt.Free, opt.FeeUnknown = *ld.ChargeMinor, *ld.ChargeMinor == 0, false
			opt.Label = domain.LabelStoreDelivery
		default:
			// The quote could not be read: the flat fee still applies (orders charges it), so price it from the settings.
			if fee, ok := s.flatDeliveryFee(p); ok {
				opt.FeeMinor, opt.Free, opt.FeeUnknown = fee, fee == 0, false
				opt.Label = domain.LabelStoreDelivery
			} else {
				opt.FeeMinor, opt.Free, opt.FeeUnknown, opt.Label = 0, false, true, domain.LabelFeeUnknown
			}
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

// shippingSettings is the staff shipping settings in force; false when none was ever read.
func (s *checkoutPaymentServiceImpl) shippingSettings() (domain.ShippingSettings, bool) {
	if setter, ok := s.d.Estimator.(domain.ShippingSettingsSetter); ok {
		return setter.ShippingSettings()
	}
	return domain.ShippingSettings{}, false
}

// flatDeliveryFee is the delivery charge of the whole package's items under the staff settings.
func (s *checkoutPaymentServiceImpl) flatDeliveryFee(p *domain.CheckoutPackage) (int64, bool) {
	cfg, ok := s.shippingSettings()
	if !ok {
		return 0, false
	}
	return cfg.DeliveryFee(p.SubtotalMinor), true
}

// applyCheckoutShipping sets shippingMinor from the shopper's per-package delivery choices (or each package's first
// option when a choice is omitted) and each pre-order package's own window fee.
func applyCheckoutShipping(sum *domain.CheckoutSummary, packageDelivery map[string]string) {
	sum.ShippingMinor = 0
	for _, p := range sum.Packages {
		sum.ShippingMinor += packageShippingFee(p, packageDelivery)
	}
}

// packageShippingFee is what orders charges the package: a pre-order package pays the fee of its own window or run (the
// door or pickup fee it was set with, never the flat fee), every other package the fee of its chosen option.
func packageShippingFee(p domain.CheckoutPackage, packageDelivery map[string]string) int64 {
	if fee, ok := preorderFee(p); ok {
		return fee
	}
	key := packageDelivery[p.PackageID]
	if key == "" && len(p.DeliveryOptions) > 0 {
		key = p.DeliveryOptions[0].Key
	}
	for _, o := range p.DeliveryOptions {
		if o.Key == key {
			return o.FeeMinor
		}
	}
	if len(p.DeliveryOptions) > 0 {
		return p.DeliveryOptions[0].FeeMinor
	}
	return 0
}

// preorderFee is the delivery fee of a pre-order package's chosen fulfilment (the cart service's preorder block).
func preorderFee(p domain.CheckoutPackage) (int64, bool) {
	if len(p.Preorder) == 0 {
		return 0, false
	}
	var block struct {
		FeeMinor int64 `json:"feeMinor"`
	}
	if err := json.Unmarshal(p.Preorder, &block); err != nil || block.FeeMinor < 0 {
		return 0, false
	}
	return block.FeeMinor, true
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
	tripOffers := map[string]string{} // offer id → its pickup location
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
		tripOffers[l.OfferID] = id
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
	cfg, haveFee := s.shippingSettings()
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
		// Listing rules D16: the same flat fee as every other delivery, on the items collected at this store (orders splits
		// a package per pickup location and charges each); the distance tariff's customer charge is not used.
		tripItems := int64(0)
		for _, l := range p.Lines {
			if l.OfferID != "" && tripOffers[l.OfferID] == t.locationID {
				tripItems += l.LineTotalMinor
			}
		}
		if haveFee {
			charge += cfg.DeliveryFee(tripItems)
		}
		road, minutes = max(road, d.RoadMiles), max(minutes, d.DurationMinutes)
	}
	ld.Available, ld.RoadMiles, ld.DurationMinutes = true, &road, &minutes
	if haveFee {
		ld.ChargeMinor = &charge
	}
	return ld
}
