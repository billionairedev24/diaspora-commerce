package domain

import (
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

// AfroTransact Delivery from the seller's store (S255): the delivery pricing section of a fee-schedule version
// (payments owns the rules). The staff editor shows and edits it, the Seller Center shows the seller fee, and checkout
// quotes it through payments. Amounts are minor units, distances road miles.

// Delivery tariff modes.
const (
	TariffFlat     = "FLAT"
	TariffDistance = "DISTANCE"
)

// AfroTransactDelivery is the offer / package fulfilment of AfroTransact Delivery from the seller's store.
const AfroTransactDelivery = "AFROTRANSACT_DELIVERY"

// DeliveryBand is a fixed fee for a distance range (from inclusive, to exclusive).
type DeliveryBand struct {
	FromMiles float64 `json:"fromMiles"`
	ToMiles   float64 `json:"toMiles"`
	FeeMinor  int64   `json:"feeMinor"`
}

// DeliveryTariff is payments' DeliveryTariff as the screens show it (every key present, null when unset).
type DeliveryTariff struct {
	Mode                string         `json:"mode"`
	FlatPerPackageMinor *int64         `json:"flatPerPackageMinor"`
	BaseMinor           *int64         `json:"baseMinor"`
	PerMileMinor        *int64         `json:"perMileMinor"`
	Bands               []DeliveryBand `json:"bands"`
	MinMinor            *int64         `json:"minMinor"`
	MaxMinor            *int64         `json:"maxMinor"`
}

// Normalized returns the tariff with a non-nil bands list (JSON arrays, never null).
func (t DeliveryTariff) Normalized() DeliveryTariff {
	if t.Bands == nil {
		t.Bands = []DeliveryBand{}
	}
	t.Mode = strings.ToUpper(strings.TrimSpace(t.Mode))
	return t
}

// DeliveryFeeOverride is a negotiated seller delivery fee of one organisation.
type DeliveryFeeOverride struct {
	OrgID     string         `json:"orgId"`
	OrgName   *string        `json:"orgName"`
	SellerFee DeliveryTariff `json:"sellerFee"`
}

// DeliveryPricing is the section of one fee-schedule version.
type DeliveryPricing struct {
	Enabled         bool                  `json:"enabled"`
	MaxRadiusMiles  float64               `json:"maxRadiusMiles"`
	CustomerCharge  *DeliveryTariff       `json:"customerCharge"`
	SellerFee       *DeliveryTariff       `json:"sellerFee"`
	SellerOverrides []DeliveryFeeOverride `json:"sellerOverrides"`
}

// DeliveryDraftOverride is one seller override of a draft.
type DeliveryDraftOverride struct {
	OrgID     string          `json:"orgId"`
	SellerFee *DeliveryTariff `json:"sellerFee"`
}

// DeliveryPricingDraft is the section of a new version (nil on the draft = carried over from the latest live version).
type DeliveryPricingDraft struct {
	Enabled         bool                    `json:"enabled"`
	MaxRadiusMiles  *float64                `json:"maxRadiusMiles"`
	CustomerCharge  *DeliveryTariff         `json:"customerCharge"`
	SellerFee       *DeliveryTariff         `json:"sellerFee"`
	SellerOverrides []DeliveryDraftOverride `json:"sellerOverrides"`
}

// DeliveryQuote is payments' DeliveryQuote as the screens show it.
type DeliveryQuote struct {
	Available           bool     `json:"available"`
	UnavailableReason   *string  `json:"unavailableReason"`
	CustomerChargeMinor int64    `json:"customerChargeMinor"`
	SellerFeeMinor      int64    `json:"sellerFeeMinor"`
	SellerFeeSource     *string  `json:"sellerFeeSource"`
	ScheduleVersion     *int     `json:"scheduleVersion"`
	MaxRadiusMiles      *float64 `json:"maxRadiusMiles"`
	Currency            string   `json:"currency"`
}

// DeliveryQuoteRequest is POST /v1/staff/fees/delivery-quote.
type DeliveryQuoteRequest struct {
	SellerOrgID   string   `json:"sellerOrgId"`
	DistanceMiles *float64 `json:"distanceMiles"`
	Packages      int      `json:"packages"`
	At            *string  `json:"at"`
}

// QuoteTariff prices one trip like payments: FLAT counts packages; DISTANCE uses the band covering the miles, else
// base + per mile × miles (half up), then at least min and at most max. A nil tariff is free.
func QuoteTariff(t *DeliveryTariff, miles float64, packages int) int64 {
	if t == nil {
		return 0
	}
	if strings.EqualFold(t.Mode, TariffFlat) {
		return val(t.FlatPerPackageMinor) * int64(max(1, packages))
	}
	fee := int64(-1)
	for _, b := range t.Bands {
		if miles >= b.FromMiles && miles < b.ToMiles {
			fee = b.FeeMinor
			break
		}
	}
	if fee < 0 {
		fee = val(t.BaseMinor) + int64(math.Floor(float64(val(t.PerMileMinor))*miles+0.5))
	}
	if t.MinMinor != nil && fee < *t.MinMinor {
		fee = *t.MinMinor
	}
	if t.MaxMinor != nil && fee > *t.MaxMinor {
		fee = *t.MaxMinor
	}
	return fee
}

func val(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

// TariffSummary describes a tariff in one line ("$2.00 per package", "$4.99 + $0.85/mi, at least $5.99, at most $24.99").
func TariffSummary(t *DeliveryTariff) string {
	if t == nil {
		return "No fee"
	}
	if strings.EqualFold(t.Mode, TariffFlat) {
		return FormatMinor(val(t.FlatPerPackageMinor)) + " per package"
	}
	parts := []string{}
	if len(t.Bands) > 0 {
		bands := make([]string, 0, len(t.Bands))
		for _, b := range t.Bands {
			bands = append(bands, fmt.Sprintf("%s–%s mi %s", miles(b.FromMiles), miles(b.ToMiles), FormatMinor(b.FeeMinor)))
		}
		parts = append(parts, strings.Join(bands, ", ")+"; otherwise")
	}
	parts = append(parts, FormatMinor(val(t.BaseMinor))+" + "+FormatMinor(val(t.PerMileMinor))+"/mi")
	s := strings.Join(parts, " ")
	if t.MinMinor != nil && *t.MinMinor > 0 {
		s += ", at least " + FormatMinor(*t.MinMinor)
	}
	if t.MaxMinor != nil {
		s += ", at most " + FormatMinor(*t.MaxMinor)
	}
	return s
}

func miles(m float64) string { return strconv.FormatFloat(m, 'f', -1, 64) }

// ValidateDeliveryDraft checks the delivery section of a draft; errors are keyed deliveryPricing.<property>.
func ValidateDeliveryDraft(d *DeliveryPricingDraft, retailOrgID string, l FeeLimits) map[string]string {
	errs := map[string]string{}
	if d == nil {
		return errs
	}
	const p = "deliveryPricing."
	switch {
	case d.MaxRadiusMiles == nil:
		errs[p+"maxRadiusMiles"] = "Enter the maximum radius in miles"
	case *d.MaxRadiusMiles <= 0 || *d.MaxRadiusMiles > l.DeliveryMaxRadiusMiles:
		errs[p+"maxRadiusMiles"] = fmt.Sprintf("Enter more than 0 and at most %s miles", miles(l.DeliveryMaxRadiusMiles))
	}
	validateTariff(errs, p+"customerCharge", d.CustomerCharge, l)
	validateTariff(errs, p+"sellerFee", d.SellerFee, l)
	if len(d.SellerOverrides) > l.MaxDeliveryOverrides {
		errs[p+"sellerOverrides"] = fmt.Sprintf("At most %d seller overrides", l.MaxDeliveryOverrides)
	}
	seen := map[string]bool{}
	for i, o := range d.SellerOverrides {
		key := fmt.Sprintf("%ssellerOverrides.%d", p, i)
		org := strings.TrimSpace(o.OrgID)
		switch {
		case org == "" || len(org) > 80:
			errs[key+".orgId"] = "Choose a seller organisation"
		case retailOrgID != "" && org == retailOrgID:
			errs[key+".orgId"] = "AfroTransact Retail never pays a delivery fee; it can't have an override"
		case seen[org]:
			errs[key+".orgId"] = "This seller already has a delivery fee override"
		}
		seen[org] = true
		if o.SellerFee == nil {
			errs[key+".sellerFee"] = "Set the seller's fee"
			continue
		}
		validateTariff(errs, key+".sellerFee", o.SellerFee, l)
	}
	return errs
}

func validateTariff(errs map[string]string, key string, t *DeliveryTariff, l FeeLimits) {
	if t == nil {
		return
	}
	amount := func(field string, v *int64, required bool) {
		switch {
		case v == nil && required:
			errs[key+"."+field] = "Enter an amount"
		case v != nil && (*v < 0 || *v > l.DeliveryAmountMaxMinor):
			errs[key+"."+field] = "Enter an amount between $0.00 and " + FormatMinor(l.DeliveryAmountMaxMinor)
		}
	}
	switch strings.ToUpper(t.Mode) {
	case TariffFlat:
		amount("flatPerPackageMinor", t.FlatPerPackageMinor, true)
	case TariffDistance:
		amount("baseMinor", t.BaseMinor, true)
		amount("perMileMinor", t.PerMileMinor, true)
		amount("minMinor", t.MinMinor, false)
		amount("maxMinor", t.MaxMinor, false)
		if t.MinMinor != nil && t.MaxMinor != nil && *t.MinMinor > *t.MaxMinor && errs[key+".maxMinor"] == "" {
			errs[key+".maxMinor"] = "The maximum must not be below the minimum"
		}
		if len(t.Bands) > l.DeliveryMaxBands {
			errs[key+".bands"] = fmt.Sprintf("At most %d distance bands", l.DeliveryMaxBands)
		}
		idx := make([]int, len(t.Bands))
		for i := range idx {
			idx[i] = i
		}
		sort.SliceStable(idx, func(a, b int) bool { return t.Bands[idx[a]].FromMiles < t.Bands[idx[b]].FromMiles })
		for n, i := range idx {
			b := t.Bands[i]
			bk := fmt.Sprintf("%s.bands.%d", key, i)
			switch {
			case b.FromMiles < 0:
				errs[bk+".fromMiles"] = "Start at 0 miles or more"
			case b.ToMiles <= b.FromMiles:
				errs[bk+".toMiles"] = "Must be more than the start of the band"
			case n > 0 && b.FromMiles < t.Bands[idx[n-1]].ToMiles:
				errs[bk+".fromMiles"] = "Bands must not overlap"
			}
			if b.FeeMinor < 0 || b.FeeMinor > l.DeliveryAmountMaxMinor {
				errs[bk+".feeMinor"] = "Enter an amount between $0.00 and " + FormatMinor(l.DeliveryAmountMaxMinor)
			}
		}
	default:
		errs[key+".mode"] = "Choose flat or by distance"
	}
}
