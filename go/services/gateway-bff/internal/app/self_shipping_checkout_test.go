package app

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The seller's own shipping (staff switch, off by default): the checkout offers "Shipped by the seller" only while the switch is
// on and only for a seller that opted in, at the seller's one flat charge, and nothing is guessed when orders cannot answer.

type fakeSelfShipping struct {
	charges map[string]int64
	err     error
	asked   int
}

func (f *fakeSelfShipping) Offered(_ context.Context, _ string, orgIDs []string) (map[string]int64, error) {
	f.asked++
	if f.err != nil {
		return nil, f.err
	}
	out := map[string]int64{}
	for _, id := range orgIDs {
		if c, ok := f.charges[id]; ok {
			out[id] = c
		}
	}
	return out, nil
}

func selfShipService(switchOn bool, f *fakeSelfShipping) *checkoutPaymentServiceImpl {
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", Version: 3, FlatFeeMinor: 599,
		SellerSelfShippingEnabled: switchOn})
	return &checkoutPaymentServiceImpl{d: ShopperDeps{Estimator: e, SelfShipping: f}}
}

func sellerOnlySummary() domain.CheckoutSummary {
	p := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org_b:SELLER", Fulfilment: domain.ShipsFromSeller,
		Seller: domain.Seller{ID: "org_b", Name: "Bee"}, SubtotalMinor: 9000, DeliveryOptions: []domain.DeliveryOption{}}}
	return domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{p}}
}

func TestSwitchOffNeverOffersSellerShippingAndDoesNotEvenAskOrders(t *testing.T) {
	f := &fakeSelfShipping{charges: map[string]int64{"org_b": 750}}
	s := selfShipService(false, f)
	sum := sellerOnlySummary()
	charges := s.selfShippingCharges(context.Background(), &sum)
	addSellerShipsOption(&sum.Packages[0], charges)
	if len(sum.Packages[0].DeliveryOptions) != 0 || f.asked != 0 {
		t.Fatalf("switch off: options=%+v asked=%d", sum.Packages[0].DeliveryOptions, f.asked)
	}
}

func TestSwitchOnOffersTheSellersOneFlatChargeOnlyToSellersWhoOptedIn(t *testing.T) {
	f := &fakeSelfShipping{charges: map[string]int64{"org_b": 750}}
	s := selfShipService(true, f)
	sum := sellerOnlySummary()
	other := sum.Packages[0]
	other.PackageID, other.Seller = "org_c:SELLER", domain.Seller{ID: "org_c", Name: "Cee"}
	sum.Packages = append(sum.Packages, other)
	charges := s.selfShippingCharges(context.Background(), &sum)
	for i := range sum.Packages {
		addSellerShipsOption(&sum.Packages[i], charges)
	}
	opts := sum.Packages[0].DeliveryOptions
	if len(opts) != 1 || opts[0].Key != domain.OptionSellerShips || opts[0].FeeMinor != 750 || opts[0].Free || opts[0].Label != "Shipped by the seller" {
		t.Fatalf("org_b: %+v", opts)
	}
	if len(sum.Packages[1].DeliveryOptions) != 0 {
		t.Fatalf("org_c did not opt in: %+v", sum.Packages[1].DeliveryOptions)
	}
	if got := packageShippingFee(sum.Packages[0], map[string]string{"org_b:SELLER": domain.OptionSellerShips}); got != 750 {
		t.Fatalf("the seller's charge is what is charged: %d", got)
	}
}

func TestOrdersNotAnsweringOffersNothingInsteadOfGuessing(t *testing.T) {
	s := selfShipService(true, &fakeSelfShipping{err: errors.New("orders down")})
	sum := sellerOnlySummary()
	if charges := s.selfShippingCharges(context.Background(), &sum); charges != nil {
		t.Fatalf("charges = %v", charges)
	}
}

func TestAFreeSellerChargeIsFreeAndAFulfilledPackageNeverGetsIt(t *testing.T) {
	p := sellerOnlySummary().Packages[0]
	addSellerShipsOption(&p, map[string]int64{"org_b": 0})
	if len(p.DeliveryOptions) != 1 || !p.DeliveryOptions[0].Free {
		t.Fatalf("a $0 charge is free shipping: %+v", p.DeliveryOptions)
	}
	fba := domain.CheckoutPackage{CartPackage: domain.CartPackage{PackageID: "org_b:AFROTRANSACT_DELIVERY", Fulfilment: domain.AfroTransactDelivery,
		Seller: domain.Seller{ID: "org_b"}}}
	addSellerShipsOption(&fba, map[string]int64{"org_b": 750})
	if len(fba.DeliveryOptions) != 0 {
		t.Fatalf("a package AfroTransact collects is never the seller's own shipping: %+v", fba.DeliveryOptions)
	}
}

type recordingMembership struct {
	port.MembershipClient
	req port.UpMembershipSavingsRequest
}

func (r *recordingMembership) Savings(_ context.Context, _ string, req port.UpMembershipSavingsRequest) (port.UpMembershipWaiverQuote, error) {
	r.req = req
	return port.UpMembershipWaiverQuote{}, nil
}

func TestNoPlanWaivesTheSellersOwnShipping(t *testing.T) {
	p := sellerOnlySummary().Packages[0]
	addSellerShipsOption(&p, map[string]int64{"org_b": 750})
	m := &recordingMembership{}
	c := &domain.Caller{BearerToken: "tok", Subject: "u1"}
	quoteMembership(context.Background(), m, c, []domain.CheckoutPackage{p}, map[string]string{p.PackageID: domain.OptionSellerShips}, "USD")
	if len(m.req.Fees) != 0 {
		t.Fatalf("the seller's shipping is the seller's revenue and is never sent to be waived: %+v", m.req.Fees)
	}
}

func TestFulfilmentModeSellerShipsIsDerivedFromTheNewMethodOnly(t *testing.T) {
	if got := domain.FulfilmentModeOf("SELLER_FULFILLED", "SELLER_SELF_SHIPS", false); got != domain.ModeSellerShips {
		t.Fatalf("new method = %s", got)
	}
	for _, dm := range []string{"SELLER_SHIPS", "CARRIER"} {
		if got := domain.FulfilmentModeOf("SELLER_FULFILLED", dm, false); got != domain.ModeLegacySellerShipped {
			t.Fatalf("%s must stay read-only history, got %s", dm, got)
		}
	}
	if domain.DeliveredByAfroTransact(domain.ModeSellerShips) {
		t.Fatal("a seller-shipped order never says AfroTransact delivers")
	}
	sh := shipmentOf(port.OrderShipmentView{ID: "s1", FulfilmentType: "SELLER_FULFILLED", DeliveryMethod: "SELLER_SELF_SHIPS", Status: "READY",
		NextActions: []string{"picked_up"}})
	if sh.FulfilmentMode != domain.ModeSellerShips || !sh.NeedsShipDetails || sh.CanBuyLabel || sh.HasLabel || len(sh.NextActions) != 1 {
		t.Fatalf("seller-shipped sheet: %+v", sh)
	}
}
