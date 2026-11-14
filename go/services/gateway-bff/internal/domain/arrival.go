package domain

import "time"

// Arrival bases (DeliveryPromise.Basis).
const (
	// ArrivalPromise: the window was computed by the promise service from real inputs.
	ArrivalPromise = "PROMISE"
	// ArrivalNone: there is no real basis for a date; nothing may show one.
	ArrivalNone = "NONE"
)

// Why an arrival has no date (DeliveryPromise.Reason).
const (
	// ArrivalNoPlace: the shopper's ZIP is not known yet.
	ArrivalNoPlace = "NO_PLACE"
	// ArrivalNoOrigin: where or when the package leaves is not known (the seller has no handling time or ship-from ZIP on the
	// offer, or AfroTransact's own stock has no fulfilment centre to date from).
	ArrivalNoOrigin = "NO_ORIGIN"
	// ArrivalNoService: the deployment has no promise service.
	ArrivalNoService = "NO_SERVICE"
	// ArrivalUnavailable: the promise service did not answer or could not promise this offer to the place.
	ArrivalUnavailable = "UNAVAILABLE"
)

// Arrival is the promised delivery window of one package or offer: real dates from the promise service, or none.
type Arrival struct {
	Basis  string
	Reason string
	// EarliestDate and LatestDate are ISO dates (the window); Label renders them ("Nov 18 – 20", "Wed, Nov 18").
	EarliestDate, LatestDate string
	Label                    string
	CutoffLabel              string
	// Days is the calendar days from the day of the request to the earliest date (0 = today), for rail filtering.
	Days int
}

// Known reports whether the arrival carries a real window.
func (a Arrival) Known() bool {
	return a.Basis == ArrivalPromise && a.EarliestDate != "" && a.Label != ""
}

// NoArrival is the arrival of a package with no real basis for a date.
func NoArrival(reason string) Arrival { return Arrival{Basis: ArrivalNone, Reason: reason} }

// ArrivalOf builds the arrival of a promise window (ISO dates; latest defaults to earliest). It is none when a date does
// not parse: a malformed answer is never shown.
func ArrivalOf(earliest, latest, cutoffLabel string, today time.Time) Arrival {
	if latest == "" {
		latest = earliest
	}
	e, err1 := time.Parse(time.DateOnly, earliest)
	l, err2 := time.Parse(time.DateOnly, latest)
	if err1 != nil || err2 != nil || l.Before(e) {
		return NoArrival(ArrivalUnavailable)
	}
	return Arrival{Basis: ArrivalPromise, EarliestDate: earliest, LatestDate: latest, Label: WindowLabel(e, l), CutoffLabel: cutoffLabel,
		Days: daysBetween(today, e)}
}

// WindowLabel renders a delivery window the way shoppers read it: "Wed, Nov 18" for one day, "Nov 18 – 20" within a month,
// "Nov 30 – Dec 2" across months.
func WindowLabel(earliest, latest time.Time) string {
	switch {
	case earliest.Equal(latest):
		return earliest.Format("Mon, Jan 2")
	case earliest.Month() == latest.Month() && earliest.Year() == latest.Year():
		return earliest.Format("Jan 2") + " – " + latest.Format("2")
	default:
		return earliest.Format("Jan 2") + " – " + latest.Format("Jan 2")
	}
}

// daysBetween counts the calendar days from the day of from to the day of to (0 when to is not after from).
func daysBetween(from, to time.Time) int {
	f := time.Date(from.Year(), from.Month(), from.Day(), 0, 0, 0, 0, time.UTC)
	return max(0, int(to.Sub(f).Hours()/24))
}
