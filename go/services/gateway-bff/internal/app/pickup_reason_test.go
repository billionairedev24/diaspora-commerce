package app

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Why a package shows no pickup is machine-readable, never ids, and never set when pickup is offered.
func TestPickupReasonSaysWhyThereIsNoPickup(t *testing.T) {
	lat, lng := 29.76, -95.37
	located := &port.IdentityAddress{PostalCode: "77002", Latitude: &lat, Longitude: &lng}
	unlocated := &port.IdentityAddress{PostalCode: "77002"}
	dallas := &port.GeoPoint{Lat: 32.78, Lng: -96.80}
	seller := domain.CheckoutPackage{CartPackage: domain.CartPackage{Fulfilment: domain.AfroTransactDelivery, Seller: domain.Seller{ID: "org_ama"}}}
	fc := domain.CheckoutPackage{CartPackage: domain.CartPackage{Fulfilment: domain.FulfilledByAfroTransact}}

	cases := []struct {
		name         string
		pkg          domain.CheckoutPackage
		own          []port.PickupLocation
		addr         *port.IdentityAddress
		zip          string
		lookupFailed bool
		want         string
	}{
		{"no address yet", seller, nil, nil, "", false, domain.PickupWhyNeedsAddress},
		{"short zip", seller, nil, &port.IdentityAddress{PostalCode: "770"}, "770", false, domain.PickupWhyNeedsAddress},
		{"carrier-gateway down", seller, nil, located, "77002", true, domain.PickupWhyLookupFailed},
		{"seller never enabled pickup", seller, nil, located, "77002", false, domain.PickupWhySellerNotEnabled},
		{"only an inactive place", seller, []port.PickupLocation{pickupLoc("store", "SELLER", "org_ama", "77042", nil, false)}, located, "77002", false, domain.PickupWhySellerNotEnabled},
		{"another seller's place", seller, []port.PickupLocation{pickupLoc("store", "SELLER", "org_b", "77042", nil, true)}, located, "77002", false, domain.PickupWhySellerNotEnabled},
		{"place too far away", seller, []port.PickupLocation{pickupLoc("store", "SELLER", "org_ama", "75201", dallas, true)}, located, "77002", false, domain.PickupWhyTooFar},
		{"unlocated place in another zip area", seller, []port.PickupLocation{pickupLoc("store", "SELLER", "org_ama", "10001", nil, true)}, unlocated, "77002", false, domain.PickupWhyOtherArea},
		{"afrotransact package with no counter near", fc, nil, located, "77002", false, domain.PickupWhyNoPlatformPoint},
	}
	for _, c := range cases {
		if got := pickupReason(c.pkg, nil, c.own, c.addr, c.zip, c.lookupFailed); got != c.want {
			t.Errorf("%s: reason %q, want %q", c.name, got, c.want)
		}
	}

	withPoints := seller
	withPoints.PickupPoints = []domain.PickupPoint{{ID: "store"}}
	if got := pickupReason(withPoints, nil, nil, located, "77002", false); got != "" {
		t.Errorf("a package with pickup points has no reason, got %q", got)
	}
}

func TestPickupIssuesExplainWhyAnAddressIsNotOffered(t *testing.T) {
	located := &domain.GeoPosition{Lat: 29.7, Lng: -95.5, Precision: "ADDRESS"}
	hours := []domain.PickupWindow{{Day: "MONDAY", Opens: "09:00", Closes: "17:00"}}
	off := withPickupIssues(domain.ShipFromAddress{Position: located}, domain.CustomerPickup{})
	if off.Ready || len(off.Issues) != 1 || off.Issues[0].Code != domain.PickupIssueNotEnabled || !off.Issues[0].Blocking {
		t.Fatalf("pickup off: %+v", off)
	}
	noHours := withPickupIssues(domain.ShipFromAddress{Position: located}, domain.CustomerPickup{Enabled: true})
	if noHours.Ready || len(noHours.Issues) != 1 || noHours.Issues[0].Code != domain.PickupIssueNoHours {
		t.Fatalf("no hours: %+v", noHours)
	}
	unlocated := withPickupIssues(domain.ShipFromAddress{}, domain.CustomerPickup{Enabled: true, Hours: hours})
	if !unlocated.Ready || len(unlocated.Issues) != 1 || unlocated.Issues[0].Code != domain.PickupIssueNotLocated || unlocated.Issues[0].Blocking {
		t.Fatalf("not located is advisory, the address is still offered to the same ZIP area: %+v", unlocated)
	}
	zip := withPickupIssues(domain.ShipFromAddress{Position: &domain.GeoPosition{Precision: "ZIP"}}, domain.CustomerPickup{Enabled: true, Hours: hours})
	if len(zip.Issues) != 1 || zip.Issues[0].Code != domain.PickupIssueApprox {
		t.Fatalf("zip precision: %+v", zip)
	}
	ok := withPickupIssues(domain.ShipFromAddress{Position: located}, domain.CustomerPickup{Enabled: true, Hours: hours})
	if !ok.Ready || len(ok.Issues) != 0 {
		t.Fatalf("a ready address has no issues: %+v", ok)
	}
}

func TestPickupOfferNoteForStaff(t *testing.T) {
	full := jobj{"active": true, "hours": []any{map[string]any{"weekday": float64(1)}}, "position": map[string]any{"lat": 1.0, "lng": 2.0}}
	if n := pickupOfferNote(full); n != "" {
		t.Fatalf("offered: %q", n)
	}
	bad := jobj{"active": false, "hours": []any{}}
	if n := pickupOfferNote(bad); n != "Paused: not offered; No opening hours: not offered; Not located: only shoppers in the same ZIP area see it" {
		t.Fatalf("note: %q", n)
	}
}
