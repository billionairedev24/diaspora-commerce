package app

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func pickupLoc(id, kind, owner, zip string, pos *port.GeoPoint, active bool) port.PickupLocation {
	l := port.PickupLocation{ID: id, Kind: kind, OwnerOrg: owner, Name: id, Active: active, Position: pos}
	l.Address.Line1, l.Address.City, l.Address.State, l.Address.PostalCode = "1 Main St", "Houston", "TX", zip
	return l
}

func TestPickupPointsFollowTheFulfilment(t *testing.T) {
	lat, lng := 29.76, -95.37
	addr := &port.IdentityAddress{PostalCode: "77002", Latitude: &lat, Longitude: &lng}
	near := &port.GeoPoint{Lat: 29.80, Lng: -95.40}
	nearer := &port.GeoPoint{Lat: 29.761, Lng: -95.371}
	far := &port.GeoPoint{Lat: 32.78, Lng: -96.80} // Dallas
	platform := []port.PickupLocation{
		pickupLoc("counter", "PLATFORM_COUNTER", "afrotransact", "77005", near, true),
		pickupLoc("locker", "LOCKER", "afrotransact", "77002", nearer, true),
		pickupLoc("dallas", "PLATFORM_COUNTER", "afrotransact", "75201", far, true),
		pickupLoc("closed", "LOCKER", "afrotransact", "77002", nearer, false),
		pickupLoc("someone", "SELLER", "org_x", "77002", nearer, true),
	}
	fc := domain.CheckoutPackage{CartPackage: domain.CartPackage{Fulfilment: domain.FulfilledByAfroTransact}}
	got := pickupPointsFor(fc, platform, nil, addr, "77002")
	if len(got) != 2 || got[0].ID != "locker" || got[1].ID != "counter" || got[0].SetBy != "platform" || got[0].DistanceMiles == nil || *got[0].DistanceMiles > 0.2 {
		t.Fatalf("platform points, nearest first: %+v", got)
	}

	own := []port.PickupLocation{
		pickupLoc("store", "SELLER", "org_ama", "77042", nil, true),      // not located: same ZIP area
		pickupLoc("other-area", "SELLER", "org_ama", "10001", nil, true), // not located, elsewhere
		pickupLoc("not-mine", "SELLER", "org_b", "77042", near, true),    // another seller's
		pickupLoc("counter", "PLATFORM_COUNTER", "org_ama", "77042", near, true),
	}
	seller := domain.CheckoutPackage{CartPackage: domain.CartPackage{Fulfilment: domain.AfroTransactDelivery, Seller: domain.Seller{ID: "org_ama"}}}
	got = pickupPointsFor(seller, platform, own, addr, "77002")
	if len(got) != 1 || got[0].ID != "store" || got[0].SetBy != "seller" || got[0].DistanceMiles != nil {
		t.Fatalf("seller points: %+v", got)
	}

	addPickupOption(&seller, got)
	if o := seller.DeliveryOptions[len(seller.DeliveryOptions)-1]; o.Key != "pickup" || !o.Free || o.FeeMinor != 0 || len(seller.PickupPoints) != 1 {
		t.Fatalf("pickup option: %+v", seller.DeliveryOptions)
	}
	none := domain.CheckoutPackage{}
	addPickupOption(&none, nil)
	if len(none.DeliveryOptions) != 0 {
		t.Fatal("no points, no pickup option")
	}
}

func TestHaversineMiles(t *testing.T) {
	// Houston downtown → Dallas downtown is about 225 miles in a straight line.
	if d := domain.HaversineMiles(29.7604, -95.3698, 32.7767, -96.7970); d < 220 || d > 230 {
		t.Fatalf("distance %f", d)
	}
}
