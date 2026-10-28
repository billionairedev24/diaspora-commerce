package domain

import (
	"fmt"
	"strings"
	"sync/atomic"
	"time"
)

// DeliveryPolicy holds the commercial delivery rules used when the promise service has no answer.
type DeliveryPolicy struct {
	SellerShippingMinor        int64
	FreeShippingThresholdMinor int64
	PlatformDeliveryMode       string // "FLAT", "PER_WEIGHT", or "DISTANCE"
	PlatformDeliveryFlatMinor  int64
	PlatformDeliveryBaseMinor  int64
	PlatformDeliveryPerLbMinor int64
	Location                   *time.Location
}

// Default business-day estimates when neither the promise service nor search carries one.
const (
	DefaultAfroTransactDays = 2
	DefaultSellerDays       = 4
	// DefaultLocalDeliveryDays is AfroTransact Delivery from the seller's store (offers' buy box uses 1 day).
	DefaultLocalDeliveryDays = 1
	ExpressFeeMinor          = 799
)

// LocalDeliveryOptionLabel is the AfroTransact Delivery option of a seller-store package before checkout prices it.
const LocalDeliveryOptionLabel = "AfroTransact Delivery · priced by distance at checkout"

// Estimator builds delivery promises and package delivery options from a business-day estimate.
type Estimator interface {
	// Promise estimates delivery for an offer; days <= 0 means unknown (defaults apply).
	Promise(fulfilment string, days int, now time.Time) DeliveryPromise
	// Options lists the delivery options for a package with the given (selected) subtotal.
	Options(fulfilment string, days int, subtotalMinor int64, now time.Time) []DeliveryOption
	// Nudge returns the free-shipping nudge for seller-shipped packages under the threshold.
	Nudge(fulfilment string, subtotalMinor int64) *ShippingNudge
	// DayLabel renders "Today", "Tomorrow, Oct 11" or "Wednesday, Oct 14".
	DayLabel(days int, now time.Time) string
}

// CarrierRule is the staff-configured carrier rate (ADR 0005 §3): what a package a carrier ships costs before any
// live quote (the fallback fee, which is what orders charge), and the goods subtotal from which it is free (0 = never).
type CarrierRule struct {
	RuleID         string
	Version        int64
	FallbackMinor  int64
	FreeAboveMinor int64
}

// CarrierRuleSetter is implemented by estimators whose carrier estimate follows the staff rule.
type CarrierRuleSetter interface {
	// SetCarrierRule replaces the carrier rule (nil: back to the configured flat policy).
	SetCarrierRule(r *CarrierRule)
}

type estimatorImpl struct {
	p    DeliveryPolicy
	rule atomic.Pointer[CarrierRule]
}

var (
	_ Estimator         = (*estimatorImpl)(nil)
	_ CarrierRuleSetter = (*estimatorImpl)(nil)
)

func (e *estimatorImpl) SetCarrierRule(r *CarrierRule) { e.rule.Store(r) }

// shipping is the carrier estimate for a package: the staff rule's fee, else the configured flat fee.
func (e *estimatorImpl) shipping() int64 {
	if r := e.rule.Load(); r != nil {
		return r.FallbackMinor
	}
	return e.p.SellerShippingMinor
}

// freeAbove is the subtotal from which carrier shipping is free (0 = never).
func (e *estimatorImpl) freeAbove() int64 {
	if r := e.rule.Load(); r != nil {
		return r.FreeAboveMinor
	}
	return e.p.FreeShippingThresholdMinor
}

// NewEstimator returns the default Estimator.
func NewEstimator(p DeliveryPolicy) Estimator {
	if p.Location == nil {
		p.Location = time.UTC
	}
	return &estimatorImpl{p: p}
}

func (e *estimatorImpl) days(fulfilment string, days int) int {
	if days > 0 {
		return days
	}
	switch fulfilment {
	case FulfilledByAfroTransact:
		return DefaultAfroTransactDays
	case AfroTransactDelivery:
		return DefaultLocalDeliveryDays
	}
	return DefaultSellerDays
}

func (e *estimatorImpl) Promise(fulfilment string, days int, now time.Time) DeliveryPromise {
	d := e.days(fulfilment, days)
	if fulfilment == AfroTransactDelivery {
		// Priced by road distance at checkout (payments' delivery pricing), so no fee is promised here.
		return DeliveryPromise{Days: d, Label: e.DayLabel(d, now), Prefix: "AfroTransact Delivery", Free: false, FeeMinor: 0}
	}
	if fulfilment == FulfilledByAfroTransact {
		express := e.DayLabel(1, now)
		cutoff := cutoffNote(now.In(e.p.Location))
		return DeliveryPromise{Days: d, Label: e.DayLabel(d, now), Prefix: "FREE delivery", Free: true, FeeMinor: 0,
			ExpressLabel: &express, Cutoff: &cutoff}
	}
	return DeliveryPromise{Days: d, Label: e.DayLabel(d, now), Prefix: "Delivery", Free: false, FeeMinor: e.shipping()}
}

func (e *estimatorImpl) Options(fulfilment string, days int, subtotalMinor int64, now time.Time) []DeliveryOption {
	d := e.days(fulfilment, days)
	if fulfilment == AfroTransactDelivery {
		label := LocalDeliveryOptionLabel
		fee := int64(0)
		free := false
		if strings.EqualFold(e.p.PlatformDeliveryMode, TariffFlat) && e.p.PlatformDeliveryFlatMinor > 0 {
			fee = e.p.PlatformDeliveryFlatMinor
			label = fmt.Sprintf("AfroTransact Delivery · %s flat delivery", Money(fee))
		} else if strings.EqualFold(e.p.PlatformDeliveryMode, TariffPerWeight) && (e.p.PlatformDeliveryBaseMinor > 0 || e.p.PlatformDeliveryPerLbMinor > 0) {
			label = fmt.Sprintf("AfroTransact Delivery · %s + %s/lb", Money(e.p.PlatformDeliveryBaseMinor), Money(e.p.PlatformDeliveryPerLbMinor))
		}
		return []DeliveryOption{{Key: "afrotransact_delivery", Label: label, ETA: e.DayLabel(d, now), FeeMinor: fee, Free: free}}
	}
	isAT := fulfilment == FulfilledByAfroTransact
	free := isAT || (e.freeAbove() > 0 && subtotalMinor >= e.freeAbove())
	carrier := DeliveryOption{Key: "carrier", ETA: e.DayLabel(d, now), Free: free}
	if isAT {
		carrier.Label = "UPS Ground or USPS Ground Advantage"
	} else {
		carrier.Label = "USPS Ground Advantage"
	}
	if !free {
		carrier.FeeMinor = e.shipping()
	}
	if r := e.rule.Load(); r != nil && !isAT {
		source := "CARRIER_FALLBACK"
		if free {
			source = "RULE"
		}
		carrier.FeeRule = &FeeRuleRef{Method: "CARRIER", RuleID: r.RuleID, RuleVersion: r.Version,
			AmountMinor: carrier.FeeMinor, Source: source}
	}
	out := []DeliveryOption{carrier}
	if isAT {
		out = append(out, DeliveryOption{Key: "express", Label: "UPS Next Day Air", ETA: e.DayLabel(1, now), FeeMinor: ExpressFeeMinor})
	}
	for i := range out {
		price := "FREE"
		if !out[i].Free {
			price = Money(out[i].FeeMinor)
		}
		out[i].Label = price + " · " + out[i].Label
	}
	return out
}

func (e *estimatorImpl) Nudge(fulfilment string, subtotalMinor int64) *ShippingNudge {
	t := e.freeAbove()
	if fulfilment == FulfilledByAfroTransact || fulfilment == AfroTransactDelivery || t <= 0 || subtotalMinor <= 0 || subtotalMinor >= t {
		return nil
	}
	return &ShippingNudge{RemainingMinor: t - subtotalMinor, ThresholdMinor: t}
}

func (e *estimatorImpl) DayLabel(days int, now time.Time) string {
	if days <= 0 {
		return "Today"
	}
	d := AddBusinessDays(now.In(e.p.Location), days)
	if days == 1 {
		return "Tomorrow, " + d.Format("Jan 2")
	}
	return d.Format("Monday, Jan 2")
}

// AddBusinessDays adds n delivery days, skipping Sundays (carriers deliver Monday–Saturday).
func AddBusinessDays(t time.Time, n int) time.Time {
	for n > 0 {
		t = t.AddDate(0, 0, 1)
		if t.Weekday() != time.Sunday {
			n--
		}
	}
	return t
}

func cutoffNote(now time.Time) string {
	end := time.Date(now.Year(), now.Month(), now.Day(), 23, 59, 0, 0, now.Location())
	mins := max(1, int(end.Sub(now).Minutes()))
	return fmt.Sprintf("Order within %d hrs %d mins.", mins/60, mins%60)
}

// LocalDeliveryHeading is the package heading of AfroTransact Delivery from the seller's store.
func LocalDeliveryHeading(city string) string {
	if city == "" {
		return "AfroTransact Delivery from the seller's store"
	}
	return "AfroTransact Delivery from " + city
}

// Money renders minor units as dollars, e.g. 599 → "$5.99".
func Money(minor int64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return fmt.Sprintf("%s$%d.%02d", sign, minor/100, minor%100)
}
