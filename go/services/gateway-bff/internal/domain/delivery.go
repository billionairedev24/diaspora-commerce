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

// Delivery option keys. Orders accepts exactly these (plus carrier only while the staff switch is on).
const (
	OptionDelivery      = "afrotransact_delivery"
	OptionPickup        = "pickup"
	OptionCarrier       = "carrier"
	OptionQuoteRequired = "quote_required"
)

// Shopper-facing option labels (launch shipping, listing rules section 3). No carrier is ever named.
const (
	LabelDelivery               = "Delivery"
	LabelDeliveryByAfroTransact = LabelDelivery
	LabelStoreDelivery          = LabelDelivery
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

// Estimator builds the delivery method, fee and options of an offer or package. It NEVER produces a date: no constant, no
// "today plus N business days". A promised window comes only from the promise service for real inputs (the seller's
// handling time, the ship-from ZIP, the shopper's ZIP) through Arrival; until one is applied (DeliveryPromise.WithArrival,
// DeliveryOption.WithArrival) the promise has basis NONE and an empty label, and nothing may show a date for it.
type Estimator interface {
	// Promise is the method and fee of delivering an offer, with no date (basis NONE).
	Promise(fulfilment string) DeliveryPromise
	// Options lists the delivery options for a package with the given (selected) subtotal, none of them dated.
	Options(fulfilment string, subtotalMinor int64) []DeliveryOption
	// Nudge returns the free-shipping nudge for seller-shipped packages under the threshold.
	Nudge(fulfilment string, subtotalMinor int64) *ShippingNudge
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

func (e *estimatorImpl) Promise(fulfilment string) DeliveryPromise {
	if e.sellerCarrier(fulfilment) {
		return DeliveryPromise{Prefix: LabelDelivery, Basis: ArrivalNone, Reason: ArrivalNoOrigin, Free: false, FeeMinor: e.shipping()}
	}
	out := DeliveryPromise{Prefix: deliveryLabel(fulfilment), Basis: ArrivalNone, Reason: ArrivalNoOrigin}
	if s := e.settings.Load(); s != nil {
		// One flat fee (free above the optional threshold, which a single item cannot know): the page shows the flat fee.
		out.FeeMinor, out.Free = s.FlatFeeMinor, s.FlatFeeMinor == 0
	}
	return out
}

func (e *estimatorImpl) Options(fulfilment string, subtotalMinor int64) []DeliveryOption {
	if e.sellerCarrier(fulfilment) {
		free := e.freeAbove() > 0 && subtotalMinor >= e.freeAbove()
		carrier := DeliveryOption{Key: OptionCarrier, Label: LabelSellerShipping, Free: free}
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
		return []DeliveryOption{carrier, e.deliveryOption(fulfilment, subtotalMinor)}
	}
	return []DeliveryOption{e.deliveryOption(fulfilment, subtotalMinor)}
}

// deliveryOption is the Delivery option of a package (one flat fee). When the settings were never read the fee is flagged
// unknown instead of showing 0. It carries no date.
func (e *estimatorImpl) deliveryOption(fulfilment string, subtotalMinor int64) DeliveryOption {
	o := DeliveryOption{Key: OptionDelivery, Label: deliveryLabel(fulfilment)}
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

// LocalDeliveryHeading is the package heading of a delivery from the seller's store: plain "Delivery", never wording that
// says AfroTransact collects or delivers.
func LocalDeliveryHeading(city string) string {
	if city == "" {
		return LabelDelivery
	}
	return LabelDelivery + " from " + city
}

// Money renders minor units as dollars, e.g. 599 → "$5.99".
func Money(minor int64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return fmt.Sprintf("%s$%d.%02d", sign, minor/100, minor%100)
}
