package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ADR 0005 §3: the carrier estimate follows the staff-managed carrier rule once it is read, and the flat configured
// policy applies until then (and again when the rule is removed).
func TestEstimatorFollowsTheStaffCarrierRule(t *testing.T) {
	e := domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 4900})
	setter, ok := e.(domain.CarrierRuleSetter)
	if !ok {
		t.Fatal("the default estimator does not take a carrier rule")
	}
	now := time.Date(2026, 10, 24, 12, 0, 0, 0, time.UTC)

	flat := e.Options(domain.ShipsFromSeller, 4, 2000, now)[0]
	if flat.FeeMinor != 599 || flat.Free || flat.FeeRule != nil {
		t.Fatalf("flat policy before a rule: %+v", flat)
	}

	setter.SetCarrierRule(&domain.CarrierRule{RuleID: "r-1", Version: 4, FallbackMinor: 1350, FreeAboveMinor: 7500})
	paid := e.Options(domain.ShipsFromSeller, 4, 2000, now)[0]
	if paid.FeeMinor != 1350 || paid.Free || paid.FeeRule == nil || paid.FeeRule.Method != "CARRIER" ||
		paid.FeeRule.RuleVersion != 4 || paid.FeeRule.RuleID != "r-1" || paid.FeeRule.AmountMinor != 1350 ||
		paid.FeeRule.Source != "CARRIER_FALLBACK" {
		t.Fatalf("rule fee: %+v", paid)
	}
	if got := e.Promise(domain.ShipsFromSeller, 4, now); got.FeeMinor != 1350 {
		t.Fatalf("promise fee: %+v", got)
	}
	free := e.Options(domain.ShipsFromSeller, 4, 7500, now)[0]
	if !free.Free || free.FeeMinor != 0 || free.FeeRule == nil || free.FeeRule.AmountMinor != 0 || free.FeeRule.Source != "RULE" {
		t.Fatalf("free over the rule threshold: %+v", free)
	}
	if n := e.Nudge(domain.ShipsFromSeller, 5000); n == nil || n.ThresholdMinor != 7500 || n.RemainingMinor != 2500 {
		t.Fatalf("nudge follows the rule threshold: %+v", n)
	}

	atFree := e.Options(domain.FulfilledByAfroTransact, 2, 100, now)
	if atFree[0].FeeRule != nil || !atFree[0].Free {
		t.Fatalf("AfroTransact-fulfilled shipping is free and has no carrier rule: %+v", atFree[0])
	}

	setter.SetCarrierRule(&domain.CarrierRule{RuleID: "r-1", Version: 5, FallbackMinor: 500})
	if never := e.Options(domain.ShipsFromSeller, 4, 100000, now)[0]; never.Free || never.FeeMinor != 500 {
		t.Fatalf("a rule without a threshold is never free: %+v", never)
	}

	setter.SetCarrierRule(nil)
	if back := e.Options(domain.ShipsFromSeller, 4, 2000, now)[0]; back.FeeMinor != 599 || back.FeeRule != nil {
		t.Fatalf("flat policy after the rule is removed: %+v", back)
	}
}
