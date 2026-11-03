package domain

import (
	"testing"
	"time"
)

func TestQuoteTariffFollowsPayments(t *testing.T) {
	seedCustomer := &DeliveryTariff{Mode: TariffDistance, BaseMinor: i64(499), PerMileMinor: i64(85), MinMinor: i64(599), MaxMinor: i64(2499)}
	cases := []struct {
		name     string
		t        *DeliveryTariff
		miles    float64
		packages int
		want     int64
	}{
		{"README example: 499 + 85 × 7.4", seedCustomer, 7.4, 1, 1128},
		{"raised to the minimum", seedCustomer, 0.5, 1, 599},
		{"capped at the maximum", seedCustomer, 40, 1, 2499},
		{"per trip: packages don't count", seedCustomer, 7.4, 3, 1128},
		{"half up", &DeliveryTariff{Mode: TariffDistance, PerMileMinor: i64(85)}, 0.3, 1, 26},
		{"flat per package", &DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(200)}, 12, 3, 600},
		{"per weight: 399 + 50 * 4 lbs", &DeliveryTariff{Mode: TariffPerWeight, BaseMinor: i64(399), PerLbMinor: i64(50)}, 5, 1, 599},
		{"band covers the miles (to exclusive)", &DeliveryTariff{Mode: TariffDistance, BaseMinor: i64(999), PerMileMinor: i64(100),
			Bands: []DeliveryBand{{0, 3, 399}, {3, 6, 599}}}, 3, 1, 599},
		{"beyond the bands: base + per mile", &DeliveryTariff{Mode: TariffDistance, BaseMinor: i64(999), PerMileMinor: i64(100),
			Bands: []DeliveryBand{{0, 3, 399}}}, 4, 1, 1399},
		{"no tariff is free", nil, 9, 1, 0},
	}
	for _, c := range cases {
		var got int64
		if c.name == "per weight: 399 + 50 * 4 lbs" {
			got = QuoteTariff(c.t, c.miles, c.packages, 4.0)
		} else {
			got = QuoteTariff(c.t, c.miles, c.packages)
		}
		if got != c.want {
			t.Errorf("%s: got %d, want %d", c.name, got, c.want)
		}
	}
	if s := TariffSummary(seedCustomer); s != "$4.99 + $0.85/mi, at least $5.99, at most $24.99" {
		t.Errorf("summary = %q", s)
	}
	if s := TariffSummary(&DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(200)}); s != "$2.00 per package" {
		t.Errorf("flat summary = %q", s)
	}
	if s := TariffSummary(&DeliveryTariff{Mode: TariffPerWeight, BaseMinor: i64(399), PerLbMinor: i64(50), MinMinor: i64(499)}); s != "$3.99 + $0.50/lb, at least $4.99" {
		t.Errorf("per weight summary = %q", s)
	}
}

func TestValidateDeliveryDraft(t *testing.T) {
	radius := 25.0
	ok := &DeliveryPricingDraft{Enabled: true, MaxRadiusMiles: &radius,
		CustomerCharge:  &DeliveryTariff{Mode: TariffDistance, BaseMinor: i64(499), PerMileMinor: i64(85), Bands: []DeliveryBand{{0, 3, 399}, {3, 6, 599}}},
		SellerFee:       &DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(200)},
		SellerOverrides: []DeliveryDraftOverride{{OrgID: "org-1", SellerFee: &DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(100)}}}}
	if errs := ValidateDeliveryDraft(ok, "org-retail", DefaultFeeLimits); len(errs) != 0 {
		t.Fatalf("valid draft: %v", errs)
	}
	if errs := ValidateDeliveryDraft(nil, "org-retail", DefaultFeeLimits); len(errs) != 0 {
		t.Fatal("an omitted section is carried over, never an error")
	}
	far := 201.0
	bad := &DeliveryPricingDraft{MaxRadiusMiles: &far,
		CustomerCharge: &DeliveryTariff{Mode: TariffDistance, PerMileMinor: i64(85), MinMinor: i64(900), MaxMinor: i64(500),
			Bands: []DeliveryBand{{0, 5, 399}, {4, 3, 599}}},
		SellerFee: &DeliveryTariff{Mode: "PER_KG"},
		SellerOverrides: []DeliveryDraftOverride{{OrgID: "org-retail", SellerFee: &DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(0)}},
			{OrgID: "org-1"}, {OrgID: "org-1", SellerFee: &DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(20_000)}}}}
	errs := ValidateDeliveryDraft(bad, "org-retail", DefaultFeeLimits)
	for _, k := range []string{"deliveryPricing.maxRadiusMiles", "deliveryPricing.customerCharge.baseMinor", "deliveryPricing.customerCharge.maxMinor",
		"deliveryPricing.customerCharge.bands.1.toMiles", "deliveryPricing.sellerFee.mode", "deliveryPricing.sellerOverrides.0.orgId",
		"deliveryPricing.sellerOverrides.1.sellerFee", "deliveryPricing.sellerOverrides.2.orgId", "deliveryPricing.sellerOverrides.2.sellerFee.flatPerPackageMinor"} {
		if errs[k] == "" {
			t.Errorf("missing %s: %v", k, errs)
		}
	}
	overlap := &DeliveryPricingDraft{MaxRadiusMiles: &radius, CustomerCharge: &DeliveryTariff{Mode: TariffDistance, BaseMinor: i64(0), PerMileMinor: i64(0),
		Bands: []DeliveryBand{{3, 6, 599}, {0, 4, 399}}}}
	if e := ValidateDeliveryDraft(overlap, "", DefaultFeeLimits); e["deliveryPricing.customerCharge.bands.0.fromMiles"] == "" {
		t.Fatalf("overlap (any order) = %v", e)
	}
}

func TestSellerDeliveryFeeFor(t *testing.T) {
	p := &DeliveryPricing{Enabled: true, MaxRadiusMiles: 25, SellerFee: &DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(200)},
		SellerOverrides: []DeliveryFeeOverride{{OrgID: "org-1", SellerFee: DeliveryTariff{Mode: TariffFlat, FlatPerPackageMinor: i64(150)}}}}
	if f := SellerDeliveryFeeFor(p, "org-2", false); f.Source != "DEFAULT" || f.Summary != "$2.00 per package" || f.Tariff.Bands == nil {
		t.Fatalf("default = %+v", f)
	}
	if f := SellerDeliveryFeeFor(p, "org-1", false); f.Source != "OVERRIDE" || f.Summary != "$1.50 per package" {
		t.Fatalf("override = %+v", f)
	}
	if f := SellerDeliveryFeeFor(p, "org-1", true); f.Source != "RETAIL" || f.Tariff != nil {
		t.Fatalf("retail = %+v", f)
	}
	if SellerDeliveryFeeFor(nil, "org-1", false) != nil {
		t.Fatal("no section, no fee")
	}
}

func TestPickupHours(t *testing.T) {
	ok := AfrotransactPickupRequest{Enabled: true, Hours: []PickupWindow{{"MONDAY", "09:00", "12:00"}, {"MONDAY", "12:00", "17:00"}, {"SUNDAY", "10:00", "14:00"}}}
	if e := ok.Validate(); len(e) != 0 {
		t.Fatalf("adjacent windows are fine: %v", e)
	}
	if e := (AfrotransactPickupRequest{Enabled: false}).Validate(); len(e) != 0 {
		t.Fatalf("disabling needs no hours: %v", e)
	}
	bad := AfrotransactPickupRequest{Enabled: true, Hours: []PickupWindow{{"FUNDAY", "9:00", "12:00"}, {"MONDAY", "12:00", "11:00"},
		{"TUESDAY", "09:00", "12:00"}, {"TUESDAY", "11:30", "13:00"}}}
	e := bad.Validate()
	for _, k := range []string{"hours.0.day", "hours.0.opens", "hours.1.closes", "hours.3.opens"} {
		if e[k] == "" {
			t.Errorf("missing %s: %v", k, e)
		}
	}
	if e := (AfrotransactPickupRequest{Enabled: true}).Validate(); e["hours"] == "" {
		t.Fatal("enabling needs a window")
	}
}

func TestEstimatorLocalDelivery(t *testing.T) {
	e := NewEstimator(DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 4900})
	now := time.Date(2026, 10, 9, 9, 0, 0, 0, time.UTC)
	p := e.Promise(AfroTransactDelivery, 0, now)
	if p.Days != 1 || p.Prefix != "AfroTransact Delivery" || p.FeeMinor != 0 || p.Free {
		t.Fatalf("promise = %+v", p)
	}
	opts := e.Options(AfroTransactDelivery, 0, 1000, now)
	if len(opts) != 1 || opts[0].Key != "afrotransact_delivery" || opts[0].Label != LocalDeliveryOptionLabel {
		t.Fatalf("options = %+v", opts)
	}
	if e.Nudge(AfroTransactDelivery, 1000) != nil {
		t.Fatal("no free-shipping nudge: the delivery is priced by distance")
	}
	if LocalDeliveryHeading("Houston, TX") != "AfroTransact Delivery from Houston, TX" {
		t.Fatal("heading")
	}
}
