package domain_test

import (
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

var now = time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)

func offer(atin, seller, fulfilment string, price int64) domain.Offer {
	return domain.Offer{VariantATIN: atin, ProductATIN: "P" + atin, Title: atin, SellerOrgID: seller,
		SellerName: seller, Fulfilment: fulfilment, PriceMinor: price, Currency: "USD"}
}

func TestAddIsCappedByStock(t *testing.T) {
	var c domain.Cart
	o := offer("A", "org1", "SELLER", 500)
	if err := c.Add(o, 2, 3, now); err != nil {
		t.Fatal(err)
	}
	if err := c.Add(o, 2, 3, now); !errors.Is(err, domain.ErrQuantityTooHigh) {
		t.Fatalf("expected cap at 3, got %v", err)
	}
	if err := c.Add(o, 1, 3, now); err != nil || c.Lines[0].Quantity != 3 {
		t.Fatalf("expected quantity 3, got %+v %v", c.Lines, err)
	}
	if err := c.Add(offer("B", "org1", "SELLER", 1), 1, 0, now); !errors.Is(err, domain.ErrOutOfStock) {
		t.Fatalf("out of stock items cannot be added, got %v", err)
	}
}

func TestStepperSetsExactQuantityAndZeroRemoves(t *testing.T) {
	var c domain.Cart
	_ = c.Add(offer("A", "org1", "SELLER", 500), 1, 10, now)
	if err := c.SetQuantity("A", 11, 10); !errors.Is(err, domain.ErrQuantityTooHigh) {
		t.Fatalf("expected cap, got %v", err)
	}
	if err := c.SetQuantity("A", 0, 10); err != nil || len(c.Lines) != 0 {
		t.Fatalf("0 should remove: %v %+v", err, c.Lines)
	}
}

func TestSaveForLaterAndMoveBack(t *testing.T) {
	var c domain.Cart
	_ = c.Add(offer("A", "org1", "SELLER", 500), 4, 10, now)
	if err := c.SaveForLater("A"); err != nil || len(c.Lines) != 0 || len(c.Saved) != 1 {
		t.Fatalf("save: %v %+v", err, c)
	}
	if err := c.MoveToCart("A", 2); err != nil || c.Lines[0].Quantity != 2 {
		t.Fatalf("move back should cap to stock: %v %+v", err, c.Lines)
	}
}

func TestMergeAddsAndCaps(t *testing.T) {
	var user, guest domain.Cart
	_ = user.Add(offer("A", "org1", "SELLER", 500), 2, 10, now)
	_ = guest.Add(offer("A", "org1", "SELLER", 500), 5, 10, now)
	_ = guest.Add(offer("B", "org2", "AFROTRANSACT", 300), 3, 10, now)
	user.Merge(guest, map[string]int{"A": 6, "B": 1})
	if user.Lines[0].Quantity != 6 || user.Lines[1].Quantity != 1 {
		t.Fatalf("merge caps: %+v", user.Lines)
	}
}

func TestPresentGroupsPackagesAndFlags(t *testing.T) {
	var c domain.Cart
	_ = c.Add(offer("A", "org_lagos", "SELLER", 1000), 2, 10, now)
	_ = c.Add(offer("B", "org_retail", "AFROTRANSACT", 899), 1, 10, now)
	_ = c.Add(offer("C", "org_lagos", "SELLER", 250), 1, 10, now)
	_ = c.Add(offer("D", "org_dakar", "SELLER", 700), 1, 10, now)
	live := map[string]domain.Offer{
		"A": offer("A", "org_lagos", "SELLER", 1200), // price went up
		"B": offer("B", "org_retail", "AFROTRANSACT", 899),
		"C": offer("C", "org_lagos", "SELLER", 250),
		// D is no longer live
	}
	v := c.Present(live, map[string]int{"A": 4, "B": 3, "C": 0, "D": 9})
	if len(v.Packages) != 3 || v.Packages[0].Fulfilment != "AFROTRANSACT" {
		t.Fatalf("packages: %+v", v.Packages)
	}
	var a, cc, d domain.LineView
	for _, p := range v.Packages {
		for _, l := range p.Lines {
			switch l.VariantATIN {
			case "A":
				a = l
			case "C":
				cc = l
			case "D":
				d = l
			}
		}
	}
	if !a.PriceChanged || !a.LowStock || a.LineTotal != 2400 {
		t.Fatalf("line A flags: %+v", a)
	}
	if !cc.Unavailable || !d.Unavailable {
		t.Fatal("out-of-stock and non-live lines must be unavailable")
	}
	if v.SubtotalMinor != 2400+899 || v.ItemCount != 3 || !v.Checkoutable {
		t.Fatalf("totals: %+v", v)
	}
}

// S259: AfroTransact Delivery lines are collected per seller pickup location, so each location is its own package.
func TestPresentSplitsAfroTransactDeliveryByPickupLocation(t *testing.T) {
	delivery := func(atin, location string) domain.Offer {
		o := offer(atin, "org_ama", domain.FulfilmentAfroTransactDelivery, 500)
		o.ShipFromLocationID = location
		return o
	}
	var c domain.Cart
	_ = c.Add(delivery("A", "loc-north"), 1, 10, now)
	_ = c.Add(delivery("B", "loc-south"), 1, 10, now)
	_ = c.Add(delivery("C", "loc-north"), 2, 10, now)
	_ = c.Add(offer("D", "org_ama", "SELLER", 300), 1, 10, now)
	live := map[string]domain.Offer{"A": delivery("A", "loc-north"), "B": delivery("B", "loc-south"),
		"C": delivery("C", "loc-north"), "D": offer("D", "org_ama", "SELLER", 300)}
	v := c.Present(live, map[string]int{"A": 9, "B": 9, "C": 9, "D": 9})
	if len(v.Packages) != 3 {
		t.Fatalf("want north, south and seller-shipped packages, got %+v", v.Packages)
	}
	byLocation := map[string]domain.Package{}
	for _, p := range v.Packages {
		if p.Fulfilment == domain.FulfilmentAfroTransactDelivery {
			byLocation[p.PickupLocationID] = p
		} else if p.PickupLocationID != "" {
			t.Fatalf("only AfroTransact Delivery packages carry a pickup location: %+v", p)
		}
	}
	north, south := byLocation["loc-north"], byLocation["loc-south"]
	if len(north.Lines) != 2 || north.SubtotalMinor != 1500 || len(south.Lines) != 1 || south.SubtotalMinor != 500 {
		t.Fatalf("north %+v south %+v", north, south)
	}
}
