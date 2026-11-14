package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// emptyCheckout is the cart-side summary of a cart whose items are all unticked: no packages.
type emptyCheckout struct{}

func (emptyCheckout) Summary(context.Context, *domain.Caller, string) (domain.CheckoutSummary, error) {
	return domain.CheckoutSummary{Currency: "USD", Packages: []domain.CheckoutPackage{}, TaxRateLabel: taxLabelNeedsAddress, TaxEstimateStatus: domain.TaxNeedsAddress}, nil
}

func (emptyCheckout) PlaceOrder(context.Context, *domain.Caller, string, domain.PlaceOrderRequest) (port.PlaceOrderResult, error) {
	return port.PlaceOrderResult{}, nil
}

// With nothing selected the summary used to keep the default "enter your ZIP code" tax state, so the page asked for a
// ZIP code for an order with no items. It now says there are no items and cannot be placed.
func TestASummaryWithNothingSelectedSaysNoItemsNotNeedsZip(t *testing.T) {
	s := &checkoutPaymentServiceImpl{d: ShopperDeps{Checkout: emptyCheckout{}, PublishableKey: "pk_test_x"}}
	sum, err := s.summary(context.Background(), &domain.Caller{}, "", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(sum.Packages) != 0 || sum.CanPlaceOrder || sum.TaxEstimateStatus != domain.TaxUnavailable || sum.TaxUnavailableReason != taxReasonNoItems ||
		sum.TaxRateLabel != taxLabelNoItems || sum.PublishableKey != "pk_test_x" {
		t.Fatalf("%+v", sum)
	}
}
