package domain

import (
	"fmt"
	"slices"
	"strings"
	"time"
	"unicode"
)

// Fees & commissions: the staff fee-schedule editor (contracts/openapi/gateway-bff-staff.yaml, tag Fees) and the
// Seller Center's read-only Fees page (gateway-bff-seller.yaml). Rates travel in basis points (1250 = 12.50 %);
// the screens show and take percentages with two decimals and convert.

// Fee schedule statuses (derived by the BFF from payments' history and the current time).
const (
	FeeStatusActive     = "ACTIVE"
	FeeStatusScheduled  = "SCHEDULED"
	FeeStatusSuperseded = "SUPERSEDED"
	FeeStatusCancelled  = "CANCELLED"
)

// RetailFeesNote is how AfroTransact Retail appears wherever fees are configured or shown.
const RetailFeesNote = "No commission, no fees (merchant of record)"

// AllCategories is payments' override category meaning "every category".
const AllCategories = "*"

// FeeLimits are the sensible bounds the editor enforces (payments itself accepts 0–100 %).
type FeeLimits struct {
	CommissionMaxBps   int   `json:"commissionMaxBps"`
	CardPctMaxBps      int   `json:"cardPctMaxBps"`
	CardFixedMaxMinor  int64 `json:"cardFixedMaxMinor"`
	FbaPerUnitMaxMinor int64 `json:"fbaPerUnitMaxMinor"`
	NoteMinLength      int   `json:"noteMinLength"`
	NoteMaxLength      int   `json:"noteMaxLength"`
	MaxCategoryRates   int   `json:"maxCategoryRates"`
	MaxOverrides       int   `json:"maxOverrides"`
	// AfroTransact Delivery pricing (S255): payments accepts a radius up to 200 miles.
	DeliveryMaxRadiusMiles float64 `json:"deliveryMaxRadiusMiles"`
	DeliveryAmountMaxMinor int64   `json:"deliveryAmountMaxMinor"`
	DeliveryMaxBands       int     `json:"deliveryMaxBands"`
	MaxDeliveryOverrides   int     `json:"maxDeliveryOverrides"`
}

// DefaultFeeLimits allow commission 0–50 %, a card fee of 0–10 % + up to $10.00, an FBA fee up to $100.00 per unit and
// AfroTransact Delivery amounts up to $100.00 within a radius of up to 200 miles.
var DefaultFeeLimits = FeeLimits{CommissionMaxBps: 5000, CardPctMaxBps: 1000, CardFixedMaxMinor: 1000, FbaPerUnitMaxMinor: 10_000,
	NoteMinLength: 3, NoteMaxLength: 500, MaxCategoryRates: 100, MaxOverrides: 500,
	DeliveryMaxRadiusMiles: 200, DeliveryAmountMaxMinor: 10_000, DeliveryMaxBands: 20, MaxDeliveryOverrides: 500}

// FeeCategory is a commission category the editor offers (catalog departments, plus the ones schedules already use).
type FeeCategory struct {
	Key    string `json:"key"`
	Label  string `json:"label"`
	Source string `json:"source"` // TAXONOMY, SCHEDULE or PLATFORM
}

// FeeCategoryRate is one row of the per-category commission table.
type FeeCategoryRate struct {
	Category      string `json:"category"`
	Label         string `json:"label"`
	CommissionBps int    `json:"commissionBps"`
}

// FeeOverride is a negotiated rate for one seller organisation (one category, or every category when Category is nil).
type FeeOverride struct {
	OrgID         string  `json:"orgId"`
	OrgName       *string `json:"orgName"`
	Category      *string `json:"category"`
	CategoryLabel string  `json:"categoryLabel"`
	CommissionBps int     `json:"commissionBps"`
}

// FeeScheduleView is one fee-schedule version as the console shows it.
type FeeScheduleView struct {
	ID                        string            `json:"id"`
	Version                   int               `json:"version"`
	Status                    string            `json:"status"`
	EffectiveFrom             time.Time         `json:"effectiveFrom"`
	Currency                  string            `json:"currency"`
	DefaultCommissionBps      int               `json:"defaultCommissionBps"`
	CategoryRates             []FeeCategoryRate `json:"categoryRates"`
	Overrides                 []FeeOverride     `json:"overrides"`
	CardPctBps                int               `json:"cardPctBps"`
	CardFixedMinor            int64             `json:"cardFixedMinor"`
	FbaPerUnitMinor           int64             `json:"fbaPerUnitMinor"`
	ReverseCommissionOnRefund bool              `json:"reverseCommissionOnRefund"`
	DeliveryPricing           *DeliveryPricing  `json:"deliveryPricing"`
	// PlanRates are the product commission per plan; ConnectionFee the service connection-fee section (nil for
	// versions created before connection fees existed).
	PlanRates     []FeePlanRate     `json:"planRates"`
	ConnectionFee *FeeConnectionFee `json:"connectionFee"`
	Note          *string           `json:"note"`
	CreatedBy     string            `json:"createdBy"`
	CreatedAt     *time.Time        `json:"createdAt"`
	CancelledAt   *time.Time        `json:"cancelledAt"`
	CancelledBy   *string           `json:"cancelledBy"`
	CancelReason  *string           `json:"cancelReason"`
	Cancellable   bool              `json:"cancellable"`
}

// FeeRetail describes AfroTransact Retail (merchant of record): never charged commission or fees, never overridden.
type FeeRetail struct {
	OrgID string `json:"orgId"`
	Name  string `json:"name"`
	Note  string `json:"note"`
}

// FeesOverview is GET /v1/staff/fees.
type FeesOverview struct {
	GeneratedAt time.Time `json:"generatedAt"`
	Currency    string    `json:"currency"`
	// CanConfigure: payments:configure plus EDIT on the fees module (create and cancel versions).
	CanConfigure bool `json:"canConfigure"`
	// Current is the version in force now; Base is what "New version" starts from (the latest scheduled version, else Current).
	Current *FeeScheduleView `json:"current"`
	Base    *FeeScheduleView `json:"base"`
	// MinEffectiveFrom is the earliest time a new version may take effect (now, or the latest scheduled version's date).
	MinEffectiveFrom    time.Time         `json:"minEffectiveFrom"`
	ImmediateAllowed    bool              `json:"immediateAllowed"`
	Schedules           []FeeScheduleView `json:"schedules"`
	Categories          []FeeCategory     `json:"categories"`
	TaxonomyAvailable   bool              `json:"taxonomyAvailable"`
	OrgNamesAvailable   bool              `json:"orgNamesAvailable"`
	Retail              FeeRetail         `json:"retail"`
	Limits              FeeLimits         `json:"limits"`
	ExampleSaleMinor    int64             `json:"exampleSaleMinor"`
	ReverseOnRefundHelp string            `json:"reverseOnRefundHelp"`
	// Plans of the catalogue (seller service) for the plan-rate matrix, with a warning where the feature text and the
	// configured rate differ; PlansAvailable false when the catalogue could not be read.
	Plans          []FeePlan `json:"plans"`
	PlansAvailable bool      `json:"plansAvailable"`
	PlanWarnings   []string  `json:"planWarnings"`
	// ServiceCategories are the service categories the connection-fee section prices (plus "services").
	ServiceCategories []FeeCategory `json:"serviceCategories"`
}

// FeeOrgHit is a seller or provider organisation found for an override.
type FeeOrgHit struct {
	OrgID  string `json:"orgId"`
	Name   string `json:"name"`
	Kind   string `json:"kind"`
	Status string `json:"status"`
	Retail bool   `json:"retail"`
	// Selectable is false for AfroTransact Retail (no overrides: it pays no commission).
	Selectable bool    `json:"selectable"`
	Reason     *string `json:"reason"`
}

// FeeOrgSearch is GET /v1/staff/fees/orgs.
type FeeOrgSearch struct {
	Items []FeeOrgHit `json:"items"`
}

// FeeScheduleDraft is POST /v1/staff/fees/schedules (rates in basis points).
type FeeScheduleDraft struct {
	EffectiveFrom             *time.Time             `json:"effectiveFrom"`
	Immediate                 bool                   `json:"immediate"`
	DefaultCommissionBps      *int                   `json:"defaultCommissionBps"`
	CategoryRates             []FeeDraftCategoryRate `json:"categoryRates"`
	Overrides                 []FeeDraftOverride     `json:"overrides"`
	CardPctBps                *int                   `json:"cardPctBps"`
	CardFixedMinor            *int64                 `json:"cardFixedMinor"`
	FbaPerUnitMinor           *int64                 `json:"fbaPerUnitMinor"`
	ReverseCommissionOnRefund bool                   `json:"reverseCommissionOnRefund"`
	// DeliveryPricing nil = carried over from the latest live version (payments' rule).
	DeliveryPricing *DeliveryPricingDraft `json:"deliveryPricing"`
	// PlanRates nil = carried over from the latest live version; [] = no plan rates.
	PlanRates []FeeDraftPlanRate `json:"planRates"`
	// ConnectionFee nil = carried over from the latest live version.
	ConnectionFee *FeeConnectionDraft `json:"connectionFee"`
	Note          string              `json:"note"`
}

// FeeDraftCategoryRate is one category row of a draft.
type FeeDraftCategoryRate struct {
	Category      string `json:"category"`
	CommissionBps *int   `json:"commissionBps"`
}

// FeeDraftOverride is one seller override of a draft (Category empty or nil = every category).
type FeeDraftOverride struct {
	OrgID         string  `json:"orgId"`
	Category      *string `json:"category"`
	CommissionBps *int    `json:"commissionBps"`
}

// FeeCancelRequest is POST /v1/staff/fees/schedules/{id}/cancel.
type FeeCancelRequest struct {
	Reason string `json:"reason"`
}

// NormalizeFeeCategory is payments' category key: trimmed, lower-case.
func NormalizeFeeCategory(s string) string {
	return strings.ToLower(strings.TrimSpace(s))
}

// FeeCategoryLabel turns a category key into a label ("home & art" → "Home & art").
func FeeCategoryLabel(key string) string {
	if key == "" || key == AllCategories {
		return "All categories"
	}
	r := []rune(key)
	r[0] = unicode.ToUpper(r[0])
	return string(r)
}

// FeeDraftRules are the facts a draft is validated against.
type FeeDraftRules struct {
	Now              time.Time
	MinEffectiveFrom time.Time
	ImmediateAllowed bool
	RetailOrgID      string
	Limits           FeeLimits
}

// ValidateFeeDraft checks a draft and returns field errors keyed by request property (categoryRates.2.commissionBps).
func ValidateFeeDraft(d FeeScheduleDraft, r FeeDraftRules) map[string]string {
	errs := map[string]string{}
	l := r.Limits
	pct := func(bps int) string { return fmt.Sprintf("%d.%02d%%", bps/100, bps%100) }
	money := func(minor int64) string { return fmt.Sprintf("$%d.%02d", minor/100, minor%100) }
	bpsIn := func(key string, v *int, hi int) {
		switch {
		case v == nil:
			errs[key] = "Enter a rate"
		case *v < 0 || *v > hi:
			errs[key] = "Enter a rate between 0.00% and " + pct(hi)
		}
	}
	minorIn := func(key string, v *int64, hi int64) {
		switch {
		case v == nil:
			errs[key] = "Enter an amount"
		case *v < 0 || *v > hi:
			errs[key] = "Enter an amount between $0.00 and " + money(hi)
		}
	}
	switch {
	case d.Immediate && d.EffectiveFrom != nil:
		errs["effectiveFrom"] = "Choose a date or immediate, not both"
	case d.Immediate && !r.ImmediateAllowed:
		errs["immediate"] = "A version is already scheduled: schedule this one after it, or cancel it first"
	case !d.Immediate && d.EffectiveFrom == nil:
		errs["effectiveFrom"] = "Choose when the new version takes effect"
	case !d.Immediate && !d.EffectiveFrom.After(r.Now):
		errs["effectiveFrom"] = "Choose a time in the future (or take effect immediately)"
	case !d.Immediate && d.EffectiveFrom.Before(r.MinEffectiveFrom):
		errs["effectiveFrom"] = "Must not be before the latest scheduled version (" + r.MinEffectiveFrom.UTC().Format("2006-01-02 15:04") + " UTC)"
	}
	bpsIn("defaultCommissionBps", d.DefaultCommissionBps, l.CommissionMaxBps)
	bpsIn("cardPctBps", d.CardPctBps, l.CardPctMaxBps)
	minorIn("cardFixedMinor", d.CardFixedMinor, l.CardFixedMaxMinor)
	minorIn("fbaPerUnitMinor", d.FbaPerUnitMinor, l.FbaPerUnitMaxMinor)
	if n := len([]rune(strings.TrimSpace(d.Note))); n < l.NoteMinLength || n > l.NoteMaxLength {
		errs["note"] = fmt.Sprintf("Explain the change (%d to %d characters)", l.NoteMinLength, l.NoteMaxLength)
	}
	if len(d.CategoryRates) > l.MaxCategoryRates {
		errs["categoryRates"] = fmt.Sprintf("At most %d categories", l.MaxCategoryRates)
	}
	seen := map[string]bool{}
	for i, c := range d.CategoryRates {
		key := NormalizeFeeCategory(c.Category)
		field := fmt.Sprintf("categoryRates.%d", i)
		switch {
		case key == "" || key == AllCategories:
			errs[field+".category"] = "Name the category"
		case len(key) > 80:
			errs[field+".category"] = "Keep the category under 80 characters"
		case seen[key]:
			errs[field+".category"] = "This category is listed twice"
		}
		seen[key] = true
		bpsIn(field+".commissionBps", c.CommissionBps, l.CommissionMaxBps)
	}
	if len(d.Overrides) > l.MaxOverrides {
		errs["overrides"] = fmt.Sprintf("At most %d seller overrides", l.MaxOverrides)
	}
	pairs := map[string]bool{}
	for i, o := range d.Overrides {
		field := fmt.Sprintf("overrides.%d", i)
		org := strings.TrimSpace(o.OrgID)
		cat := AllCategories
		if o.Category != nil && NormalizeFeeCategory(*o.Category) != "" {
			cat = NormalizeFeeCategory(*o.Category)
		}
		switch {
		case org == "" || len(org) > 80:
			errs[field+".orgId"] = "Choose a seller organisation"
		case r.RetailOrgID != "" && org == r.RetailOrgID:
			errs[field+".orgId"] = "AfroTransact Retail pays no commission and no fees (merchant of record); it can't have an override"
		case pairs[org+"|"+cat]:
			errs[field+".category"] = "This seller already has an override for this category"
		}
		if len(cat) > 80 {
			errs[field+".category"] = "Keep the category under 80 characters"
		}
		pairs[org+"|"+cat] = true
		bpsIn(field+".commissionBps", o.CommissionBps, l.CommissionMaxBps)
	}
	for k, v := range ValidateDeliveryDraft(d.DeliveryPricing, r.RetailOrgID, l) {
		errs[k] = v
	}
	for k, v := range ValidateRateSections(d, l) {
		errs[k] = v
	}
	return errs
}

// FeeStatusOf derives a version's status: cancelled, scheduled (future), active (the one in force) or superseded.
func FeeStatusOf(cancelled bool, effectiveFrom time.Time, isActive bool, now time.Time) string {
	switch {
	case cancelled:
		return FeeStatusCancelled
	case effectiveFrom.After(now):
		return FeeStatusScheduled
	case isActive:
		return FeeStatusActive
	default:
		return FeeStatusSuperseded
	}
}

// ---------------------------------------------------------------- Seller Center: read-only Fees page

// SellerFeeCategory is the commission the organisation pays in one category.
type SellerFeeCategory struct {
	Category      string `json:"category"`
	Label         string `json:"label"`
	CommissionBps int    `json:"commissionBps"`
	// Source: OVERRIDE (negotiated for this organisation), PLAN, CATEGORY, DEFAULT or RETAIL.
	Source          string     `json:"source"`
	PlanCode        *string    `json:"planCode"`
	PlanName        *string    `json:"planName"`
	NegotiatedUntil *time.Time `json:"negotiatedUntil"`
}

// SellerFeeVersion is the fees one fee-schedule version applies to the organisation.
type SellerFeeVersion struct {
	Version                   int                 `json:"version"`
	EffectiveFrom             time.Time           `json:"effectiveFrom"`
	DefaultCommissionBps      int                 `json:"defaultCommissionBps"`
	Categories                []SellerFeeCategory `json:"categories"`
	CardPctBps                int                 `json:"cardPctBps"`
	CardFixedMinor            int64               `json:"cardFixedMinor"`
	FbaPerUnitMinor           int64               `json:"fbaPerUnitMinor"`
	ReverseCommissionOnRefund bool                `json:"reverseCommissionOnRefund"`
	DeliveryFee               *SellerDeliveryFee  `json:"deliveryFee"`
}

// SellerDeliveryFee is what the organisation pays for AfroTransact Delivery from its store under one version (S255).
type SellerDeliveryFee struct {
	Enabled        bool            `json:"enabled"`
	MaxRadiusMiles float64         `json:"maxRadiusMiles"`
	Source         string          `json:"source"` // DEFAULT, OVERRIDE or RETAIL
	Tariff         *DeliveryTariff `json:"tariff"`
	Summary        string          `json:"summary"`
}

// SellerDeliveryFeeFor picks the organisation's seller fee from a version's delivery pricing: its override, else the
// default; AfroTransact Retail never pays one. Nil when the version has no delivery pricing.
func SellerDeliveryFeeFor(p *DeliveryPricing, orgID string, retail bool) *SellerDeliveryFee {
	if p == nil {
		return nil
	}
	out := &SellerDeliveryFee{Enabled: p.Enabled, MaxRadiusMiles: p.MaxRadiusMiles, Source: "DEFAULT", Tariff: p.SellerFee}
	if retail {
		out.Source, out.Tariff = "RETAIL", nil
		out.Summary = "No delivery fee (merchant of record)"
		return out
	}
	for _, o := range p.SellerOverrides {
		if o.OrgID == orgID {
			t := o.SellerFee
			out.Source, out.Tariff = "OVERRIDE", &t
		}
	}
	if out.Tariff != nil {
		t := out.Tariff.Normalized()
		out.Tariff = &t
	}
	out.Summary = TariffSummary(out.Tariff)
	return out
}

// SellerFeeChange is one rate that changes in the upcoming version.
type SellerFeeChange struct {
	Field string `json:"field"`
	Label string `json:"label"`
	From  string `json:"from"`
	To    string `json:"to"`
}

// SellerFeesView is GET /v1/seller/fees.
type SellerFeesView struct {
	Currency string            `json:"currency"`
	Retail   bool              `json:"retail"`
	Note     *string           `json:"note"`
	Current  SellerFeeVersion  `json:"current"`
	Upcoming *SellerFeeVersion `json:"upcoming"`
	Changes  []SellerFeeChange `json:"changes"`
	// PlanCode / PlanName: the organisation's plan whose rates apply (nil when none or unknown).
	PlanCode *string `json:"planCode"`
	PlanName *string `json:"planName"`
	// Negotiated are the organisation's negotiated commission rates (active or scheduled).
	Negotiated []SellerNegotiatedRate `json:"negotiated"`
	// ConnectionFees is what the organisation pays on service bookings (nil when payments could not say).
	ConnectionFees *SellerRateCard `json:"connectionFees"`
}

// FormatBps renders basis points as a percentage with two decimals ("12.50%").
func FormatBps(bps int) string {
	return fmt.Sprintf("%d.%02d%%", bps/100, bps%100)
}

// FormatMinor renders USD minor units ("$3.25").
func FormatMinor(minor int64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return fmt.Sprintf("%s$%d.%02d", sign, minor/100, minor%100)
}

// SellerFeeChanges lists what changes between the current and the upcoming version for one organisation.
func SellerFeeChanges(cur SellerFeeVersion, next *SellerFeeVersion) []SellerFeeChange {
	out := []SellerFeeChange{}
	if next == nil {
		return out
	}
	byCat := map[string]SellerFeeCategory{}
	for _, c := range cur.Categories {
		byCat[c.Category] = c
	}
	keys := []string{}
	nextBy := map[string]SellerFeeCategory{}
	for _, c := range next.Categories {
		nextBy[c.Category] = c
		keys = append(keys, c.Category)
	}
	for _, c := range cur.Categories {
		if _, ok := nextBy[c.Category]; !ok {
			keys = append(keys, c.Category)
		}
	}
	slices.Sort(keys)
	keys = slices.Compact(keys)
	for _, k := range keys {
		from, ok1 := byCat[k]
		to, ok2 := nextBy[k]
		fb, tb := cur.DefaultCommissionBps, next.DefaultCommissionBps
		label := FeeCategoryLabel(k)
		if ok1 {
			fb, label = from.CommissionBps, from.Label
		}
		if ok2 {
			tb, label = to.CommissionBps, to.Label
		}
		if fb != tb {
			out = append(out, SellerFeeChange{Field: "category:" + k, Label: label + " commission", From: FormatBps(fb), To: FormatBps(tb)})
		}
	}
	if cur.DefaultCommissionBps != next.DefaultCommissionBps {
		out = append(out, SellerFeeChange{Field: "defaultCommissionBps", Label: "Commission in other categories", From: FormatBps(cur.DefaultCommissionBps), To: FormatBps(next.DefaultCommissionBps)})
	}
	if cur.CardPctBps != next.CardPctBps || cur.CardFixedMinor != next.CardFixedMinor {
		out = append(out, SellerFeeChange{Field: "cardFee", Label: "Card fee estimate",
			From: FormatBps(cur.CardPctBps) + " + " + FormatMinor(cur.CardFixedMinor), To: FormatBps(next.CardPctBps) + " + " + FormatMinor(next.CardFixedMinor)})
	}
	if cur.FbaPerUnitMinor != next.FbaPerUnitMinor {
		out = append(out, SellerFeeChange{Field: "fbaPerUnitMinor", Label: "FBA fee per unit", From: FormatMinor(cur.FbaPerUnitMinor), To: FormatMinor(next.FbaPerUnitMinor)})
	}
	if cur.ReverseCommissionOnRefund != next.ReverseCommissionOnRefund {
		yn := func(b bool) string {
			if b {
				return "Returned on refunds"
			}
			return "Kept on refunds"
		}
		out = append(out, SellerFeeChange{Field: "reverseCommissionOnRefund", Label: "Commission on refunds", From: yn(cur.ReverseCommissionOnRefund), To: yn(next.ReverseCommissionOnRefund)})
	}
	feeText := func(f *SellerDeliveryFee) string {
		switch {
		case f == nil || !f.Enabled:
			return "Not offered"
		default:
			return f.Summary
		}
	}
	if from, to := feeText(cur.DeliveryFee), feeText(next.DeliveryFee); from != to {
		out = append(out, SellerFeeChange{Field: "deliveryFee", Label: "AfroTransact Delivery seller fee", From: from, To: to})
	}
	return out
}
