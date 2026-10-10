package domain

import (
	"fmt"
	"time"
)

// DeliveryPolicy holds the commercial delivery rules used when the promise service has no answer.
type DeliveryPolicy struct {
	SellerShippingMinor        int64
	FreeShippingThresholdMinor int64
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

type estimatorImpl struct{ p DeliveryPolicy }

var _ Estimator = (*estimatorImpl)(nil)

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
	return DeliveryPromise{Days: d, Label: e.DayLabel(d, now), Prefix: "Delivery", Free: false, FeeMinor: e.p.SellerShippingMinor}
}

func (e *estimatorImpl) Options(fulfilment string, days int, subtotalMinor int64, now time.Time) []DeliveryOption {
	d := e.days(fulfilment, days)
	if fulfilment == AfroTransactDelivery {
		return []DeliveryOption{{Key: "afrotransact_delivery", Label: LocalDeliveryOptionLabel, ETA: e.DayLabel(d, now)}}
	}
	isAT := fulfilment == FulfilledByAfroTransact
	free := isAT || (e.p.FreeShippingThresholdMinor > 0 && subtotalMinor >= e.p.FreeShippingThresholdMinor)
	carrier := DeliveryOption{Key: "carrier", ETA: e.DayLabel(d, now), Free: free}
	if isAT {
		carrier.Label = "UPS Ground or USPS Ground Advantage"
	} else {
		carrier.Label = "USPS Ground Advantage"
	}
	if !free {
		carrier.FeeMinor = e.p.SellerShippingMinor
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
	t := e.p.FreeShippingThresholdMinor
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
