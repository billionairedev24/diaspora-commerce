package domain_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Seller carrier shipping is retired with seller self-shipping: the staff carrier rule (ADR 0005 §3) and the old switch no
// longer create any option. A seller's own package has no delivery; Fulfilled by AfroTransact is the flat fee, paid by the
// customer, whatever the rule says.
func TestTheRetiredCarrierRuleChangesNoOption(t *testing.T) {
	e := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 4900})
	e.(domain.ShippingSettingsSetter).SetShippingSettings(&domain.ShippingSettings{Currency: "USD", Version: 1, FlatFeeMinor: 599, SellerCarrierShippingEnabled: true})
	setter, ok := e.(domain.CarrierRuleSetter)
	if !ok {
		t.Fatal("the default estimator does not take a carrier rule")
	}
	setter.SetCarrierRule(&domain.CarrierRule{RuleID: "r-1", Version: 4, FallbackMinor: 1350, FreeAboveMinor: 7500})

	if got := e.Options(domain.ShipsFromSeller, 2000); len(got) != 0 {
		t.Fatalf("a seller's own package offers no delivery: %+v", got)
	}
	atFlat := e.Options(domain.FulfilledByAfroTransact, 100)
	if len(atFlat) != 1 || atFlat[0].FeeRule != nil || atFlat[0].FeeMinor != 599 || atFlat[0].Key != "afrotransact_delivery" {
		t.Fatalf("AfroTransact-fulfilled delivery is the flat fee and has no carrier rule: %+v", atFlat)
	}
	store := e.Options(domain.AfroTransactDelivery, 100)
	if len(store) != 1 || store[0].FeeRule != nil || store[0].FeeMinor != 599 {
		t.Fatalf("Fulfilled by AfroTransact from the seller's store is the flat fee too: %+v", store)
	}
}
