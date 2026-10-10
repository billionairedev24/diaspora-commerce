package domain

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrInvalid marks a request we cannot plan (bad ZIP, unknown FC, malformed clock…).
var ErrInvalid = errors.New("invalid promise request")

// ErrNotFound is returned for unknown promise ids.
var ErrNotFound = errors.New("promise not found")

// OriginKind distinguishes our fulfilment centres from sellers shipping themselves.
type OriginKind string

// Origin kinds.
const (
	OriginFC     OriginKind = "FC"
	OriginSeller OriginKind = "SELLER"
)

// MethodType is how the order reaches the customer.
type MethodType string

// Fulfilment methods.
const (
	MethodCarrier      MethodType = "CARRIER"
	MethodAfroDelivery MethodType = "AFROTRANSACT_DELIVERY"
	MethodPickup       MethodType = "PICKUP"
)

// ServiceLevel is the carrier service class.
type ServiceLevel string

// Carrier service levels.
const (
	LevelGround    ServiceLevel = "GROUND"
	LevelTwoDay    ServiceLevel = "TWO_DAY"
	LevelOvernight ServiceLevel = "OVERNIGHT"
)

// Clock is a local wall-clock time.
type Clock struct{ Hour, Minute int }

// ParseClock parses "HH:MM".
func ParseClock(s string) (Clock, error) {
	t, err := time.Parse("15:04", strings.TrimSpace(s))
	if err != nil {
		return Clock{}, fmt.Errorf("%w: clock %q must be HH:MM", ErrInvalid, s)
	}
	return Clock{t.Hour(), t.Minute()}, nil
}

// On returns the instant of the clock on d in loc.
func (c Clock) On(d Day, loc *time.Location) time.Time { return d.At(loc, c.Hour, c.Minute) }

// OpeningHours is one weekday's opening interval at a pickup location.
type OpeningHours struct {
	Weekday time.Weekday `json:"weekday"`
	Open    string       `json:"open"`
	Close   string       `json:"close"`
}

// Origin is where a parcel leaves from, with its working rules resolved.
type Origin struct {
	Kind          OriginKind
	ID            string
	ZIP           string
	Location      *time.Location
	Cutoff        Clock // orders after the cutoff are handled on the next work day
	HandlingDays  int   // sellers: business days to prepare the parcel
	WorksSaturday bool
}

// ServiceRule is how a carrier service level behaves.
type ServiceRule struct {
	MaxDays          int  // caps transit (2 for TWO_DAY, 1 for OVERNIGHT); 0 = table transit
	SaturdayDelivery bool // carrier delivers this service on Saturdays
}

// Transit is a business-day transit range.
type Transit struct {
	MinDays int    `json:"minDays"`
	MaxDays int    `json:"maxDays"`
	Source  string `json:"source"` // table | distance
}

// DeliveryZone is AfroTransact Delivery eligibility for one origin/destination pair.
type DeliveryZone struct {
	SameDay          bool
	NextDay          bool
	SameDayCutoff    Clock
	DeliversSaturday bool
}

// PickupWindow describes when a pickup location can hand over orders.
type PickupWindow struct {
	ReadyHours int
	Location   *time.Location
	Hours      []OpeningHours
}

// PlanInput is everything the planner needs for one offer.
type PlanInput struct {
	Now      time.Time
	Origin   Origin
	Method   MethodType
	Level    ServiceLevel
	Service  ServiceRule
	Transit  Transit
	Zone     DeliveryZone
	Pickup   PickupWindow
	Customer *time.Location
}

// OriginRef identifies an origin in a promise.
type OriginRef struct {
	Kind OriginKind `json:"kind"`
	ID   string     `json:"id"`
}

// Promise is a delivery (or pickup) promise for one offer to one ZIP.
type Promise struct {
	ID             string       `json:"id"`
	OfferID        string       `json:"offerId"`
	Origin         OriginRef    `json:"origin"`
	Method         MethodType   `json:"method"`
	ServiceLevel   ServiceLevel `json:"serviceLevel,omitempty"`
	DestinationZIP string       `json:"destinationZip"`
	Available      bool         `json:"available"`
	Reason         string       `json:"reason,omitempty"`
	ShipDate       *Day         `json:"shipDate,omitempty"`
	EarliestDate   *Day         `json:"earliestDate,omitempty"`
	LatestDate     *Day         `json:"latestDate,omitempty"`
	CutoffAt       *time.Time   `json:"cutoffAt,omitempty"`
	CutoffLabel    string       `json:"cutoffLabel,omitempty"`
	ReadyAt        *time.Time   `json:"readyAt,omitempty"`
	Label          string       `json:"label,omitempty"`
	Transit        *Transit     `json:"transit,omitempty"`
	ComputedAt     time.Time    `json:"computedAt"`
	ExpiresAt      time.Time    `json:"expiresAt"`
	// ValidUntil bounds how long the computed dates can be reused (next hour or the cutoff, whichever is first).
	ValidUntil time.Time `json:"validUntil"`
}

// Unavailable builds a promise that cannot be offered.
func Unavailable(reason string) Promise { return Promise{Available: false, Reason: reason} }

// Planner computes promises on a business calendar.
type Planner interface {
	Plan(in PlanInput) Promise
}

type plannerImpl struct{ cal Calendar }

var _ Planner = (*plannerImpl)(nil)

// NewPlanner returns a Planner on cal.
func NewPlanner(cal Calendar) Planner { return &plannerImpl{cal: cal} }

// accept returns the day the origin starts handling an order placed at now, and that day's cutoff instant.
func (p *plannerImpl) accept(o Origin, now time.Time) (Day, time.Time) {
	local := now.In(o.Location)
	today := DayOf(local)
	if cut := o.Cutoff.On(today, o.Location); p.cal.IsWorkDay(today, o.WorksSaturday) && local.Before(cut) {
		return today, cut
	}
	next := p.cal.NextWorkDay(today, o.WorksSaturday)
	return next, o.Cutoff.On(next, o.Location)
}

func nextHour(now time.Time) time.Time { return now.Truncate(time.Hour).Add(time.Hour) }

func (p *plannerImpl) Plan(in PlanInput) Promise {
	var out Promise
	switch in.Method {
	case MethodCarrier:
		out = p.carrier(in)
	case MethodAfroDelivery:
		out = p.afroDelivery(in)
	case MethodPickup:
		out = p.pickup(in)
	default:
		return Unavailable("unknown fulfilment method")
	}
	out.Method, out.ComputedAt = in.Method, in.Now.UTC()
	if in.Method == MethodCarrier {
		out.ServiceLevel = in.Level
	}
	if out.ValidUntil.IsZero() {
		out.ValidUntil = nextHour(in.Now)
	}
	if out.CutoffAt != nil && out.CutoffAt.After(in.Now) && out.CutoffAt.Before(out.ValidUntil) {
		out.ValidUntil = *out.CutoffAt
	}
	return out
}

func ptr[T any](v T) *T { return &v }

func (p *plannerImpl) carrier(in PlanInput) Promise {
	accepted, cutoff := p.accept(in.Origin, in.Now)
	ship := p.cal.AddWorkDays(accepted, in.Origin.HandlingDays, in.Origin.WorksSaturday)
	t := in.Transit
	if in.Service.MaxDays > 0 {
		t.MinDays, t.MaxDays = min(t.MinDays, in.Service.MaxDays), min(t.MaxDays, in.Service.MaxDays)
	}
	t.MinDays, t.MaxDays = max(t.MinDays, 1), max(t.MaxDays, t.MinDays, 1)
	earliest := p.cal.AddWorkDays(ship, t.MinDays, in.Service.SaturdayDelivery)
	latest := p.cal.AddWorkDays(ship, t.MaxDays, in.Service.SaturdayDelivery)
	return Promise{Available: true, ShipDate: &ship, EarliestDate: &earliest, LatestDate: &latest,
		CutoffAt: ptr(cutoff.UTC()), Transit: &t}
}

func (p *plannerImpl) afroDelivery(in PlanInput) Promise {
	z := in.Zone
	if !z.SameDay && !z.NextDay {
		return Unavailable("Outside the AfroTransact Delivery area for this item")
	}
	o := in.Origin
	if o.Kind == OriginFC && z.SameDay {
		local := in.Now.In(o.Location)
		today := DayOf(local)
		if cut := z.SameDayCutoff.On(today, o.Location); local.Before(cut) && p.cal.IsWorkDay(today, z.DeliversSaturday) &&
			p.cal.IsWorkDay(today, o.WorksSaturday) {
			return Promise{Available: true, ShipDate: &today, EarliestDate: &today, LatestDate: &today, CutoffAt: ptr(cut.UTC())}
		}
	}
	accepted, cutoff := p.accept(o, in.Now)
	dispatch := p.cal.AddWorkDays(accepted, o.HandlingDays, o.WorksSaturday)
	deliver := p.cal.NextWorkDay(dispatch, z.DeliversSaturday)
	return Promise{Available: true, ShipDate: &dispatch, EarliestDate: &deliver, LatestDate: &deliver, CutoffAt: ptr(cutoff.UTC())}
}

func (p *plannerImpl) pickup(in PlanInput) Promise {
	w := in.Pickup
	loc := w.Location
	if loc == nil {
		loc = in.Origin.Location
	}
	start := in.Now.In(loc)
	if in.Origin.HandlingDays > 0 {
		accepted, _ := p.accept(in.Origin, in.Now)
		ready := p.cal.AddWorkDays(accepted, in.Origin.HandlingDays, in.Origin.WorksSaturday)
		start = ready.At(loc, 0, 0)
	}
	candidate := start.Add(time.Duration(w.ReadyHours) * time.Hour)
	readyAt, ok := p.alignToOpening(candidate, loc, w.Hours)
	if !ok {
		return Unavailable("Pickup location has no opening hours in the next two weeks")
	}
	day := DayOf(readyAt)
	return Promise{Available: true, ShipDate: &day, EarliestDate: &day, LatestDate: &day, ReadyAt: &readyAt,
		ValidUntil: in.Now.Add(5 * time.Minute)}
}

// alignToOpening returns the first instant at or after t when the location is open (holidays closed).
func (p *plannerImpl) alignToOpening(t time.Time, loc *time.Location, hours []OpeningHours) (time.Time, bool) {
	day := DayOf(t)
	for range 15 {
		if _, holiday := p.cal.Holiday(day); !holiday {
			for _, h := range hours {
				if h.Weekday != day.Weekday() {
					continue
				}
				open, err1 := ParseClock(h.Open)
				closing, err2 := ParseClock(h.Close)
				if err1 != nil || err2 != nil {
					continue
				}
				o, c := open.On(day, loc), closing.On(day, loc)
				switch {
				case t.Before(o):
					return o, true
				case t.Before(c):
					return t, true
				}
			}
		}
		day = day.AddDays(1)
		t = day.At(loc, 0, 0)
	}
	return time.Time{}, false
}
