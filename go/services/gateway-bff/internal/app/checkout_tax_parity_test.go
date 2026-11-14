package app

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// parityPath is shared with orders (CheckoutTaxParityTest.java): the estimate and the order must send tax-returns the
// same request for the same basket. Same request + same tax-returns function = the final tax equals the estimate.
const parityPath = "../../../../../tools/checkout/checkout-tax-parity.json"

type parityLine struct {
	LineID          string         `json:"lineId"`
	ATIN            string         `json:"atin"`
	OfferID         string         `json:"offerId"`
	ProductType     string         `json:"productType"`
	Quantity        int            `json:"quantity"`
	UnitPriceMinor  int64          `json:"unitPriceMinor"`
	DiscountMinor   int64          `json:"discountMinor"`
	Outcome         string         `json:"outcome"`
	SellerBehaviour map[string]any `json:"sellerBehaviour"`
}

type parityCase struct {
	Name                  string           `json:"name"`
	Allocation            string           `json:"allocation"`
	Currency              string           `json:"currency"`
	ShipTo                port.UpTaxShipTo `json:"shipTo"`
	ShippingDiscountMinor int64            `json:"shippingDiscountMinor"`
	RateBps               int64            `json:"rateBps"`
	Packages              []struct {
		SellerOrgID           *string      `json:"sellerOrgId"`
		FeeMinor              int64        `json:"feeMinor"`
		MembershipWaivedMinor int64        `json:"membershipWaivedMinor"`
		Lines                 []parityLine `json:"lines"`
	} `json:"packages"`
	Expected struct {
		RequestLines []map[string]any `json:"requestLines"`
		TaxMinor     int64            `json:"taxMinor"`
	} `json:"expected"`
}

func TestEstimateSendsTaxReturnsTheSameRequestAsOrders(t *testing.T) {
	raw, err := os.ReadFile(parityPath)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Cases []parityCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Cases) < 10 {
		t.Fatalf("expected the shared vectors, got %d cases", len(doc.Cases))
	}
	for _, c := range doc.Cases {
		method, err := ParseShippingAllocation(c.Allocation)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		sum := domain.CheckoutSummary{Currency: c.Currency, ShippingDiscountMinor: c.ShippingDiscountMinor, CanPlaceOrder: true,
			BlockedLines: []domain.CheckoutBlockedLine{}, TaxLines: []domain.CheckoutTaxLine{}, Coupon: &domain.CheckoutCoupon{Applicable: true}}
		outcome := map[string]string{}
		for i, p := range c.Packages {
			cp := domain.CartPackage{PackageID: "pkg" + string(rune('a'+i)), Seller: domain.Seller{ID: "afrotransact", Kind: domain.SellerKindPlatform},
				DeliveryOptions: []domain.DeliveryOption{{Key: "carrier", FeeMinor: p.FeeMinor}}}
			if p.SellerOrgID != nil {
				cp.Seller, cp.SellerOrgRaw = domain.Seller{ID: *p.SellerOrgID, Kind: domain.SellerKindSeller}, *p.SellerOrgID
			}
			for _, l := range p.Lines {
				cp.Lines = append(cp.Lines, domain.CartLine{LineID: l.LineID, VariantATIN: l.ATIN, ATIN: l.ATIN, OfferID: l.OfferID, ProductType: l.ProductType, Quantity: l.Quantity,
					PriceMinor: l.UnitPriceMinor, SellerBehaviour: l.SellerBehaviour})
				outcome[l.LineID] = l.Outcome
				if l.DiscountMinor > 0 {
					sum.Coupon.OfferDiscounts = append(sum.Coupon.OfferDiscounts, domain.CheckoutCouponOfferDiscount{OfferID: l.OfferID, DiscountMinor: l.DiscountMinor})
				}
			}
			sum.Packages = append(sum.Packages, domain.CheckoutPackage{CartPackage: cp, MembershipWaivedMinor: p.MembershipWaivedMinor})
		}
		f := &fakeReturns{answer: func(l port.UpTaxQuoteLine) port.UpTaxLineQuote {
			a := port.UpTaxLineQuote{LineID: l.LineID, DecisionStatus: "RESOLVED", Outcome: outcome[l.LineID], Rate: float64(c.RateBps) / 10000}
			if a.Outcome == "TAXABLE" {
				a.TaxMinor = ((l.UnitPriceMinor*int64(l.Quantity)-l.DiscountMinor+l.ShippingMinor)*c.RateBps + 5000) / 10000
			}
			return a
		}}
		s := &checkoutPaymentServiceImpl{d: ShopperDeps{Returns: f, ShippingAllocation: method}}
		addr := &port.IdentityAddress{Country: c.ShipTo.Country, State: c.ShipTo.State, PostalCode: c.ShipTo.PostalCode, City: c.ShipTo.City}
		s.priceTax(context.Background(), &sum, addr, nil)

		if sum.TaxEstimateStatus != domain.TaxEstimated || sum.TaxMinor != c.Expected.TaxMinor {
			t.Errorf("%s: estimate %s %d, want %d", c.Name, sum.TaxEstimateStatus, sum.TaxMinor, c.Expected.TaxMinor)
		}
		if f.req.ShipTo != c.ShipTo || f.req.Currency != c.Currency {
			t.Errorf("%s: ship-to %+v currency %s", c.Name, f.req.ShipTo, f.req.Currency)
		}
		var sent []map[string]any
		body, _ := json.Marshal(f.req.Lines)
		_ = json.Unmarshal(body, &sent)
		if !reflect.DeepEqual(sent, c.Expected.RequestLines) {
			t.Errorf("%s: the quote request differs from the vector\n got %v\nwant %v", c.Name, sent, c.Expected.RequestLines)
		}
	}
}
