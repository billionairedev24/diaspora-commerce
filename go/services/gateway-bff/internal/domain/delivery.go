package domain

import (
	"fmt"
	"sync/atomic"
	"time"
)

// DeliveryPolicy holds the fallback delivery rules. The delivery fee itself is NOT configured here: it is the staff-edited
// shipping settings record owned by orders (listing rules D16), read through ShippingSettingsSetter. Only the carrier fee
// of seller-shipped packages (off at launch) and the time zone live here.
type DeliveryPolicy struct {
	SellerShippingMinor        int64
	FreeShippingThresholdMinor int64
	Location                   *time.Location
}

// Default business-day estimates when neither the promise service nor search carries one.
const (
	DefaultAfroTransactDays = 2
	DefaultSellerDays       = 4
	// DefaultLocalDeliveryDays is AfroTransact collecting from a seller's store and delivering (the buy box uses 1 day).
	DefaultLocalDeliveryDays = 1
)

// Delivery option keys. Orders accepts exactly these (plus carrier only while the staff switch is on).
const (
	OptionDelivery      = "afrotransact_delivery"
	OptionPickup        = "pickup"
	OptionCarrier       = "carrier"
	OptionQuoteRequired = "quote_required"
)

// Shopper-facing option labels (launch shipping, listing rules section 3). No carrier is ever named.
const (
	LabelDeliveryByAfroTransact = "Delivery"
	LabelStoreDelivery          = "Delivery"
	LabelPickupPoint            = "Pickup"
	LabelStorePickup            = "Pickup"
	LabelSellerShipping         = "Standard shipping from the seller"
	LabelQuoteRequired          = "Too far for standard delivery. Ask us for a quote"
	// LabelFeeUnknown replaces a fee that cannot be read right now: never $0.
	LabelFeeUnknown = "Delivery fee shown at checkout"
)

// LocalDeliveryOptionLabel is the AfroTransact option of a seller-store package.
const LocalDeliveryOptionLabel = LabelStoreDelivery

// ShippingSettings is the staff-set shipping charge (orders' shipping_settings record, listing rules D16). DeliveryFee is
// the one formula; orders' ShippingSettings.deliveryFee is the same and tools/checkout/shipping-fee-vectors.json proves it.
type ShippingSettings struct {
	Currency                     string
	Version                      int64
	FlatFeeMinor                 int64
	FreeAboveMinor               *int64 // nil = never free
	SellerCarrierShippingEnabled bool
}

// DeliveryFee is the delivery charge of one package whose items cost itemsMinor.
func (s ShippingSettings) DeliveryFee(itemsMinor int64) int64 {
	if s.FreeAboveMinor != nil && itemsMinor >= *s.FreeAboveMinor {
		return 0
	}
	return s.FlatFeeMinor
}

// ShippingSettingsSetter is implemented by estimators whose delivery fee follows the staff shipping settings.
type ShippingSettingsSetter interface {
	// SetShippingSettings replaces the settings in force (nil is ignored: the last known value stays).
	SetShippingSettings(s *ShippingSettings)
	// ShippingSettings is the settings in force; false when none was ever read.
	ShippingSettings() (ShippingSettings, bool)
}

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
	p        DeliveryPolicy
	rule     atomic.Pointer[CarrierRule]
	settings atomic.Pointer[ShippingSettings]
}

var (
	_ Estimator              = (*estimatorImpl)(nil)
	_ CarrierRuleSetter      = (*estimatorImpl)(nil)
	_ ShippingSettingsSetter = (*estimatorImpl)(nil)
)

func (e *estimatorImpl) SetCarrierRule(r *CarrierRule) { e.rule.Store(r) }

func (e *estimatorImpl) SetShippingSettings(s *ShippingSettings) {
	if s != nil {
		e.settings.Store(s)
	}
}

func (e *estimatorImpl) ShippingSettings() (ShippingSettings, bool) {
	if s := e.settings.Load(); s != nil {
		return *s, true
	}
	return ShippingSettings{}, false
}

// carrierOn is the staff switch for seller-shipped carrier options (off at launch, and while unknown).
func (e *estimatorImpl) carrierOn() bool {
	s := e.settings.Load()
	return s != nil && s.SellerCarrierShippingEnabled
}

// shipping is the carrier estimate for a package a seller ships (switch on): the staff rule's fee, else the configured fee.
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

// sellerCarrier is true for a seller-shipped package offered the carrier option (staff switch on).
func (e *estimatorImpl) sellerCarrier(fulfilment string) bool {
	return fulfilment != FulfilledByAfroTransact && fulfilment != AfroTransactDelivery && e.carrierOn()
}

func deliveryLabel(fulfilment string) string {
	if fulfilment == FulfilledByAfroTransact {
		return LabelDeliveryByAfroTransact
	}
	return LabelStoreDelivery
}

func (e *estimatorImpl) Promise(fulfilment string, days int, now time.Time) DeliveryPromise {
	d := e.days(fulfilment, days)
	if e.sellerCarrier(fulfilment) {
		return DeliveryPromise{Days: d, Label: e.DayLabel(d, now), Prefix: "Delivery", Free: false, FeeMinor: e.shipping()}
	}
	prefix := deliveryLabel(fulfilment)
	out := DeliveryPromise{Days: d, Label: e.DayLabel(d, now), Prefix: prefix}
	if fulfilment == FulfilledByAfroTransact {
		cutoff := cutoffNote(now.In(e.p.Location))
		out.Cutoff = &cutoff
	}
	if s := e.settings.Load(); s != nil {
		// One flat fee (free above the optional threshold, which a single item cannot know): the page shows the flat fee.
		out.FeeMinor, out.Free = s.FlatFeeMinor, s.FlatFeeMinor == 0
	}
	return out
}

func (e *estimatorImpl) Options(fulfilment string, days int, subtotalMinor int64, now time.Time) []DeliveryOption {
	d := e.days(fulfilment, days)
	if e.sellerCarrier(fulfilment) {
		free := e.freeAbove() > 0 && subtotalMinor >= e.freeAbove()
		carrier := DeliveryOption{Key: OptionCarrier, Label: LabelSellerShipping, ETA: e.DayLabel(d, now), Free: free}
		if !free {
			carrier.FeeMinor = e.shipping()
		}
		if r := e.rule.Load(); r != nil {
			source := "CARRIER_FALLBACK"
			if free {
				source = "RULE"
			}
			carrier.FeeRule = &FeeRuleRef{Method: "CARRIER", RuleID: r.RuleID, RuleVersion: r.Version,
				AmountMinor: carrier.FeeMinor, Source: source}
		}
		// A seller package can still be delivered by AfroTransact, at the flat fee.
		return []DeliveryOption{carrier, e.deliveryOption(fulfilment, d, subtotalMinor, now)}
	}
	return []DeliveryOption{e.deliveryOption(fulfilment, d, subtotalMinor, now)}
}

// deliveryOption is Delivery by AfroTransact (or AfroTransact collecting from the store and delivering): the one flat
// fee. When the settings were never read the fee is flagged unknown instead of showing 0.
func (e *estimatorImpl) deliveryOption(fulfilment string, days int, subtotalMinor int64, now time.Time) DeliveryOption {
	o := DeliveryOption{Key: OptionDelivery, Label: deliveryLabel(fulfilment), ETA: e.DayLabel(days, now)}
	s := e.settings.Load()
	if s == nil {
		o.FeeUnknown, o.Label = true, LabelFeeUnknown
		return o
	}
	o.FeeMinor = s.DeliveryFee(subtotalMinor)
	o.Free = o.FeeMinor == 0
	return o
}

func (e *estimatorImpl) Nudge(fulfilment string, subtotalMinor int64) *ShippingNudge {
	if subtotalMinor <= 0 {
		return nil
	}
	t := int64(0)
	if e.sellerCarrier(fulfilment) {
		t = e.freeAbove()
	} else if s := e.settings.Load(); s != nil && s.FreeAboveMinor != nil {
		t = *s.FreeAboveMinor
	}
	if t <= 0 || subtotalMinor >= t {
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
