package domain

import (
	"errors"
	"strings"
)

// Pre-order fulfilments (S272): door delivery by the window's run or collection at its pickup point.
const (
	FulfilmentDoor   = "DOOR"
	FulfilmentPickup = "PICKUP"
	// FulfilmentPreorder is the package fulfilment of a pre-order window's lines.
	FulfilmentPreorder = "PREORDER"
	// PreorderPackagePrefix prefixes a pre-order package's id (preorder:<windowId>), also the checkout selection key.
	PreorderPackagePrefix = "preorder:"
	// StageOpen is the only stage whose lines can be checked out.
	StageOpen = "OPEN"
)

// ErrInvalidFulfilment is returned for a pre-order fulfilment other than DOOR or PICKUP, or one the window does not
// offer.
var ErrInvalidFulfilment = errors.New("pre-order fulfilment must be DOOR or PICKUP")

// Location is where the shopper's order is delivered (the BFF passes it from the delivery address).
type Location struct {
	Country string
	Region  string
	City    string
}

// Known reports whether a location was given.
func (l Location) Known() bool { return l.Country != "" || l.Region != "" || l.City != "" }

// PreorderLine is delivery-runs' answer for one product at the shopper's location. Money is in minor units.
type PreorderLine struct {
	Preorder       bool     `json:"preorder"`
	WindowID       string   `json:"windowId"`
	WindowCode     string   `json:"windowCode,omitempty"`
	WindowName     string   `json:"name,omitempty"`
	Stage          string   `json:"stage"`
	DeliversOn     string   `json:"deliversOn,omitempty"`
	ClosesAt       string   `json:"closesAt,omitempty"`
	OpensAt        string   `json:"opensAt,omitempty"`
	Door           bool     `json:"door"`
	Pickup         bool     `json:"pickup"`
	DoorFeeMinor   int64    `json:"doorFeeMinor"`
	PickupFeeMinor int64    `json:"pickupFeeMinor"`
	Currency       string   `json:"currency"`
	AllowedMethods []string `json:"allowedMethods,omitempty"`
	PickupWindow   string   `json:"pickupWindow,omitempty"`
	TimeZone       string   `json:"timeZone,omitempty"`
}

// AllPaymentMethods is what a window allows when its payment setup names none.
var AllPaymentMethods = []string{"CARD", "WALLET", "ACH"}

// PreorderOption is one fulfilment the window offers with its fee.
type PreorderOption struct {
	Fulfilment string `json:"fulfilment"`
	FeeMinor   int64  `json:"feeMinor"`
	Currency   string `json:"currency"`
}

// PackagePreorder is the pre-order part of a cart package (the storefront's CartPreorder): the window, its stage, the
// window's payment setup (door / pickup, fees in minor units, currency, pickup hours, allowed methods) and the door /
// pickup choice.
type PackagePreorder struct {
	WindowID       string           `json:"windowId"`
	Code           string           `json:"code"`
	Name           string           `json:"name"`
	DeliversOn     string           `json:"deliversOn"`
	Stage          string           `json:"stage"`
	OpensAt        string           `json:"opensAt"`
	ClosesAt       string           `json:"closesAt"`
	TimeZone       string           `json:"timeZone"`
	Door           bool             `json:"door"`
	Pickup         bool             `json:"pickup"`
	DoorFeeMinor   int64            `json:"doorFeeMinor"`
	PickupFeeMinor int64            `json:"pickupFeeMinor"`
	Currency       string           `json:"currency"`
	PickupWindow   string           `json:"pickupWindow"`
	AllowedMethods []string         `json:"allowedMethods"`
	Fulfilment     string           `json:"fulfilment"`
	Options        []PreorderOption `json:"options"`
	// FeeMinor is the fee of the chosen fulfilment.
	FeeMinor int64 `json:"feeMinor"`
	// Locked is true when the window is not open: its lines cannot be checked out (upcoming or closed).
	Locked bool `json:"locked"`
}

// SetPreorderFulfilment remembers the shopper's door / pickup choice for a window.
func (c *Cart) SetPreorderFulfilment(windowID, fulfilment string) error {
	f := strings.ToUpper(strings.TrimSpace(fulfilment))
	if strings.TrimSpace(windowID) == "" || (f != FulfilmentDoor && f != FulfilmentPickup) {
		return ErrInvalidFulfilment
	}
	if c.PreorderFulfilment == nil {
		c.PreorderFulfilment = map[string]string{}
	}
	c.PreorderFulfilment[strings.TrimSpace(windowID)] = f
	return nil
}

// preorderOf builds a package's pre-order part, applying the shopper's choice when the window offers it.
func preorderOf(p PreorderLine, chosen string) *PackagePreorder {
	currency := p.Currency
	if currency == "" {
		currency = "USD"
	}
	methods := p.AllowedMethods
	if len(methods) == 0 {
		methods = AllPaymentMethods
	}
	tz := p.TimeZone
	if tz == "" {
		tz = "America/Chicago"
	}
	pp := &PackagePreorder{WindowID: p.WindowID, Code: p.WindowCode, Name: p.WindowName,
		DeliversOn: p.DeliversOn, Stage: p.Stage, ClosesAt: p.ClosesAt, OpensAt: p.OpensAt, TimeZone: tz,
		Door: p.Door, Pickup: p.Pickup, DoorFeeMinor: p.DoorFeeMinor, PickupFeeMinor: p.PickupFeeMinor,
		Currency: currency, PickupWindow: p.PickupWindow, AllowedMethods: methods,
		Locked: !strings.EqualFold(p.Stage, StageOpen), Options: []PreorderOption{}}
	if p.Door {
		pp.Options = append(pp.Options, PreorderOption{Fulfilment: FulfilmentDoor, FeeMinor: p.DoorFeeMinor, Currency: currency})
	}
	if p.Pickup {
		pp.Options = append(pp.Options, PreorderOption{Fulfilment: FulfilmentPickup, FeeMinor: p.PickupFeeMinor, Currency: currency})
	}
	for _, o := range pp.Options {
		if o.Fulfilment == chosen {
			pp.Fulfilment, pp.FeeMinor = o.Fulfilment, o.FeeMinor
		}
	}
	if pp.Fulfilment == "" && len(pp.Options) > 0 {
		pp.Fulfilment, pp.FeeMinor = pp.Options[0].Fulfilment, pp.Options[0].FeeMinor
	}
	return pp
}
