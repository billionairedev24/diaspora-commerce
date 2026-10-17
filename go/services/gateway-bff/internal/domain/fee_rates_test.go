package domain

import (
	"testing"
	"time"
)

func TestAdvertisedFeesMatchThePlanFeatureText(t *testing.T) {
	cases := map[string]FeeRange{
		"15% referral fee":                {1500, 1500},
		"8–12% referral fee by category":  {800, 1200},
		"6-10% referral fee":              {600, 1000},
		"10% booking fee":                 {1000, 1000},
		"7.5% connection fee on bookings": {750, 750},
	}
	for text, want := range cases {
		got, r := AdvertisedFee([]string{"Up to 50 listings", text})
		if got != text || r == nil || *r != want {
			t.Errorf("%q → %q %v, want %v", text, got, r, want)
		}
	}
	if text, r := AdvertisedFee([]string{"Unlimited listings", "3 users"}); text != "" || r != nil {
		t.Errorf("no fee feature → %q %v", text, r)
	}
	if (FeeRange{800, 1200}).Text() != "8–12%" || (FeeRange{750, 750}).Text() != "7.5%" {
		t.Error("range text")
	}
}

func TestPlanFeeWarningsOnlyWhenTheConfiguredRatesDiffer(t *testing.T) {
	adv := &FeeRange{800, 1200}
	rates := []FeePlanRate{{PlanCode: "seller_growth", Category: "groceries", Bps: 800}, {PlanCode: "seller_growth", Category: "*", Bps: 1000},
		{PlanCode: "seller_growth", Category: "fashion", Bps: 1200}, {PlanCode: "seller_pro", Category: "*", Bps: 800}}
	if w := PlanFeeWarning("Seller Growth", adv, PlanRange(rates, "seller_growth")); w != "" {
		t.Errorf("matching rates warned: %s", w)
	}
	if w := PlanFeeWarning("Seller Pro", &FeeRange{600, 1000}, PlanRange(rates, "seller_pro")); w != "Seller Pro advertises 6–10% but the fee schedule charges 8%." {
		t.Errorf("warning = %q", w)
	}
	if w := PlanFeeWarning("Seller Essentials", &FeeRange{1500, 1500}, nil); w == "" {
		t.Error("a plan without rates should warn")
	}
	if PlanFeeWarning("Free", nil, nil) != "" {
		t.Error("a plan advertising no fee never warns")
	}
}

func TestTakeHomeUsesTheConnectionFeeAndCardFeesOnBothCharges(t *testing.T) {
	th := TakeHomeOf(12_000, 3_000, 800, 290, 30, RateSourcePlan, "")
	// 8 % of 120.00 = 9.60; card 2.9 % + 30¢ on 30.00 (1.17) and on 90.00 (2.91)
	if th.ConnectionFeeMinor != 960 || th.CardFeesMinor != 117+291 || th.KeepMinor != 12_000-960-408 || th.Currency != Currency {
		t.Fatalf("take-home = %+v", th)
	}
	if noDeposit := TakeHomeOf(5_000, 0, 1000, 290, 30, RateSourceDefault, "USD"); noDeposit.CardFeesMinor != 175 {
		t.Fatalf("no deposit = %+v", noDeposit)
	}
}

func TestNegotiatedRateDraftsNeedAReasonAndNeverRepriceThePast(t *testing.T) {
	now := time.Date(2026, 10, 12, 12, 0, 0, 0, time.UTC)
	bps := 600
	ok := NegotiatedRateDraft{OrgID: "org-1", Type: NegotiatedCommission, RateBps: &bps, Reason: "Wholesale partner"}
	if e := ValidateNegotiatedRateDraft(ok, now, "org-retail", DefaultFeeLimits); len(e) != 0 {
		t.Fatalf("valid draft: %v", e)
	}
	past, end := now.Add(-time.Hour), now.Add(-time.Minute)
	bad := NegotiatedRateDraft{OrgID: "org-retail", Type: "RENT", StartsAt: &past, EndsAt: &end, Reason: " "}
	e := ValidateNegotiatedRateDraft(bad, now, "org-retail", DefaultFeeLimits)
	for _, k := range []string{"orgId", "type", "rateBps", "startsAt", "endsAt", "reason"} {
		if e[k] == "" {
			t.Errorf("missing error %s: %v", k, e)
		}
	}
	if KindOfNegotiated(NegotiatedConnectionFee) != FeeKindService || KindOfNegotiated(NegotiatedCommission) != FeeKindProduct {
		t.Error("kind of negotiated")
	}
}

func TestRateSectionsValidateAndShape(t *testing.T) {
	bps, big := 800, 9000
	d := FeeScheduleDraft{PlanRates: []FeeDraftPlanRate{{PlanCode: "seller_pro", Category: "*", Bps: &bps}, {PlanCode: "seller_pro", Category: "", Bps: &bps}},
		ConnectionFee: &FeeConnectionDraft{DefaultBps: &big, CategoryRates: []FeeDraftRate{{Category: "", Bps: &bps}}}}
	e := ValidateRateSections(d, DefaultFeeLimits)
	for _, k := range []string{"planRates.1.category", "connectionFee.defaultBps", "connectionFee.categoryRates.0.category"} {
		if e[k] == "" {
			t.Errorf("missing error %s: %v", k, e)
		}
	}
	body := PlanBpsBody([]FeeDraftPlanRate{{PlanCode: " Seller_Pro ", Category: "Fashion", Bps: &bps}})
	if body["seller_pro"]["fashion"] != 800 {
		t.Fatalf("body = %v", body)
	}
	rows := PlanRatesOf(map[string]map[string]int{"seller_pro": {"fashion": 1000, "*": 800}}, FeeCategoryLabel)
	if len(rows) != 2 || rows[0].Category != "*" || rows[0].Label != "Other categories" || rows[1].PlanName != "Seller Pro" {
		t.Fatalf("rows = %v", rows)
	}
	if NegotiatedPlanCode("seller_growth", "org_Kente-1") != "seller_growth_org_kente_1" {
		t.Errorf("code = %s", NegotiatedPlanCode("seller_growth", "org_Kente-1"))
	}
	until := time.Date(2026, 12, 31, 0, 0, 0, 0, time.UTC)
	if RateExplanation(FeeKindService, RateSourceOverride, nil, &until) != "Negotiated connection fee until 31 Dec 2026" {
		t.Error("explanation")
	}
}

func TestPlanFbaBodyAndValidation(t *testing.T) {
	two, big := int64(200), int64(DefaultFeeLimits.FbaPerUnitMaxMinor+1)
	rows := []FeeDraftPlanFba{{PlanCode: " Seller_Pro ", FeeMinor: &two}}
	if body := PlanFbaBody(rows); body["seller_pro"] != 200 {
		t.Fatalf("body = %v", body)
	}
	if e := ValidatePlanFba(rows, DefaultFeeLimits); len(e) != 0 {
		t.Fatalf("errors = %v", e)
	}
	e := ValidatePlanFba([]FeeDraftPlanFba{{PlanCode: "seller_pro", FeeMinor: &two}, {PlanCode: "seller_pro", FeeMinor: &big}, {PlanCode: "", FeeMinor: nil}}, DefaultFeeLimits)
	for _, k := range []string{"planFba.1.planCode", "planFba.1.feeMinor", "planFba.2.planCode", "planFba.2.feeMinor"} {
		if e[k] == "" {
			t.Errorf("missing error %s in %v", k, e)
		}
	}
}
