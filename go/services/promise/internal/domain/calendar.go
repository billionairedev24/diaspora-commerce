// Package domain holds the delivery-promise rules: civil days, the US business calendar (weekends and federal
// holidays with observed rules), promise planning and customer-facing labels. No infrastructure imports.
package domain

import (
	"fmt"
	"sync"
	"time"
)

// Day is a civil date with no time zone.
type Day struct {
	Year  int
	Month time.Month
	Day   int
}

// DayOf returns the civil date of t in t's location.
func DayOf(t time.Time) Day {
	y, m, d := t.Date()
	return Day{y, m, d}
}

// ParseDay parses YYYY-MM-DD.
func ParseDay(s string) (Day, error) {
	t, err := time.Parse(time.DateOnly, s)
	if err != nil {
		return Day{}, fmt.Errorf("parse day %q: %w", s, err)
	}
	return DayOf(t), nil
}

// At returns the instant of hh:mm on d in loc.
func (d Day) At(loc *time.Location, hour, minute int) time.Time {
	return time.Date(d.Year, d.Month, d.Day, hour, minute, 0, 0, loc)
}

func (d Day) noon() time.Time { return time.Date(d.Year, d.Month, d.Day, 12, 0, 0, 0, time.UTC) }

// AddDays adds calendar days.
func (d Day) AddDays(n int) Day { return DayOf(d.noon().AddDate(0, 0, n)) }

// Weekday returns the day of the week.
func (d Day) Weekday() time.Weekday { return d.noon().Weekday() }

// Before reports whether d is earlier than o.
func (d Day) Before(o Day) bool { return d.noon().Before(o.noon()) }

// Format formats d with a time layout.
func (d Day) Format(layout string) string { return d.noon().Format(layout) }

// String is YYYY-MM-DD.
func (d Day) String() string { return d.Format(time.DateOnly) }

// DaysUntil returns the number of calendar days from d to o.
func (d Day) DaysUntil(o Day) int { return int(o.noon().Sub(d.noon()).Hours() / 24) }

// MarshalText encodes YYYY-MM-DD.
func (d Day) MarshalText() ([]byte, error) { return []byte(d.String()), nil }

// UnmarshalText decodes YYYY-MM-DD.
func (d *Day) UnmarshalText(b []byte) error {
	p, err := ParseDay(string(b))
	if err != nil {
		return err
	}
	*d = p
	return nil
}

// Holiday is a US federal holiday with the date it is observed when it falls on a weekend.
type Holiday struct {
	Name     string `json:"name"`
	Date     Day    `json:"date"`
	Observed Day    `json:"observed"`
}

func nthWeekday(year int, month time.Month, wd time.Weekday, n int) Day {
	d := Day{year, month, 1}
	for d.Weekday() != wd {
		d = d.AddDays(1)
	}
	return d.AddDays(7 * (n - 1))
}

func lastWeekday(year int, month time.Month, wd time.Weekday) Day {
	d := Day{year, month + 1, 1}.AddDays(-1) // time.Date normalises month 13 to January
	for d.Weekday() != wd {
		d = d.AddDays(-1)
	}
	return d
}

// observe moves a Saturday holiday to Friday and a Sunday holiday to Monday (5 U.S.C. 6103(b)).
func observe(d Day) Day {
	switch d.Weekday() {
	case time.Saturday:
		return d.AddDays(-1)
	case time.Sunday:
		return d.AddDays(1)
	default:
		return d
	}
}

// FederalHolidays returns the federal holidays of a year (5 U.S.C. 6103), including observed dates. The observed
// New Year's Day of the following year can fall on Dec 31 of this year; Calendar accounts for that.
func FederalHolidays(year int) []Holiday {
	fixed := func(name string, m time.Month, day int) Holiday {
		d := Day{year, m, day}
		return Holiday{Name: name, Date: d, Observed: observe(d)}
	}
	floating := func(name string, d Day) Holiday { return Holiday{Name: name, Date: d, Observed: d} }
	hs := []Holiday{
		fixed("New Year's Day", time.January, 1),
		floating("Birthday of Martin Luther King, Jr.", nthWeekday(year, time.January, time.Monday, 3)),
		floating("Washington's Birthday", nthWeekday(year, time.February, time.Monday, 3)),
		floating("Memorial Day", lastWeekday(year, time.May, time.Monday)),
	}
	if year >= 2021 {
		hs = append(hs, fixed("Juneteenth National Independence Day", time.June, 19))
	}
	return append(hs,
		fixed("Independence Day", time.July, 4),
		floating("Labor Day", nthWeekday(year, time.September, time.Monday, 1)),
		floating("Columbus Day", nthWeekday(year, time.October, time.Monday, 2)),
		fixed("Veterans Day", time.November, 11),
		floating("Thanksgiving Day", nthWeekday(year, time.November, time.Thursday, 4)),
		fixed("Christmas Day", time.December, 25),
	)
}

// Calendar answers business-day questions for shipping and delivery.
type Calendar interface {
	// Holiday returns the holiday observed on d, if any.
	Holiday(d Day) (string, bool)
	// IsBusinessDay: Monday–Friday and not an observed federal holiday.
	IsBusinessDay(d Day) bool
	// IsWorkDay is a business day, or a non-holiday Saturday when saturday is true.
	IsWorkDay(d Day, saturday bool) bool
	// NextWorkDay returns the first work day strictly after d.
	NextWorkDay(d Day, saturday bool) Day
	// AddWorkDays returns the n-th work day after d (n = 0 returns d).
	AddWorkDays(d Day, n int, saturday bool) Day
}

type usCalendarImpl struct {
	mu    sync.Mutex
	years map[int]map[Day]string
}

var _ Calendar = (*usCalendarImpl)(nil)

// NewUSCalendar returns the US federal business calendar; holidays are computed for any year on demand.
func NewUSCalendar() Calendar { return &usCalendarImpl{years: map[int]map[Day]string{}} }

func (c *usCalendarImpl) observedIn(year int) map[Day]string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if m, ok := c.years[year]; ok {
		return m
	}
	m := map[Day]string{}
	for _, y := range []int{year, year + 1} { // Jan 1 on a Saturday is observed on Dec 31 of the year before
		for _, h := range FederalHolidays(y) {
			if h.Observed.Year == year {
				m[h.Observed] = h.Name
			}
		}
	}
	c.years[year] = m
	return m
}

func (c *usCalendarImpl) Holiday(d Day) (string, bool) {
	name, ok := c.observedIn(d.Year)[d]
	return name, ok
}

func (c *usCalendarImpl) IsBusinessDay(d Day) bool {
	if wd := d.Weekday(); wd == time.Saturday || wd == time.Sunday {
		return false
	}
	_, holiday := c.Holiday(d)
	return !holiday
}

func (c *usCalendarImpl) IsWorkDay(d Day, saturday bool) bool {
	if saturday && d.Weekday() == time.Saturday {
		_, holiday := c.Holiday(d)
		return !holiday
	}
	return c.IsBusinessDay(d)
}

func (c *usCalendarImpl) NextWorkDay(d Day, saturday bool) Day {
	for {
		d = d.AddDays(1)
		if c.IsWorkDay(d, saturday) {
			return d
		}
	}
}

func (c *usCalendarImpl) AddWorkDays(d Day, n int, saturday bool) Day {
	for range n {
		d = c.NextWorkDay(d, saturday)
	}
	return d
}
