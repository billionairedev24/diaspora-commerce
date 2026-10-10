package domain

import (
	"testing"
	"time"
)

func ip(n int) *int             { return &n }
func i64(n int64) *int64        { return &n }
func sp(s string) *string       { return &s }
func tp(t time.Time) *time.Time { return &t }

func validDraft(at time.Time) FeeScheduleDraft {
	return FeeScheduleDraft{EffectiveFrom: tp(at), DefaultCommissionBps: ip(1000), CardPctBps: ip(290), CardFixedMinor: i64(30), FbaPerUnitMinor: i64(325),
		Note: "Fashion to 12.5 %", CategoryRates: []FeeDraftCategoryRate{{Category: "Fashion", CommissionBps: ip(1250)}},
		Overrides: []FeeDraftOverride{{OrgID: "org-1", CommissionBps: ip(600)}, {OrgID: "org-1", Category: sp("groceries"), CommissionBps: ip(500)}}}
}

func TestValidateFeeDraft(t *testing.T) {
	now := time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)
	rules := FeeDraftRules{Now: now, MinEffectiveFrom: now, ImmediateAllowed: true, RetailOrgID: "org-retail", Limits: DefaultFeeLimits}
	if errs := ValidateFeeDraft(validDraft(now.Add(time.Hour)), rules); len(errs) != 0 {
		t.Fatalf("valid draft: %v", errs)
	}
	cases := map[string]func(d *FeeScheduleDraft){
		"effectiveFrom":        func(d *FeeScheduleDraft) { d.EffectiveFrom = tp(now) },
		"defaultCommissionBps": func(d *FeeScheduleDraft) { d.DefaultCommissionBps = ip(5001) },
		"cardPctBps":           func(d *FeeScheduleDraft) { d.CardPctBps = nil },
		"cardFixedMinor":       func(d *FeeScheduleDraft) { d.CardFixedMinor = i64(-1) },
		"fbaPerUnitMinor":      func(d *FeeScheduleDraft) { d.FbaPerUnitMinor = i64(10_001) },
		"note":                 func(d *FeeScheduleDraft) { d.Note = " a " },
		"categoryRates.1.category": func(d *FeeScheduleDraft) {
			d.CategoryRates = append(d.CategoryRates, FeeDraftCategoryRate{Category: " FASHION", CommissionBps: ip(1)})
		},
		"categoryRates.0.commissionBps": func(d *FeeScheduleDraft) { d.CategoryRates[0].CommissionBps = ip(-5) },
		"overrides.2.orgId": func(d *FeeScheduleDraft) {
			d.Overrides = append(d.Overrides, FeeDraftOverride{OrgID: "org-retail", CommissionBps: ip(0)})
		},
		"overrides.2.category": func(d *FeeScheduleDraft) {
			d.Overrides = append(d.Overrides, FeeDraftOverride{OrgID: "org-1", Category: sp("*"), CommissionBps: ip(0)})
		},
		"immediate": func(d *FeeScheduleDraft) {
			d.EffectiveFrom, d.Immediate = nil, true
		},
	}
	for key, mutate := range cases {
		d := validDraft(now.Add(time.Hour))
		mutate(&d)
		r := rules
		if key == "immediate" {
			r.ImmediateAllowed = false
		}
		if errs := ValidateFeeDraft(d, r); errs[key] == "" {
			t.Errorf("%s: no error in %v", key, errs)
		}
	}
	d := validDraft(now.Add(time.Hour))
	r := rules
	r.MinEffectiveFrom = now.Add(48 * time.Hour)
	if errs := ValidateFeeDraft(d, r); errs["effectiveFrom"] == "" {
		t.Fatalf("before the scheduled version: %v", errs)
	}
	d.EffectiveFrom, d.Immediate = nil, true
	if errs := ValidateFeeDraft(d, rules); len(errs) != 0 {
		t.Fatalf("immediate: %v", errs)
	}
}

func TestFeeFormattingAndChanges(t *testing.T) {
	if FormatBps(1250) != "12.50%" || FormatBps(5) != "0.05%" || FormatMinor(325) != "$3.25" || FeeCategoryLabel("home & art") != "Home & art" {
		t.Fatal("formatting")
	}
	cur := SellerFeeVersion{DefaultCommissionBps: 1000, CardPctBps: 290, CardFixedMinor: 30, FbaPerUnitMinor: 325, ReverseCommissionOnRefund: true,
		Categories: []SellerFeeCategory{{Category: "fashion", Label: "Fashion", CommissionBps: 1200}, {Category: "groceries", Label: "Groceries", CommissionBps: 600}}}
	next := cur
	next.Categories = []SellerFeeCategory{{Category: "fashion", Label: "Fashion", CommissionBps: 1300}, {Category: "groceries", Label: "Groceries", CommissionBps: 600}}
	next.ReverseCommissionOnRefund = false
	got := SellerFeeChanges(cur, &next)
	if len(got) != 2 || got[0].Field != "category:fashion" || got[0].From != "12.00%" || got[0].To != "13.00%" || got[1].Field != "reverseCommissionOnRefund" {
		t.Fatalf("changes = %+v", got)
	}
	if len(SellerFeeChanges(cur, nil)) != 0 {
		t.Fatal("no upcoming version, no changes")
	}
	if FeeStatusOf(false, time.Now().Add(time.Hour), false, time.Now()) != FeeStatusScheduled || FeeStatusOf(true, time.Now(), true, time.Now()) != FeeStatusCancelled {
		t.Fatal("status")
	}
}
