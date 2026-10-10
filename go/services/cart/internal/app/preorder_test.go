package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

type eligibility struct {
	lines map[string]domain.PreorderLine
	err   error
	seen  domain.Location
}

func (e *eligibility) Eligibility(_ context.Context, loc domain.Location, _ []string) (map[string]domain.PreorderLine, error) {
	e.seen = loc
	return e.lines, e.err
}

func preorderSvc(e *eligibility) app.CartService {
	guests, users := &memRepo{carts: map[string]domain.Cart{}}, &memRepo{carts: map[string]domain.Cart{}}
	return app.NewCartService(guests, users, offers{"ATJOL00001": jollof, "ATDRS00001": dress},
		stock{"ATJOL00001": 3, "ATDRS00001": 10}, marketplace, app.WithPreorders(e))
}

func TestPreorderPackagesNeedALocationAndKeepTheDoorOrPickupChoice(t *testing.T) {
	e := &eligibility{lines: map[string]domain.PreorderLine{"ATJOL00001": {Preorder: true, WindowID: "win-1",
		WindowName: "Lubbock Pre-order #7", Stage: "OPEN", DeliversOn: "2026-10-25", Door: true, Pickup: true,
		DoorFeeMinor: 799, Currency: "USD"}}}
	s := preorderSvc(e)
	u := domain.Owner{Kind: domain.OwnerUser, ID: "u1"}
	ctx := context.Background()
	if _, err := s.Add(ctx, u, "ATJOL00001", "", 1); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Add(ctx, u, "ATDRS00001", "", 1); err != nil {
		t.Fatal(err)
	}
	v, _ := s.View(ctx, u)
	for _, p := range v.Packages {
		if p.Preorder != nil {
			t.Fatalf("no location, no pre-order packages: %+v", p)
		}
	}
	located := app.WithLocation(ctx, domain.Location{Country: "US", Region: "TX", City: "Lubbock"})
	v, err := s.View(located, u)
	if err != nil || len(v.Packages) != 2 || v.Packages[0].Preorder == nil {
		t.Fatalf("view: %+v %v", v, err)
	}
	if e.seen.City != "Lubbock" || v.Packages[0].PackageID != "preorder:win-1" || v.Packages[0].Preorder.Fulfilment != "DOOR" ||
		v.Packages[0].Preorder.FeeMinor != 799 || !v.Checkoutable {
		t.Fatalf("pre-order package: %+v", v.Packages[0].Preorder)
	}
	v, err = s.SetPreorderFulfilment(located, u, "win-1", "pickup")
	if err != nil || v.Packages[0].Preorder.Fulfilment != "PICKUP" || v.Packages[0].Preorder.FeeMinor != 0 ||
		v.PreorderFulfilment["win-1"] != "PICKUP" {
		t.Fatalf("pickup: %+v %v", v.Packages[0].Preorder, err)
	}
	if _, err := s.SetPreorderFulfilment(located, u, "win-1", "drone"); !errors.Is(err, domain.ErrInvalidFulfilment) {
		t.Fatalf("invalid fulfilment: %v", err)
	}
}

func TestClosedWindowLocksCheckoutAndOutagesDegradeToShipsNow(t *testing.T) {
	e := &eligibility{lines: map[string]domain.PreorderLine{"ATJOL00001": {Preorder: true, WindowID: "win-2",
		Stage: "CLOSED", Door: true, DoorFeeMinor: 799}}}
	s := preorderSvc(e)
	u := domain.Owner{Kind: domain.OwnerUser, ID: "u2"}
	ctx := app.WithLocation(context.Background(), domain.Location{Country: "US", Region: "TX"})
	v, err := s.Add(ctx, u, "ATJOL00001", "", 1)
	if err != nil || v.Checkoutable || !v.Packages[0].Preorder.Locked {
		t.Fatalf("closed window must lock checkout: %+v %v", v, err)
	}
	e.err, e.lines = errors.New("down"), nil
	v, err = s.View(ctx, u)
	if err != nil || !v.Checkoutable || v.Packages[0].Preorder != nil {
		t.Fatalf("outage: %+v %v", v, err)
	}
}
