package app

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var testNow = time.Date(2026, 10, 9, 9, 0, 0, 0, time.UTC)

type fakeOffers struct{ byID map[string]port.PublicOffer }

func (f fakeOffers) BuyBox(context.Context, string) (port.OfferBuyBox, error) {
	return port.OfferBuyBox{}, nil
}
func (f fakeOffers) ProductSummaries(context.Context, []string) (map[string]port.OfferBuyBox, error) {
	return map[string]port.OfferBuyBox{}, nil
}
func (f fakeOffers) Offer(_ context.Context, id string) (port.OfferDetail, error) {
	o, ok := f.byID[id]
	if !ok {
		return port.OfferDetail{}, &port.UpstreamProblem{Upstream: "offers", Status: 404, Code: "offers.offer-not-found"}
	}
	return port.OfferDetail{Offer: o}, nil
}

type fakeLocal struct {
	loc      port.PickupLocationFacts
	locErr   error
	dist     port.RoadDistance
	distErr  error
	quote    port.LocalDeliveryQuote
	quoteErr error
	to       port.GeoPlace
}

func (f *fakeLocal) Location(context.Context, string) (port.PickupLocationFacts, error) {
	return f.loc, f.locErr
}
func (f *fakeLocal) Distance(_ context.Context, _, to port.GeoPlace) (port.RoadDistance, error) {
	f.to = to
	return f.dist, f.distErr
}
func (f *fakeLocal) Quote(context.Context, string, float64, int) (port.LocalDeliveryQuote, error) {
	return f.quote, f.quoteErr
}

func localSummary() domain.CheckoutSummary {
	atd := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org_ama:AFROTRANSACT_DELIVERY", Fulfilment: domain.AfroTransactDelivery,
		Seller: domain.Seller{ID: "org_ama", Name: "Ama's Market", City: "Houston"}, SubtotalMinor: 3000,
		DeliveryOptions: est.Options(domain.AfroTransactDelivery, 0, 3000, testNow),
		Lines:           []domain.CartLine{{OfferID: "of-1"}}}}
	seller := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org_b:SELLER", Fulfilment: domain.ShipsFromSeller, SubtotalMinor: 1500,
		DeliveryOptions: []domain.DeliveryOption{{Key: "carrier", FeeMinor: 500}}}}
	return domain.CheckoutSummary{Packages: []domain.CheckoutPackage{atd, seller}, SubtotalMinor: 4500, ShippingMinor: 500, TotalMinor: 5000}
}

func TestCheckoutQuotesLocalDelivery(t *testing.T) {
	lat, lng := 29.70, -95.40
	addr := &port.IdentityAddress{City: "Houston", State: "TX", PostalCode: "77042", Latitude: &lat, Longitude: &lng}
	offers := fakeOffers{byID: map[string]port.PublicOffer{"of-1": {OfferID: "of-1", ShipFromLocationID: "loc-1", Seller: port.OfferSeller{City: "Houston"}}}}
	pos := &port.ShipFromPosition{Lat: 29.67, Lng: -95.55, Precision: "ADDRESS"}
	ok := func() *fakeLocal {
		return &fakeLocal{loc: port.PickupLocationFacts{ID: "loc-1", OrgID: "org_ama", City: "Houston", State: "TX", Position: pos, PickupEnabled: true},
			dist:  port.RoadDistance{RoadMiles: 7.4, DurationMinutes: 18, Method: "ROAD_OSRM"},
			quote: port.LocalDeliveryQuote{Available: true, CustomerChargeMinor: 1128, SellerFeeMinor: 200}}
	}

	f := ok()
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{LocalDelivery: f, Offers: offers}}
	sum := localSummary()
	s.priceLocalDelivery(context.Background(), &sum, addr)
	ld := sum.Packages[0].LocalDelivery
	if ld == nil || !ld.Available || *ld.ChargeMinor != 1128 || *ld.RoadMiles != 7.4 || *ld.DurationMinutes != 18 || ld.FromCity != "Houston, TX" ||
		len(ld.OfferIDs) != 1 || ld.UnavailableReason != nil {
		t.Fatalf("quote = %+v", ld)
	}
	if o := sum.Packages[0].DeliveryOptions[0]; o.FeeMinor != 1128 || o.Label != "$11.28 · AfroTransact Delivery from Houston, TX" {
		t.Fatalf("option = %+v", o)
	}
	if sum.ShippingMinor != 1628 || sum.TotalMinor != 6128 || sum.Packages[1].LocalDelivery != nil {
		t.Fatalf("totals = %d / %d", sum.ShippingMinor, sum.TotalMinor)
	}
	if f.to.Lat == nil || *f.to.Lat != lat || f.to.Address != nil {
		t.Fatalf("the address book's coordinates go to geo: %+v", f.to)
	}

	reason := func(mut func(*fakeLocal), a *port.IdentityAddress) *domain.CheckoutLocalDelivery {
		t.Helper()
		f := ok()
		mut(f)
		s := &checkoutPaymentServiceImpl{d: ShopperDeps{LocalDelivery: f, Offers: offers}}
		sum := localSummary()
		s.priceLocalDelivery(context.Background(), &sum, a)
		return sum.Packages[0].LocalDelivery
	}
	radius := 25.0
	far := reason(func(f *fakeLocal) {
		f.dist.RoadMiles = 31
		f.quote = port.LocalDeliveryQuote{Available: false, UnavailableReason: "BEYOND_MAX_RADIUS", MaxRadiusMiles: &radius}
	}, addr)
	if far.Available || *far.UnavailableReason != domain.LocalOutOfRange || far.ChargeMinor != nil ||
		*far.Message != "Ama's Market is 31 miles from Houston, TX: too far for AfroTransact Delivery (up to 25 miles). Remove it or choose another address." {
		t.Fatalf("out of range = %+v %s", far, *far.Message)
	}
	cases := map[string]func(*fakeLocal){
		domain.LocalPickupUnavailable: func(f *fakeLocal) { f.loc.PickupEnabled = false },
		domain.LocalAddressNotLocated: func(f *fakeLocal) {
			f.distErr = &port.UpstreamProblem{Upstream: "geo", Status: http.StatusUnprocessableEntity, Code: "geo.place-not-found"}
		},
		domain.LocalQuoteUnavailable: func(f *fakeLocal) { f.quoteErr = errors.New("payments down") },
		domain.LocalUnavailable: func(f *fakeLocal) {
			f.quote = port.LocalDeliveryQuote{Available: false, UnavailableReason: "NOT_CONFIGURED"}
		},
	}
	for want, mut := range cases {
		if got := reason(mut, addr); *got.UnavailableReason != want || got.Available != (want == domain.LocalQuoteUnavailable) {
			t.Errorf("%s: got %+v", want, got)
		}
	}
	if got := reason(func(f *fakeLocal) { f.locErr = &port.UpstreamProblem{Upstream: "seller", Status: 404} }, addr); *got.UnavailableReason != domain.LocalPickupUnavailable {
		t.Errorf("unknown location: %+v", got)
	}
	if got := reason(func(*fakeLocal) {}, nil); *got.UnavailableReason != domain.LocalQuoteUnavailable || !got.Available {
		t.Errorf("no address yet: %+v", got)
	}
}

func TestPlaceOrderMapsLocalDeliveryErrors(t *testing.T) {
	for code, want := range map[string]string{
		"orders.delivery-out-of-range":        domain.CodeDeliveryOutOfRange,
		"orders.delivery-unavailable":         domain.CodeDeliveryUnavailable,
		"orders.delivery-pickup-unavailable":  domain.CodeDeliveryPickupUnavailable,
		"orders.delivery-address-not-located": domain.CodeDeliveryAddressNotLocated,
	} {
		err := orderErr(&port.UpstreamProblem{Upstream: "orders", Status: 422, Code: code, Detail: "Ama's Market is 31 miles from Houston, TX: too far"})
		p, ok := domain.AsProblem(err)
		if !ok || p.Code != want || p.Status != 422 || p.Errors["deliverySelections"] == "" {
			t.Errorf("%s → %+v", code, p)
		}
	}
}
