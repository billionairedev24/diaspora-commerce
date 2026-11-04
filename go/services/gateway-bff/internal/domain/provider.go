package domain

import (
	"fmt"
	"slices"
	"strings"
	"time"
)

// Booking statuses (bookings BookingStatus) with the labels customers and providers see.
var bookingLabels = map[string]string{
	"PENDING_PAYMENT": "Awaiting payment", "REQUESTED": "Awaiting provider", "CONFIRMED": "Confirmed",
	"RESCHEDULE_REQUESTED": "New time requested", "RESCHEDULE_PROPOSED": "Provider proposed a new time",
	"COMPLETED": "Completed", "NO_SHOW": "No-show", "DECLINED": "Declined by provider", "EXPIRED": "Expired · not confirmed",
	"CANCELLED_CUSTOMER": "Cancelled by customer", "CANCELLED_PROVIDER": "Cancelled by provider",
}

// BookingStatusLabel names a booking status.
func BookingStatusLabel(s string) string {
	if l, ok := bookingLabels[s]; ok {
		return l
	}
	return s
}

// BookingActions a provider can take.
var BookingActions = []string{"CONFIRM", "DECLINE", "PROPOSE", "COMPLETE", "NO_SHOW", "CANCEL"}

// AllowedBookingActions follows the bookings state machine: requests are confirmed, declined or moved; confirmed
// bookings are completed, marked no-show (after the start) or cancelled.
func AllowedBookingActions(status string, start, now time.Time) []string {
	switch status {
	case "REQUESTED", "RESCHEDULE_REQUESTED":
		return []string{"CONFIRM", "DECLINE", "PROPOSE"}
	case "CONFIRMED":
		if now.After(start) {
			return []string{"COMPLETE", "NO_SHOW"}
		}
		return []string{"PROPOSE", "CANCEL"}
	case "RESCHEDULE_PROPOSED":
		return []string{"CANCEL"}
	}
	return []string{}
}

// BookingCard is a booking in the calendar, requests list and quick view.
type BookingCard struct {
	ID              string     `json:"id"`
	Reference       string     `json:"reference"`
	CustomerName    string     `json:"customerName"`
	ServiceName     string     `json:"serviceName"`
	StartAt         time.Time  `json:"startAt"`
	EndAt           time.Time  `json:"endAt"`
	DurationMinutes int        `json:"durationMinutes"`
	Status          string     `json:"status"`
	StatusLabel     string     `json:"statusLabel"`
	PriceMinor      int64      `json:"priceMinor"`
	DepositMinor    int64      `json:"depositMinor"`
	Currency        string     `json:"currency"`
	DepositState    string     `json:"depositState"`
	Note            *string    `json:"note"`
	Practitioner    *string    `json:"practitioner"`
	ExpiresAt       *time.Time `json:"expiresAt"`
	ProposedStartAt *time.Time `json:"proposedStartAt"`
	AllowedActions  []string   `json:"allowedActions"`
	// ServiceCategory selects the connection fee; TakeHome is what the provider keeps at the booking's price
	// ("You keep $X after the 8% connection fee"), set on booking requests when payments can say.
	ServiceCategory *string          `json:"serviceCategory"`
	TakeHome        *BookingTakeHome `json:"takeHome"`
}

// Normalize fills labels, duration and allowed actions the screens rely on.
func (b *BookingCard) Normalize(now time.Time) {
	b.StatusLabel = BookingStatusLabel(b.Status)
	if b.DurationMinutes <= 0 && b.EndAt.After(b.StartAt) {
		b.DurationMinutes = int(b.EndAt.Sub(b.StartAt) / time.Minute)
	}
	if b.Currency == "" {
		b.Currency = Currency
	}
	if b.DepositState == "" {
		b.DepositState = "none"
	}
	b.AllowedActions = AllowedBookingActions(b.Status, b.StartAt, now)
}

// DaySummary is the hover summary of a calendar day.
type DaySummary struct {
	Date                 string `json:"date"`
	Appointments         int    `json:"appointments"`
	BookedMinutes        int    `json:"bookedMinutes"`
	ExpectedRevenueMinor int64  `json:"expectedRevenueMinor"`
	NeedsAction          int    `json:"needsAction"`
}

// TimeRange is an "HH:MM"–"HH:MM" range.
type TimeRange struct {
	Start string `json:"start"`
	End   string `json:"end"`
}

// DayHours is one weekday's open ranges (empty = closed).
type DayHours struct {
	Day    string      `json:"day"`
	Ranges []TimeRange `json:"ranges"`
}

// CalendarView is GET /v1/seller/bookings/calendar.
type CalendarView struct {
	Timezone  string        `json:"timezone"`
	From      string        `json:"from"`
	To        string        `json:"to"`
	OpenHours []DayHours    `json:"openHours"`
	Bookings  []BookingCard `json:"bookings"`
	Days      []DaySummary  `json:"days"`
}

var activeBooking = []string{"REQUESTED", "CONFIRMED", "RESCHEDULE_REQUESTED", "RESCHEDULE_PROPOSED"}

// DaySummaries computes one summary per day in [from, to] from the active bookings (in loc).
func DaySummaries(bookings []BookingCard, from, to time.Time, loc *time.Location) []DaySummary {
	if loc == nil {
		loc = time.UTC
	}
	byDay := map[string]*DaySummary{}
	out := []DaySummary{}
	for d := from; !d.After(to); d = d.AddDate(0, 0, 1) {
		key := d.Format(time.DateOnly)
		out = append(out, DaySummary{Date: key})
		byDay[key] = &out[len(out)-1]
	}
	for _, b := range bookings {
		if !slices.Contains(activeBooking, b.Status) {
			continue
		}
		s := byDay[b.StartAt.In(loc).Format(time.DateOnly)]
		if s == nil {
			continue
		}
		s.Appointments++
		s.BookedMinutes += b.DurationMinutes
		s.ExpectedRevenueMinor += b.PriceMinor
		if b.Status == "REQUESTED" || b.Status == "RESCHEDULE_REQUESTED" {
			s.NeedsAction++
		}
	}
	return out
}

// Weekdays in display order.
var Weekdays = []string{"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"}

// BlockedDate is a day off.
type BlockedDate struct {
	Date string `json:"date"`
	Note string `json:"note"`
}

// BookingRules are lead time, horizon, buffers and limits.
type BookingRules struct {
	LeadMinutes          int  `json:"leadMinutes"`
	HorizonDays          int  `json:"horizonDays"`
	BufferMinutes        int  `json:"bufferMinutes"`
	StepMinutes          int  `json:"stepMinutes"`
	MaxPerDay            int  `json:"maxPerDay"`
	InstantBooking       bool `json:"instantBooking"`
	AutoAcceptReschedule bool `json:"autoAcceptReschedule"`
}

// Availability is working hours with breaks, blocked dates and booking rules.
type Availability struct {
	Timezone     string        `json:"timezone"`
	Weekly       []DayHours    `json:"weekly"`
	BlockedDates []BlockedDate `json:"blockedDates"`
	Rules        BookingRules  `json:"rules"`
}

func minutesOf(hhmm string) (int, bool) {
	var h, m int
	if len(hhmm) != 5 || hhmm[2] != ':' {
		return 0, false
	}
	if _, err := fmt.Sscanf(hhmm, "%02d:%02d", &h, &m); err != nil || h > 24 || m > 59 || (h == 24 && m > 0) {
		return 0, false
	}
	return h*60 + m, true
}

// Validate checks the week (7 known days, ranges start before they end and never overlap) and the rules — the same
// checks the bookings service applies (WeeklyHours, BookingRules).
func (a Availability) Validate() map[string]string {
	errs := map[string]string{}
	seen := map[string]bool{}
	for _, d := range a.Weekly {
		if !slices.Contains(Weekdays, d.Day) || seen[d.Day] {
			errs["weekly"] = "Send each weekday once"
			continue
		}
		seen[d.Day] = true
		type span struct{ s, e int }
		var spans []span
		for _, r := range d.Ranges {
			s, ok1 := minutesOf(r.Start)
			e, ok2 := minutesOf(r.End)
			if !ok1 || !ok2 || s >= e {
				errs[d.Day] = "Each range must start before it ends"
				continue
			}
			spans = append(spans, span{s, e})
		}
		slices.SortFunc(spans, func(x, y span) int { return x.s - y.s })
		for i := 1; i < len(spans); i++ {
			if spans[i].s < spans[i-1].e {
				errs[d.Day] = "Working hours overlap"
			}
		}
	}
	if len(seen) != 7 && errs["weekly"] == "" {
		errs["weekly"] = "Send all seven weekdays"
	}
	r := a.Rules
	if r.LeadMinutes < 0 || r.LeadMinutes > 30*24*60 {
		errs["rules.leadMinutes"] = "Between 0 and 30 days"
	}
	if r.HorizonDays < 1 || r.HorizonDays > 365 {
		errs["rules.horizonDays"] = "Between 1 and 365 days"
	}
	if r.BufferMinutes < 0 || r.BufferMinutes > 240 {
		errs["rules.bufferMinutes"] = "Between 0 and 240 minutes"
	}
	if r.StepMinutes < 5 || r.StepMinutes > 240 {
		errs["rules.stepMinutes"] = "Between 5 and 240 minutes"
	}
	if r.MaxPerDay < 1 || r.MaxPerDay > 50 {
		errs["rules.maxPerDay"] = "Between 1 and 50"
	}
	return errs
}

// TakeHome is what the provider keeps from a service price.
type TakeHome struct {
	CommissionPercent float64 `json:"commissionPercent"`
	CommissionMinor   int64   `json:"commissionMinor"`
	CardFeesMinor     int64   `json:"cardFeesMinor"`
	TakeHomeMinor     int64   `json:"takeHomeMinor"`
}

// ServiceOffer is one bookable service.
type ServiceOffer struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Description     string   `json:"description"`
	DurationMinutes int      `json:"durationMinutes"`
	PriceMinor      int64    `json:"priceMinor"`
	DepositMinor    int64    `json:"depositMinor"`
	Currency        string   `json:"currency"`
	Active          bool     `json:"active"`
	TakeHome        TakeHome `json:"takeHome"`
}

// ServiceInput creates or changes a service.
type ServiceInput struct {
	Name            string `json:"name"`
	Description     string `json:"description"`
	DurationMinutes int    `json:"durationMinutes"`
	PriceMinor      int64  `json:"priceMinor"`
	DepositMinor    int64  `json:"depositMinor"`
	Active          bool   `json:"active"`
}

// Validate applies bookings' ServiceOffering rules.
func (s ServiceInput) Validate() map[string]string {
	errs := map[string]string{}
	if n := len([]rune(strings.TrimSpace(s.Name))); n < 3 || n > 120 {
		errs["name"] = "3–120 characters"
	}
	if s.DurationMinutes < 5 || s.DurationMinutes > 24*60 {
		errs["durationMinutes"] = "Between 5 minutes and 24 hours"
	}
	if s.PriceMinor <= 0 {
		errs["priceMinor"] = "Price must be above zero"
	}
	if s.DepositMinor < 0 || s.DepositMinor > s.PriceMinor {
		errs["depositMinor"] = "Deposit must be between 0 and the price"
	}
	return errs
}

// PolicyInput is a cancellation policy choice.
type PolicyInput struct {
	Kind             string `json:"kind"`
	FreeHours        int    `json:"freeHours"`
	PartialRefund    bool   `json:"partialRefund"`
	PartialFromHours int    `json:"partialFromHours"`
	PartialPercent   int    `json:"partialPercent"`
	NoShow           string `json:"noShow"`
	RescheduleHours  int    `json:"rescheduleHours"`
	MaxReschedules   int    `json:"maxReschedules"`
}

// PolicyStep is one band of the refund timeline.
type PolicyStep struct {
	Label         string `json:"label"`
	RefundPercent int    `json:"refundPercent"`
}

// CancellationPolicy is the effective policy with its plain-language summary and refund timeline.
type CancellationPolicy struct {
	Kind             string       `json:"kind"`
	Label            string       `json:"label"`
	FreeHours        int          `json:"freeHours"`
	PartialRefund    bool         `json:"partialRefund"`
	PartialFromHours int          `json:"partialFromHours"`
	PartialPercent   int          `json:"partialPercent"`
	NoShow           string       `json:"noShow"`
	RescheduleHours  int          `json:"rescheduleHours"`
	MaxReschedules   int          `json:"maxReschedules"`
	Summary          string       `json:"summary"`
	Timeline         []PolicyStep `json:"timeline"`
}

var policyLabels = map[string]string{"FLEXIBLE": "Flexible", "MODERATE": "Moderate", "STRICT": "Strict", "CUSTOM": "Custom"}

// PresetPolicy returns the bookings presets (Flexible 24 h, Moderate 48 h with 50% from 24 h, Strict 7 days).
func PresetPolicy(kind string) (PolicyInput, bool) {
	switch kind {
	case "FLEXIBLE":
		return PolicyInput{Kind: kind, FreeHours: 24, NoShow: "DEPOSIT", RescheduleHours: 24, MaxReschedules: 2}, true
	case "MODERATE":
		return PolicyInput{Kind: kind, FreeHours: 48, PartialRefund: true, PartialFromHours: 24, PartialPercent: 50, NoShow: "DEPOSIT", RescheduleHours: 48, MaxReschedules: 2}, true
	case "STRICT":
		return PolicyInput{Kind: kind, FreeHours: 168, NoShow: "DEPOSIT", RescheduleHours: 168, MaxReschedules: 2}, true
	}
	return PolicyInput{}, false
}

// Validate checks a custom policy (presets are filled by PresetPolicy).
func (p PolicyInput) Validate() map[string]string {
	errs := map[string]string{}
	if _, ok := policyLabels[p.Kind]; !ok {
		errs["kind"] = "Choose Flexible, Moderate, Strict or Custom"
		return errs
	}
	if p.Kind != "CUSTOM" {
		return errs
	}
	if p.FreeHours < 0 || p.FreeHours > 720 {
		errs["freeHours"] = "Between 0 and 720 hours"
	}
	if p.PartialRefund {
		if p.PartialFromHours < 0 || p.PartialFromHours >= p.FreeHours {
			errs["partialFromHours"] = "Must be below the free-cancellation window"
		}
		if p.PartialPercent < 1 || p.PartialPercent > 99 {
			errs["partialPercent"] = "Between 1 and 99%"
		}
	}
	if p.NoShow != "DEPOSIT" && p.NoShow != "FULL" {
		errs["noShow"] = "Keep the deposit or charge the full price"
	}
	if p.RescheduleHours < 0 || p.RescheduleHours > 720 {
		errs["rescheduleHours"] = "Between 0 and 720 hours"
	}
	if p.MaxReschedules < 0 || p.MaxReschedules > 10 {
		errs["maxReschedules"] = "Between 0 and 10"
	}
	return errs
}

// HoursLabel renders a window ("7 days", "2 days", "36 hours").
func HoursLabel(h int) string {
	switch {
	case h >= 48 && h%24 == 0:
		return fmt.Sprintf("%d days", h/24)
	case h == 1:
		return "1 hour"
	default:
		return fmt.Sprintf("%d hours", h)
	}
}

// Describe builds the effective policy (label, plain-language summary as shown before booking, refund timeline).
func (p PolicyInput) Describe() CancellationPolicy {
	if preset, ok := PresetPolicy(p.Kind); ok {
		p = preset
	}
	c := CancellationPolicy{Kind: p.Kind, Label: policyLabels[p.Kind], FreeHours: p.FreeHours, PartialRefund: p.PartialRefund,
		PartialFromHours: p.PartialFromHours, PartialPercent: p.PartialPercent, NoShow: p.NoShow, RescheduleHours: p.RescheduleHours,
		MaxReschedules: p.MaxReschedules}
	var b strings.Builder
	fmt.Fprintf(&b, "Free cancellation up to %s before.", HoursLabel(p.FreeHours))
	c.Timeline = []PolicyStep{{Label: "More than " + HoursLabel(p.FreeHours) + " before", RefundPercent: 100}}
	if p.PartialRefund && p.PartialPercent > 0 && p.PartialFromHours < p.FreeHours {
		fmt.Fprintf(&b, " %s–%s before: %d%% of the deposit back.", HoursLabel(p.PartialFromHours), HoursLabel(p.FreeHours), p.PartialPercent)
		c.Timeline = append(c.Timeline, PolicyStep{Label: HoursLabel(p.PartialFromHours) + "–" + HoursLabel(p.FreeHours) + " before", RefundPercent: p.PartialPercent})
		c.Timeline = append(c.Timeline, PolicyStep{Label: "Under " + HoursLabel(p.PartialFromHours) + " before", RefundPercent: 0})
	} else {
		c.Timeline = append(c.Timeline, PolicyStep{Label: "Under " + HoursLabel(p.FreeHours) + " before", RefundPercent: 0})
	}
	b.WriteString(" Later than that the deposit is kept.")
	if p.NoShow == "FULL" {
		b.WriteString(" No-shows are charged the full price.")
	}
	fmt.Fprintf(&b, " Reschedule free up to %s before (max %d).", HoursLabel(p.RescheduleHours), p.MaxReschedules)
	c.Summary = b.String()
	return c
}

// EarningsRow is what one booking earned.
type EarningsRow struct {
	BookingID          string    `json:"bookingId"`
	Reference          string    `json:"reference"`
	CustomerName       string    `json:"customerName"`
	ServiceName        string    `json:"serviceName"`
	StartAt            time.Time `json:"startAt"`
	Status             string    `json:"status"`
	StatusLabel        string    `json:"statusLabel"`
	CollectedMinor     int64     `json:"collectedMinor"`
	CommissionMinor    int64     `json:"commissionMinor"`
	CardFeesMinor      int64     `json:"cardFeesMinor"`
	LateCancelFeeMinor int64     `json:"lateCancelFeeMinor"`
	NetMinor           int64     `json:"netMinor"`
}

// EarningsTotals sums the rows.
type EarningsTotals struct {
	CollectedMinor      int64 `json:"collectedMinor"`
	CommissionMinor     int64 `json:"commissionMinor"`
	CardFeesMinor       int64 `json:"cardFeesMinor"`
	LateCancelFeesMinor int64 `json:"lateCancelFeesMinor"`
	NetMinor            int64 `json:"netMinor"`
	HeldMinor           int64 `json:"heldMinor"`
}

// ProviderFee is a late-cancellation fee the provider owes.
type ProviderFee struct {
	ID               string     `json:"id"`
	BookingReference string     `json:"bookingReference"`
	AmountMinor      int64      `json:"amountMinor"`
	Currency         string     `json:"currency"`
	Reason           string     `json:"reason"`
	Status           string     `json:"status"`
	NextAttemptAt    *time.Time `json:"nextAttemptAt"`
	CreatedAt        time.Time  `json:"createdAt"`
}

// ProviderFees sums outstanding and collected fees; Available=false when payments could not answer.
type ProviderFees struct {
	Available        bool          `json:"available"`
	OutstandingMinor int64         `json:"outstandingMinor"`
	CollectedMinor   int64         `json:"collectedMinor"`
	Items            []ProviderFee `json:"items"`
}

// Earnings is GET /v1/seller/provider/earnings.
type Earnings struct {
	Currency          string         `json:"currency"`
	CommissionPercent float64        `json:"commissionPercent"`
	PayoutDay         string         `json:"payoutDay"`
	Totals            EarningsTotals `json:"totals"`
	Rows              []EarningsRow  `json:"rows"`
	ProviderFees      ProviderFees   `json:"providerFees"`
	// ConnectionFee is the provider's connection fee now, per service category, with where it comes from (nil when
	// payments could not say).
	ConnectionFee *SellerRateCard `json:"connectionFee"`
}

// Total recomputes totals from the rows (net = collected − commission − card fees − late-cancel fees). Held money
// comes from bookings and is kept.
func (e *Earnings) Total() {
	held := e.Totals.HeldMinor
	e.Totals = EarningsTotals{HeldMinor: held}
	for i := range e.Rows {
		r := &e.Rows[i]
		r.StatusLabel = BookingStatusLabel(r.Status)
		r.NetMinor = r.CollectedMinor - r.CommissionMinor - r.CardFeesMinor - r.LateCancelFeeMinor
		e.Totals.CollectedMinor += r.CollectedMinor
		e.Totals.CommissionMinor += r.CommissionMinor
		e.Totals.CardFeesMinor += r.CardFeesMinor
		e.Totals.LateCancelFeesMinor += r.LateCancelFeeMinor
		e.Totals.NetMinor += r.NetMinor
	}
}
