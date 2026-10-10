package domain

import (
	"fmt"
	"time"
)

// Describe fills the customer-facing labels relative to now: the delivery label ("Tomorrow, Oct 10",
// "Wed, Oct 15 – Fri, Oct 17", "Ready for pickup today at 4:00 PM") in the customer's time zone, and the
// cutoff countdown ("Order within 3 h 12 min") while the cutoff is less than a day away.
func Describe(p *Promise, now time.Time, customer *time.Location) {
	p.Label, p.CutoffLabel = "", ""
	if !p.Available || p.EarliestDate == nil || p.LatestDate == nil {
		return
	}
	if customer == nil {
		customer = time.UTC
	}
	today := DayOf(now.In(customer))
	if p.ReadyAt != nil {
		p.Label = ReadyLabel(*p.ReadyAt, DayOf(now.In(p.ReadyAt.Location())))
	} else {
		p.Label = DeliveryLabel(*p.EarliestDate, *p.LatestDate, today)
	}
	if p.CutoffAt != nil {
		p.CutoffLabel = CutoffLabel(p.CutoffAt.Sub(now))
	}
}

// DayLabel is "Today, Oct 10", "Tomorrow, Oct 10" or "Wed, Oct 15".
func DayLabel(d, today Day) string {
	switch today.DaysUntil(d) {
	case 0:
		return "Today, " + d.Format("Jan 2")
	case 1:
		return "Tomorrow, " + d.Format("Jan 2")
	default:
		return d.Format("Mon, Jan 2")
	}
}

// DeliveryLabel renders a single day relative to today, or a range "Wed, Oct 15 – Fri, Oct 17".
func DeliveryLabel(earliest, latest, today Day) string {
	if earliest == latest {
		return DayLabel(earliest, today)
	}
	return earliest.Format("Mon, Jan 2") + " – " + latest.Format("Mon, Jan 2")
}

// ReadyLabel renders a pickup ready time in the pickup location's zone.
func ReadyLabel(at time.Time, today Day) string {
	d := DayOf(at)
	clock := at.Format("3:04 PM")
	switch today.DaysUntil(d) {
	case 0:
		return "Ready for pickup today at " + clock
	case 1:
		return "Ready for pickup tomorrow at " + clock
	default:
		return "Ready for pickup " + d.Format("Mon, Jan 2") + " at " + clock
	}
}

// CutoffLabel renders the time left to order ("Order within 3 h 12 min"); empty when past or a day or more away.
func CutoffLabel(left time.Duration) string {
	if left <= 0 || left >= 24*time.Hour {
		return ""
	}
	mins := int(left / time.Minute)
	switch {
	case mins < 1:
		return "Order within 1 min"
	case mins < 60:
		return fmt.Sprintf("Order within %d min", mins)
	case mins%60 == 0:
		return fmt.Sprintf("Order within %d h", mins/60)
	default:
		return fmt.Sprintf("Order within %d h %d min", mins/60, mins%60)
	}
}
