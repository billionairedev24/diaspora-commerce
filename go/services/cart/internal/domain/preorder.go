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
	// KindWindow is a pre-order window delivery-runs sells from; KindRun a delivery run staff scheduled (S261), where
	// the package's WindowID is the run's id and ClosesAt its order cutoff.
	KindWindow = "WINDOW"
	KindRun    = "RUN"
)

// ErrInvalidFulfilment is returned for a pre-order fulfilment other than DOOR or PICKUP, or one the window does not
// offer.
var ErrInvalidFulfilment = errors.New("pre-order fulfilment must be DOOR or PICKUP")

// Location is where the shopper's order is delivered (the BFF passes it from the delivery address).
type Location struct {
	Country string
	Region  string
	City    string
	// PostalCode is the 5-digit ZIP (optional): delivery runs are found by ZIP, pre-order windows by region.
	PostalCode string
}

// Known reports whether a location was given.
func (l Location) Known() bool {
	return l.Country != "" || l.Region != "" || l.City != "" || l.PostalCode != ""
}

// FeeRule is the staff delivery fee rule behind a fee (ADR 0005 §3): the method, the rule and its version, the distance
// band applied and where the amount came from. The cart shows it next to the fee; orders keep it with the order.
type FeeRule struct {
	Method        string   `json:"method"`
	RuleID        string   `json:"ruleId,omitempty"`
	RuleVersion   int64    `json:"ruleVersion"`
	Currency      string   `json:"currency,omitempty"`
	AmountMinor   int64    `json:"amountMinor"`
	Source        string   `json:"source,omitempty"`
	Band          string   `json:"band,omitempty"`
	DistanceMiles *float64 `json:"distanceMiles,omitempty"`
}

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
	// DoorFeeRule / PickupFeeRule are the rules behind the two fees (nil when delivery-runs sent none).
	DoorFeeRule   *FeeRule `json:"doorFeeRule,omitempty"`
	PickupFeeRule *FeeRule `json:"pickupFeeRule,omitempty"`
	// Kind is WINDOW (default) or RUN; MinOrders and OrdersSoFar are the run's minimum progress ("7 of 10 orders").
	Kind        string `json:"kind,omitempty"`
	MinOrders   int    `json:"minOrders,omitempty"`
	OrdersSoFar int    `json:"ordersSoFar,omitempty"`
}

// AllPaymentMethods is what a window allows when its payment setup names none.
var AllPaymentMethods = []string{"CARD", "WALLET", "ACH"}

// PreorderOption is one fulfilment the window offers with its fee.
type PreorderOption struct {
	Fulfilment string `json:"fulfilment"`
	FeeMinor   int64  `json:"feeMinor"`
	Currency   string `json:"currency"`
	// Rule is the delivery fee rule that priced this option (absent when none was sent).
	Rule *FeeRule `json:"rule,omitempty"`
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
	// FeeRule is the rule behind FeeMinor: what the shopper is shown at checkout and what the order records.
	FeeRule *FeeRule `json:"feeRule,omitempty"`
	// Kind is WINDOW or RUN (a delivery run staff scheduled: WindowID is then the run's id and ClosesAt its cutoff).
	Kind string `json:"kind"`
	// MinOrders / OrdersSoFar / MinimumMet are a run's honest minimum progress: orders holding a slot now, counting
	// the shopper's own only once placed. Zero for a window (its minimum is staff-side).
	MinOrders   int  `json:"minOrders"`
	OrdersSoFar int  `json:"ordersSoFar"`
	MinimumMet  bool `json:"minimumMet"`
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
		Locked: !strings.EqualFold(p.Stage, StageOpen), Options: []PreorderOption{}, Kind: KindWindow}
	if strings.EqualFold(p.Kind, KindRun) {
		pp.Kind, pp.MinOrders, pp.OrdersSoFar = KindRun, p.MinOrders, p.OrdersSoFar
		pp.MinimumMet = p.MinOrders <= 0 || p.OrdersSoFar >= p.MinOrders
	}
	if p.Door {
		pp.Options = append(pp.Options, PreorderOption{Fulfilment: FulfilmentDoor, FeeMinor: p.DoorFeeMinor, Currency: currency,
			Rule: p.DoorFeeRule})
	}
	if p.Pickup {
		pp.Options = append(pp.Options, PreorderOption{Fulfilment: FulfilmentPickup, FeeMinor: p.PickupFeeMinor, Currency: currency,
			Rule: p.PickupFeeRule})
	}
	for _, o := range pp.Options {
		if o.Fulfilment == chosen {
			pp.Fulfilment, pp.FeeMinor, pp.FeeRule = o.Fulfilment, o.FeeMinor, o.Rule
		}
	}
	if pp.Fulfilment == "" && len(pp.Options) > 0 {
		pp.Fulfilment, pp.FeeMinor, pp.FeeRule = pp.Options[0].Fulfilment, pp.Options[0].FeeMinor, pp.Options[0].Rule
	}
	return pp
}

// OpenRun is an open delivery run covering the shopper's ZIP (delivery-runs, S261). Money is in minor units.
type OpenRun struct {
	RunID       string
	Town        string
	CutoffAt    string
	DeliversOn  string
	TimeZone    string
	FeeMinor    int64
	Currency    string
	Door        bool
	Pickup      bool
	FeeRule     *FeeRule
	MinOrders   int
	OrdersSoFar int
	Full        bool
}

// RunLine is an open run as the pre-order line of a variant that AfroTransact holds in stock: the run's own id,
// cutoff, date and fee, with its minimum progress.
func RunLine(r OpenRun) PreorderLine {
	name := "Delivery run"
	if r.Town != "" {
		name = r.Town + " delivery run"
	}
	l := PreorderLine{Preorder: true, WindowID: r.RunID, WindowName: name, Stage: StageOpen, DeliversOn: r.DeliversOn,
		ClosesAt: r.CutoffAt, Door: r.Door, Pickup: r.Pickup, Currency: r.Currency, TimeZone: r.TimeZone,
		Kind: KindRun, MinOrders: r.MinOrders, OrdersSoFar: r.OrdersSoFar}
	if r.Door {
		l.DoorFeeMinor, l.DoorFeeRule = r.FeeMinor, r.FeeRule
	}
	if r.Pickup {
		l.PickupFeeMinor, l.PickupFeeRule = r.FeeMinor, r.FeeRule
	}
	return l
}

// HeldByAfroTransact reports whether a line is AfroTransact's own stock (Retail or FBA), which delivery runs carry;
// a seller's own goods and AfroTransact Delivery from a seller's store are not.
func HeldByAfroTransact(o Offer) bool {
	f := strings.ToUpper(strings.TrimSpace(o.Fulfilment))
	return f != "" && f != "SELLER" && f != FulfilmentAfroTransactDelivery
}
