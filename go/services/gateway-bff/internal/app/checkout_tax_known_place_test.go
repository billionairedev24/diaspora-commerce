package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// placeLocation answers Current with a fixed place (the session's deliver-to location).
type placeLocation struct {
	LocationService
	loc domain.DeliveryLocation
}

func (p placeLocation) Current(context.Context, *domain.Caller) domain.DeliveryLocation { return p.loc }

func knownPlaceSvc(f *fakeReturns, loc domain.DeliveryLocation, addrs ...port.IdentityAddress) *checkoutPaymentServiceImpl {
	cart := domain.Cart{Currency: "USD", Packages: []domain.CartPackage{basketPkg().CartPackage}}
	cart.Packages[0].Lines[0].Selected, cart.Packages[0].Lines[0].MaxQuantity = true, 10
	return &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: f, Carts: fakeCarts{cart: cart}, Identity: fakeAddresses{addrs: addrs},
		Location: placeLocation{loc: loc}}}
}

var houstonLoc = domain.DeliveryLocation{ZIP: "77042", City: "Houston", State: "TX", Label: "Houston", Serviceable: true}

func TestASignedOutVisitorWithADetectedPlaceGetsAnEstimateNotAZipPrompt(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	loc := houstonLoc
	loc.Source = domain.LocationDetected
	got, err := knownPlaceSvc(f, loc).TaxEstimate(context.Background(), &domain.Caller{}, "", nil)
	if err != nil || got.TaxEstimateStatus != domain.TaxEstimated || got.TaxMinor != 214 || got.TaxJurisdictionLabel != "Houston, TX" ||
		got.TaxPlaceSource != domain.TaxPlaceDetected || got.TaxPlacePrecision != domain.PrecisionZip || got.TaxZip != "77042" {
		t.Fatalf("%+v %v", got, err)
	}
	if f.req.ShipTo.PostalCode != "77042" || f.req.ShipTo.Precision != "" {
		t.Fatalf("a real ZIP is quoted at ZIP precision: %+v", f.req.ShipTo)
	}
}

func TestAPlaceKnownOnlyByCityIsQuotedAtCityPrecision(t *testing.T) {
	f := &fakeReturns{answer: taxable}
	loc := houstonLoc
	loc.Source, loc.Precision = domain.LocationDetected, domain.PrecisionCity
	got, err := knownPlaceSvc(f, loc).TaxEstimate(context.Background(), &domain.Caller{}, "", nil)
	if err != nil || got.TaxEstimateStatus != domain.TaxEstimated || got.TaxPlacePrecision != domain.PrecisionCity || got.TaxPlaceSource != domain.TaxPlaceDetected {
		t.Fatalf("%+v %v", got, err)
	}
	if f.req.ShipTo.Precision != domain.PrecisionCity {
		t.Fatalf("tax-returns must be told the place is city precision: %+v", f.req.ShipTo)
	}
}

func TestAChosenPlaceBeatsDetectedAndASavedAddressBeatsBoth(t *testing.T) {
	loc := domain.DeliveryLocation{ZIP: "30303", City: "Atlanta", State: "GA", Source: domain.LocationChosen}
	got, err := knownPlaceSvc(&fakeReturns{answer: taxable}, loc).TaxEstimate(context.Background(), &domain.Caller{}, "", nil)
	if err != nil || got.TaxPlaceSource != domain.TaxPlaceChosen || got.TaxJurisdictionLabel != "Atlanta, GA" {
		t.Fatalf("%+v %v", got, err)
	}
	saved := port.IdentityAddress{ID: "a", State: "TX", PostalCode: "77002", City: "Houston", IsDefault: true}
	got, err = knownPlaceSvc(&fakeReturns{answer: taxable}, loc, saved).TaxEstimate(context.Background(), shopper, "", nil)
	if err != nil || got.TaxPlaceSource != domain.TaxPlaceSavedAddress || got.TaxZip != "77002" || got.TaxPlacePrecision != domain.PrecisionZip {
		t.Fatalf("%+v %v", got, err)
	}
}

func TestAnIncompleteSavedAddressFallsBackToTheKnownPlaceAndNoPlaceStillAsksForAZip(t *testing.T) {
	loc := houstonLoc
	loc.Source = domain.LocationDefault
	got, err := knownPlaceSvc(&fakeReturns{answer: taxable}, loc, port.IdentityAddress{ID: "a", City: "Nowhere", IsDefault: true}).
		TaxEstimate(context.Background(), shopper, "", nil)
	if err != nil || got.TaxEstimateStatus != domain.TaxEstimated || got.TaxPlaceSource != domain.TaxPlaceDefault {
		t.Fatalf("%+v %v", got, err)
	}
	// no ZIP at all (never invented): the ZIP prompt
	none, err := knownPlaceSvc(&fakeReturns{answer: taxable}, domain.DeliveryLocation{}).TaxEstimate(context.Background(), &domain.Caller{}, "", nil)
	if err != nil || none.TaxEstimateStatus != domain.TaxNeedsAddress || none.TaxMinor != 0 || none.TaxPlaceSource != "" {
		t.Fatalf("%+v %v", none, err)
	}
	// a ZIP without a state and no way to look it up is not enough either
	none, _ = knownPlaceSvc(&fakeReturns{answer: taxable}, domain.DeliveryLocation{ZIP: "77042", Source: domain.LocationDetected}).
		TaxEstimate(context.Background(), &domain.Caller{}, "", nil)
	if none.TaxEstimateStatus != domain.TaxNeedsAddress {
		t.Fatalf("%+v", none)
	}
}

func TestAPlaceWithBlockedItemsStillSaysWhy(t *testing.T) {
	f := &fakeReturns{answer: func(port.UpTaxQuoteLine) port.UpTaxLineQuote { return port.UpTaxLineQuote{DecisionStatus: "NEEDS_APPROVAL"} }}
	loc := houstonLoc
	loc.Source = domain.LocationDetected
	got, err := knownPlaceSvc(f, loc).TaxEstimate(context.Background(), &domain.Caller{}, "", nil)
	if err != nil || got.TaxEstimateStatus != domain.TaxUnavailable || got.TaxUnavailableReason != domain.TaxReasonBlockedItems || len(got.BlockedLines) == 0 {
		t.Fatalf("%+v %v", got, err)
	}
}
