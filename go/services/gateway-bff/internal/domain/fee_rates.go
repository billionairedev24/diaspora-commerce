package domain

import (
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Commission and connection fees on top of plan fees (payments' fee schedule): product lines pay a commission, service
// bookings a connection fee, each a configurable percentage. First match wins: an active negotiated rate of the
// organisation (its category, then every category) → the organisation's plan rate (category, then the plan's *) → the
// category rate → the default. AfroTransact Retail pays none.

// Rate kinds (payments' FeeKind) and negotiated-rate types.
const (
	FeeKindProduct          = "PRODUCT"
	FeeKindService          = "SERVICE"
	NegotiatedCommission    = "COMMISSION"
	NegotiatedConnectionFee = "CONNECTION_FEE"
)

// Rate sources, in precedence order (RETAIL: AfroTransact Retail pays none).
const (
	RateSourceOverride = "OVERRIDE"
	RateSourcePlan     = "PLAN"
	RateSourceCategory = "CATEGORY"
	RateSourceDefault  = "DEFAULT"
	RateSourceRetail   = "RETAIL"
)

// NegotiatedStatuses are the statuses of a negotiated rate (derived by payments from its dates).
var NegotiatedStatuses = []string{"SCHEDULED", "ACTIVE", "ENDED", "EXPIRED"}

// KindOfNegotiated maps a negotiated-rate type to the line kind it prices.
func KindOfNegotiated(t string) string {
	if t == NegotiatedConnectionFee {
		return FeeKindService
	}
	return FeeKindProduct
}

// PlanName names a plan code for screens when the plan catalogue cannot be read ("seller_growth" → "Seller Growth").
func PlanName(code string) string {
	parts := strings.FieldsFunc(code, func(r rune) bool { return r == '_' || r == '-' })
	for i, p := range parts {
		r := []rune(p)
		r[0] = unicode.ToUpper(r[0])
		parts[i] = string(r)
	}
	return strings.Join(parts, " ")
}

// FeeRate is one category rate of a rate section (connection fees).
type FeeRate struct {
	Category string `json:"category"`
	Label    string `json:"label"`
	Bps      int    `json:"bps"`
}

// FeePlanRate is one plan's rate for one category ("*" = the plan's rate for categories it does not list).
type FeePlanRate struct {
	PlanCode string `json:"planCode"`
	PlanName string `json:"planName"`
	Category string `json:"category"`
	Label    string `json:"label"`
	Bps      int    `json:"bps"`
}

// FeePlanFba is one plan's FBA fee per unit (plans without one pay the version's default).
type FeePlanFba struct {
	PlanCode string `json:"planCode"`
	PlanName string `json:"planName"`
	FeeMinor int64  `json:"feeMinor"`
}

// FeeDraftPlanFba is a draft's FBA fee per unit for one plan.
type FeeDraftPlanFba struct {
	PlanCode string `json:"planCode"`
	FeeMinor *int64 `json:"feeMinor"`
}

// PlanFbaBody turns draft plan FBA fees into payments' planFbaMinor (plan → minor units).
func PlanFbaBody(rows []FeeDraftPlanFba) map[string]int64 {
	out := map[string]int64{}
	for _, r := range rows {
		out[strings.ToLower(strings.TrimSpace(r.PlanCode))] = *r.FeeMinor
	}
	return out
}

// PlanFbaOf turns payments' planFbaMinor into rows sorted by plan.
func PlanFbaOf(plans map[string]int64) []FeePlanFba {
	out := []FeePlanFba{}
	for code, fee := range plans {
		out = append(out, FeePlanFba{PlanCode: code, PlanName: PlanName(code), FeeMinor: fee})
	}
	slices.SortFunc(out, func(a, b FeePlanFba) int { return strings.Compare(a.PlanCode, b.PlanCode) })
	return out
}

// ValidatePlanFba checks a draft's plan FBA fees (keys like planFba.0.feeMinor).
func ValidatePlanFba(rows []FeeDraftPlanFba, l FeeLimits) map[string]string {
	errs := map[string]string{}
	seen := map[string]bool{}
	for i, r := range rows {
		field := fmt.Sprintf("planFba.%d", i)
		code := strings.ToLower(strings.TrimSpace(r.PlanCode))
		switch {
		case !planCodePattern.MatchString(code):
			errs[field+".planCode"] = "Choose a plan"
		case seen[code]:
			errs[field+".planCode"] = "This plan already has an FBA fee"
		}
		seen[code] = true
		switch {
		case r.FeeMinor == nil:
			errs[field+".feeMinor"] = "Enter a fee"
		case *r.FeeMinor < 0 || *r.FeeMinor > l.FbaPerUnitMaxMinor:
			errs[field+".feeMinor"] = "Enter a fee between $0.00 and " + FormatMinor(l.FbaPerUnitMaxMinor)
		}
	}
	return errs
}

// FeeConnectionFee is the connection-fee section of a fee-schedule version (service bookings).
type FeeConnectionFee struct {
	DefaultBps    int           `json:"defaultBps"`
	CategoryRates []FeeRate     `json:"categoryRates"`
	PlanRates     []FeePlanRate `json:"planRates"`
}

// FeeRange is a lowest–highest rate in basis points.
type FeeRange struct {
	MinBps int `json:"minBps"`
	MaxBps int `json:"maxBps"`
}

// Text renders the range like the plan features do ("8–12%", "15%").
func (r FeeRange) Text() string {
	pct := func(bps int) string {
		if bps%100 == 0 {
			return strconv.Itoa(bps / 100)
		}
		return strconv.FormatFloat(float64(bps)/100, 'f', -1, 64)
	}
	if r.MinBps == r.MaxBps {
		return pct(r.MinBps) + "%"
	}
	return pct(r.MinBps) + "–" + pct(r.MaxBps) + "%"
}

// FeePlan is a plan of the catalogue with what its features advertise and what the fee schedule configures.
type FeePlan struct {
	Code string `json:"code"`
	// Kind SELLER (product commission) or PROVIDER (service connection fee).
	Kind       string `json:"kind"`
	Name       string `json:"name"`
	Status     string `json:"status"`
	Visibility string `json:"visibility"`
	// FeatureText is the plan feature that mentions a fee ("8–12% referral fee by category"), when one does.
	FeatureText *string   `json:"featureText"`
	Advertised  *FeeRange `json:"advertised"`
	// Configured is the lowest–highest plan rate of the version in force (nil: the plan has no rates there).
	Configured *FeeRange `json:"configured"`
	// Warning says how the feature text and the configured rates differ (nil when they agree).
	Warning *string `json:"warning"`
}

var advertisedFee = regexp.MustCompile(`(?i)(\d+(?:\.\d+)?)\s*%?\s*(?:[–—-]\s*(\d+(?:\.\d+)?))?\s*%\s*(referral|booking|connection|commission)?\s*fee`)

// AdvertisedFee finds the fee a plan feature advertises ("8–12% referral fee by category" → 800–1200 bps).
func AdvertisedFee(features []string) (string, *FeeRange) {
	for _, f := range features {
		m := advertisedFee.FindStringSubmatch(f)
		if m == nil {
			continue
		}
		lo, err := strconv.ParseFloat(m[1], 64)
		if err != nil {
			continue
		}
		hi := lo
		if m[2] != "" {
			if v, err := strconv.ParseFloat(m[2], 64); err == nil {
				hi = v
			}
		}
		return f, &FeeRange{MinBps: int(lo*100 + 0.5), MaxBps: int(hi*100 + 0.5)}
	}
	return "", nil
}

// PlanFeeWarning compares what a plan advertises with its configured rates; "" when they agree.
func PlanFeeWarning(name string, advertised, configured *FeeRange) string {
	switch {
	case advertised == nil:
		return ""
	case configured == nil:
		return fmt.Sprintf("%s advertises %s but has no plan rates in the fee schedule: its sellers pay category rates.", name, advertised.Text())
	case *advertised != *configured:
		return fmt.Sprintf("%s advertises %s but the fee schedule charges %s.", name, advertised.Text(), configured.Text())
	}
	return ""
}

// PlanRange is the lowest–highest rate of one plan in a section's plan rates.
func PlanRange(rates []FeePlanRate, plan string) *FeeRange {
	var out *FeeRange
	for _, r := range rates {
		if r.PlanCode != plan {
			continue
		}
		if out == nil {
			out = &FeeRange{MinBps: r.Bps, MaxBps: r.Bps}
			continue
		}
		out.MinBps, out.MaxBps = min(out.MinBps, r.Bps), max(out.MaxBps, r.Bps)
	}
	return out
}

// ---------------------------------------------------------------- drafts of the rate sections

// FeeDraftRate is one category rate of a draft's connection-fee section.
type FeeDraftRate struct {
	Category string `json:"category"`
	Bps      *int   `json:"bps"`
}

// FeeDraftPlanRate is one plan rate of a draft ("*" or empty category = the plan's other categories).
type FeeDraftPlanRate struct {
	PlanCode string `json:"planCode"`
	Category string `json:"category"`
	Bps      *int   `json:"bps"`
}

// FeeConnectionDraft is a draft's connection-fee section.
type FeeConnectionDraft struct {
	DefaultBps    *int               `json:"defaultBps"`
	CategoryRates []FeeDraftRate     `json:"categoryRates"`
	PlanRates     []FeeDraftPlanRate `json:"planRates"`
}

var planCodePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,79}$`)

// ValidateRateSections checks a draft's plan rates and connection-fee section (field errors keyed like
// planRates.1.bps, connectionFee.categoryRates.0.category).
func ValidateRateSections(d FeeScheduleDraft, l FeeLimits) map[string]string {
	errs := map[string]string{}
	bpsIn := func(key string, v *int) {
		switch {
		case v == nil:
			errs[key] = "Enter a rate"
		case *v < 0 || *v > l.CommissionMaxBps:
			errs[key] = "Enter a rate between 0.00% and " + FormatBps(l.CommissionMaxBps)
		}
	}
	plans := func(prefix string, rows []FeeDraftPlanRate) {
		seen := map[string]bool{}
		for i, r := range rows {
			field := fmt.Sprintf("%s.%d", prefix, i)
			code := strings.ToLower(strings.TrimSpace(r.PlanCode))
			cat := NormalizeFeeCategory(r.Category)
			if cat == "" {
				cat = AllCategories
			}
			switch {
			case !planCodePattern.MatchString(code):
				errs[field+".planCode"] = "Choose a plan"
			case len(cat) > 80:
				errs[field+".category"] = "Keep the category under 80 characters"
			case seen[code+"|"+cat]:
				errs[field+".category"] = "This plan already has a rate for this category"
			}
			seen[code+"|"+cat] = true
			bpsIn(field+".bps", r.Bps)
		}
		if len(rows) > l.MaxOverrides {
			errs[prefix] = fmt.Sprintf("At most %d plan rates", l.MaxOverrides)
		}
	}
	plans("planRates", d.PlanRates)
	for k, v := range ValidatePlanFba(d.PlanFba, l) {
		errs[k] = v
	}
	if c := d.ConnectionFee; c != nil {
		bpsIn("connectionFee.defaultBps", c.DefaultBps)
		seen := map[string]bool{}
		for i, r := range c.CategoryRates {
			field := fmt.Sprintf("connectionFee.categoryRates.%d", i)
			key := NormalizeFeeCategory(r.Category)
			switch {
			case key == "" || key == AllCategories:
				errs[field+".category"] = "Name the service category"
			case len(key) > 80:
				errs[field+".category"] = "Keep the category under 80 characters"
			case seen[key]:
				errs[field+".category"] = "This category is listed twice"
			}
			seen[key] = true
			bpsIn(field+".bps", r.Bps)
		}
		if len(c.CategoryRates) > l.MaxCategoryRates {
			errs["connectionFee.categoryRates"] = fmt.Sprintf("At most %d categories", l.MaxCategoryRates)
		}
		plans("connectionFee.planRates", c.PlanRates)
	}
	return errs
}

// PlanBpsBody turns draft plan rates into payments' planBps (plan → category → bps).
func PlanBpsBody(rows []FeeDraftPlanRate) map[string]map[string]int {
	out := map[string]map[string]int{}
	for _, r := range rows {
		code := strings.ToLower(strings.TrimSpace(r.PlanCode))
		cat := NormalizeFeeCategory(r.Category)
		if cat == "" {
			cat = AllCategories
		}
		if out[code] == nil {
			out[code] = map[string]int{}
		}
		out[code][cat] = *r.Bps
	}
	return out
}

// PlanRatesOf flattens payments' planBps into rows sorted by plan, then "*" first, then category.
func PlanRatesOf(plans map[string]map[string]int, label func(string) string) []FeePlanRate {
	out := []FeePlanRate{}
	for code, rates := range plans {
		for cat, bps := range rates {
			l := "Other categories"
			if cat != AllCategories {
				l = label(cat)
			}
			out = append(out, FeePlanRate{PlanCode: code, PlanName: PlanName(code), Category: cat, Label: l, Bps: bps})
		}
	}
	slices.SortFunc(out, func(a, b FeePlanRate) int {
		if c := strings.Compare(a.PlanCode, b.PlanCode); c != 0 {
			return c
		}
		if (a.Category == AllCategories) != (b.Category == AllCategories) {
			if a.Category == AllCategories {
				return -1
			}
			return 1
		}
		return strings.Compare(a.Category, b.Category)
	})
	return out
}

// ---------------------------------------------------------------- negotiated rates (staff)

// NegotiatedRateAuditEntry is one audit line of a negotiated rate.
type NegotiatedRateAuditEntry struct {
	Action string    `json:"action"`
	Actor  string    `json:"actor"`
	Reason *string   `json:"reason"`
	At     time.Time `json:"at"`
}

// NegotiatedRateView is a negotiated commission or connection fee as the console shows it.
type NegotiatedRateView struct {
	ID            string                     `json:"id"`
	OrgID         string                     `json:"orgId"`
	OrgName       *string                    `json:"orgName"`
	Type          string                     `json:"type"`
	Category      string                     `json:"category"`
	CategoryLabel string                     `json:"categoryLabel"`
	RateBps       int                        `json:"rateBps"`
	StartsAt      time.Time                  `json:"startsAt"`
	EndsAt        *time.Time                 `json:"endsAt"`
	Status        string                     `json:"status"`
	Reason        string                     `json:"reason"`
	NegotiatedBy  string                     `json:"negotiatedBy"`
	Reference     *string                    `json:"reference"`
	CreatedBy     string                     `json:"createdBy"`
	CreatedAt     time.Time                  `json:"createdAt"`
	EndedAt       *time.Time                 `json:"endedAt"`
	EndedBy       *string                    `json:"endedBy"`
	EndReason     *string                    `json:"endReason"`
	ExpiredAt     *time.Time                 `json:"expiredAt"`
	Audit         []NegotiatedRateAuditEntry `json:"audit"`
	// CanEnd: the caller configures fees and the rate is scheduled or active.
	CanEnd bool `json:"canEnd"`
}

// NegotiatedRatePage is GET /v1/staff/fees/negotiated-rates (server-driven table).
type NegotiatedRatePage struct {
	Items        []NegotiatedRateView `json:"items"`
	Total        int                  `json:"total"`
	Page         int                  `json:"page"`
	Size         int                  `json:"size"`
	CanConfigure bool                 `json:"canConfigure"`
}

// NegotiatedRateDraft is POST /v1/staff/fees/negotiated-rates.
type NegotiatedRateDraft struct {
	OrgID        string     `json:"orgId"`
	Type         string     `json:"type"`
	Category     *string    `json:"category"`
	RateBps      *int       `json:"rateBps"`
	StartsAt     *time.Time `json:"startsAt"`
	EndsAt       *time.Time `json:"endsAt"`
	Reason       string     `json:"reason"`
	NegotiatedBy *string    `json:"negotiatedBy"`
	Reference    *string    `json:"reference"`
}

// NegotiatedRateEnd is POST /v1/staff/fees/negotiated-rates/{rateId}/end.
type NegotiatedRateEnd struct {
	Reason string `json:"reason"`
}

// ValidateNegotiatedRateDraft checks a negotiated-rate draft (payments checks overlaps).
func ValidateNegotiatedRateDraft(d NegotiatedRateDraft, now time.Time, retailOrgID string, l FeeLimits) map[string]string {
	errs := map[string]string{}
	org := strings.TrimSpace(d.OrgID)
	switch {
	case org == "" || len(org) > 80:
		errs["orgId"] = "Choose a seller or provider organisation"
	case retailOrgID != "" && org == retailOrgID:
		errs["orgId"] = "AfroTransact Retail pays no commission and no fees (merchant of record); nothing to negotiate"
	}
	if d.Type != NegotiatedCommission && d.Type != NegotiatedConnectionFee {
		errs["type"] = "Choose commission or connection fee"
	}
	if d.Category != nil && len(NormalizeFeeCategory(*d.Category)) > 80 {
		errs["category"] = "Keep the category under 80 characters"
	}
	switch {
	case d.RateBps == nil:
		errs["rateBps"] = "Enter a rate"
	case *d.RateBps < 0 || *d.RateBps > l.CommissionMaxBps:
		errs["rateBps"] = "Enter a rate between 0.00% and " + FormatBps(l.CommissionMaxBps)
	}
	if d.StartsAt != nil && d.StartsAt.Before(now.Add(-time.Minute)) {
		errs["startsAt"] = "Choose now or a later time: a negotiated rate never re-prices past orders"
	}
	start := now
	if d.StartsAt != nil && d.StartsAt.After(now) {
		start = *d.StartsAt
	}
	if d.EndsAt != nil && !d.EndsAt.After(start) {
		errs["endsAt"] = "The end must be after the start"
	}
	if n := len([]rune(strings.TrimSpace(d.Reason))); n < l.NoteMinLength || n > l.NoteMaxLength {
		errs["reason"] = fmt.Sprintf("Say why this rate was negotiated (%d to %d characters)", l.NoteMinLength, l.NoteMaxLength)
	}
	if d.NegotiatedBy != nil && len([]rune(strings.TrimSpace(*d.NegotiatedBy))) > 120 {
		errs["negotiatedBy"] = "At most 120 characters"
	}
	if d.Reference != nil && len([]rune(strings.TrimSpace(*d.Reference))) > 120 {
		errs["reference"] = "At most 120 characters"
	}
	return errs
}

// FeeRateQuote is GET /v1/staff/fees/calculator: the rate one organisation pays on one kind and category now.
type FeeRateQuote struct {
	OrgID            string     `json:"orgId"`
	OrgName          *string    `json:"orgName"`
	Kind             string     `json:"kind"`
	Category         string     `json:"category"`
	CategoryLabel    string     `json:"categoryLabel"`
	Bps              int        `json:"bps"`
	Source           string     `json:"source"`
	PlanCode         *string    `json:"planCode"`
	PlanName         *string    `json:"planName"`
	NegotiatedRateID *string    `json:"negotiatedRateId"`
	NegotiatedUntil  *time.Time `json:"negotiatedUntil"`
	Version          int        `json:"version"`
	ExampleSaleMinor int64      `json:"exampleSaleMinor"`
	ExampleFeeMinor  int64      `json:"exampleFeeMinor"`
	Explanation      string     `json:"explanation"`
}

// RateExplanation says in words where a rate comes from.
func RateExplanation(kind, source string, planName *string, until *time.Time) string {
	what := "commission"
	if kind == FeeKindService {
		what = "connection fee"
	}
	switch source {
	case RateSourceOverride:
		if until != nil {
			return "Negotiated " + what + " until " + until.UTC().Format("2 Jan 2006")
		}
		return "Negotiated " + what + " (open-ended)"
	case RateSourcePlan:
		if planName != nil {
			return "Plan rate: " + *planName
		}
		return "Plan rate"
	case RateSourceCategory:
		return "Category rate of the fee schedule"
	case RateSourceRetail:
		return RetailFeesNote
	}
	return "Default " + what + " of the fee schedule"
}

// PercentOf is amount × bps / 10 000, rounded half up (payments' rounding).
func PercentOf(amountMinor int64, bps int) int64 {
	if amountMinor <= 0 {
		return 0
	}
	return (amountMinor*int64(bps) + 5_000) / 10_000
}

// ---------------------------------------------------------------- negotiated plan prices

// NegotiatedPlanDraft is POST /v1/staff/fees/orgs/{orgId}/negotiated-plan: a private (invite-only) version of a
// catalogue plan at a negotiated price for one organisation.
type NegotiatedPlanDraft struct {
	BasePlanCode   string  `json:"basePlanCode"`
	MonthlyCents   *int64  `json:"monthlyCents"`
	QuarterlyCents *int64  `json:"quarterlyCents"`
	AnnualCents    *int64  `json:"annualCents"`
	Reason         string  `json:"reason"`
	Reference      *string `json:"reference"`
}

// TaxRatesImportResult is the outcome of a CSV import into tax-returns' rate table.
type TaxRatesImportResult struct {
	StatesUpserted int      `json:"statesUpserted"`
	LocalsUpserted int      `json:"localsUpserted"`
	Warnings       []string `json:"warnings"`
}

// NegotiatedPlan is the private plan created for the organisation.
type NegotiatedPlan struct {
	PlanCode       string `json:"planCode"`
	PlanName       string `json:"planName"`
	BasePlanCode   string `json:"basePlanCode"`
	OrgID          string `json:"orgId"`
	Visibility     string `json:"visibility"`
	Published      bool   `json:"published"`
	MonthlyCents   int64  `json:"monthlyCents"`
	QuarterlyCents int64  `json:"quarterlyCents"`
	AnnualCents    int64  `json:"annualCents"`
	// Note says what happens next: the seller chooses the plan in Plan & billing (staff cannot switch it for them).
	Note string `json:"note"`
}

// NegotiatedPlanCode is the private plan's code: the base plan, then the organisation ("seller_growth_org_kente").
func NegotiatedPlanCode(base, orgID string) string {
	slug := strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '_':
			return r
		case r >= 'A' && r <= 'Z':
			return unicode.ToLower(r)
		}
		return '_'
	}, orgID)
	code := strings.ToLower(base) + "_" + slug
	if len(code) > 60 {
		code = code[:60]
	}
	return strings.TrimRight(code, "_")
}

// ValidateNegotiatedPlanDraft checks the negotiated plan price (cents per cadence; quarterly and annual optional).
func ValidateNegotiatedPlanDraft(d NegotiatedPlanDraft, l FeeLimits) map[string]string {
	errs := map[string]string{}
	if !planCodePattern.MatchString(strings.ToLower(strings.TrimSpace(d.BasePlanCode))) {
		errs["basePlanCode"] = "Choose the plan to start from"
	}
	cents := func(key string, v *int64, required bool) {
		switch {
		case v == nil && required:
			errs[key] = "Enter the negotiated price"
		case v != nil && (*v < 0 || *v > 10_000_000):
			errs[key] = "Enter a price between $0.00 and $100,000.00"
		}
	}
	cents("monthlyCents", d.MonthlyCents, true)
	cents("quarterlyCents", d.QuarterlyCents, false)
	cents("annualCents", d.AnnualCents, false)
	if n := len([]rune(strings.TrimSpace(d.Reason))); n < l.NoteMinLength || n > l.NoteMaxLength {
		errs["reason"] = fmt.Sprintf("Say why this price was negotiated (%d to %d characters)", l.NoteMinLength, l.NoteMaxLength)
	}
	if d.Reference != nil && len([]rune(strings.TrimSpace(*d.Reference))) > 120 {
		errs["reference"] = "At most 120 characters"
	}
	return errs
}

// ---------------------------------------------------------------- Seller Center and Provider Hub

// SellerAppliedRate is a rate the organisation pays with where it comes from.
type SellerAppliedRate struct {
	Category        string     `json:"category"`
	Label           string     `json:"label"`
	Bps             int        `json:"bps"`
	Source          string     `json:"source"`
	PlanCode        *string    `json:"planCode"`
	PlanName        *string    `json:"planName"`
	NegotiatedUntil *time.Time `json:"negotiatedUntil"`
}

// SellerNegotiatedRate is one of the organisation's negotiated rates (active or scheduled).
type SellerNegotiatedRate struct {
	Type     string     `json:"type"`
	Category string     `json:"category"`
	Label    string     `json:"label"`
	RateBps  int        `json:"rateBps"`
	StartsAt time.Time  `json:"startsAt"`
	EndsAt   *time.Time `json:"endsAt"`
	Status   string     `json:"status"`
}

// SellerRateCard is what the organisation pays on one kind of line now (commission or connection fee).
type SellerRateCard struct {
	Kind          string                 `json:"kind"`
	Version       int                    `json:"version"`
	EffectiveFrom time.Time              `json:"effectiveFrom"`
	PlanCode      *string                `json:"planCode"`
	PlanName      *string                `json:"planName"`
	Other         SellerAppliedRate      `json:"other"`
	Categories    []SellerAppliedRate    `json:"categories"`
	Negotiated    []SellerNegotiatedRate `json:"negotiated"`
}

// BookingTakeHome is what the provider keeps of a booking at its price: price − connection fee − card fees on the
// deposit charge and on the balance charge (bookings' take-home formula).
type BookingTakeHome struct {
	ConnectionFeeBps   int    `json:"connectionFeeBps"`
	ConnectionFeeMinor int64  `json:"connectionFeeMinor"`
	CardFeesMinor      int64  `json:"cardFeesMinor"`
	KeepMinor          int64  `json:"keepMinor"`
	Source             string `json:"source"`
	Currency           string `json:"currency"`
}

// TakeHomeOf computes a booking's take-home under a connection fee and a card fee estimate.
func TakeHomeOf(priceMinor, depositMinor int64, feeBps, cardPctBps int, cardFixedMinor int64, source, currency string) BookingTakeHome {
	card := func(charge int64) int64 {
		if charge <= 0 {
			return 0
		}
		return PercentOf(charge, cardPctBps) + cardFixedMinor
	}
	fee := PercentOf(priceMinor, feeBps)
	cards := card(depositMinor) + card(priceMinor-depositMinor)
	return BookingTakeHome{ConnectionFeeBps: feeBps, ConnectionFeeMinor: fee, CardFeesMinor: cards, KeepMinor: priceMinor - fee - cards,
		Source: source, Currency: firstOr(currency, Currency)}
}

func firstOr(v, def string) string {
	if v == "" {
		return def
	}
	return v
}
