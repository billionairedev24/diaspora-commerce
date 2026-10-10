package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

var (
	central = mustLoc("America/Chicago")
	eastern = mustLoc("America/New_York")
	cal     = domain.NewUSCalendar()
	planner = domain.NewPlanner(cal)
)

func mustLoc(name string) *time.Location {
	l, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return l
}

func day(s string) domain.Day {
	d, err := domain.ParseDay(s)
	if err != nil {
		panic(err)
	}
	return d
}

func at(loc *time.Location, m time.Month, d, hh, mm int) time.Time {
	return time.Date(2026, m, d, hh, mm, 0, 0, loc)
}

func TestFederalHolidaysWithObservedRules(t *testing.T) {
	cases := map[string]string{
		"2026-01-01": "New Year's Day",
		"2026-01-19": "Birthday of Martin Luther King, Jr.",
		"2026-02-16": "Washington's Birthday",
		"2026-05-25": "Memorial Day",
		"2026-06-19": "Juneteenth National Independence Day",
		"2026-07-03": "Independence Day", // Jul 4 2026 is a Saturday → observed Friday
		"2026-09-07": "Labor Day",
		"2026-10-12": "Columbus Day",
		"2026-11-11": "Veterans Day",
		"2026-11-26": "Thanksgiving Day",
		"2026-12-25": "Christmas Day",
		"2027-06-18": "Juneteenth National Independence Day", // Saturday → Friday
		"2027-07-05": "Independence Day",                     // Sunday → Monday
		"2027-12-24": "Christmas Day",                        // Saturday → Friday
		"2027-12-31": "New Year's Day",                       // Jan 1 2028 is a Saturday → observed the year before
		"2021-06-18": "Juneteenth National Independence Day",
		"2023-01-02": "New Year's Day", // Sunday → Monday
		"2030-01-21": "Birthday of Martin Luther King, Jr.",
		"2031-05-26": "Memorial Day",
	}
	for d, want := range cases {
		if got, ok := cal.Holiday(day(d)); !ok || got != want {
			t.Errorf("%s: got %q (%v), want %q", d, got, ok, want)
		}
	}
	for _, d := range []string{"2026-07-04", "2020-06-19", "2026-10-13"} {
		if name, ok := cal.Holiday(day(d)); ok {
			t.Errorf("%s must not be an observed holiday, got %s", d, name)
		}
	}
	if cal.IsBusinessDay(day("2027-12-31")) || cal.IsBusinessDay(day("2026-10-10")) || !cal.IsBusinessDay(day("2026-10-13")) {
		t.Fatal("business day rules")
	}
	if !cal.IsWorkDay(day("2026-10-10"), true) || cal.IsWorkDay(day("2026-10-11"), true) {
		t.Fatal("Saturday counts only when enabled; Sunday never")
	}
}

func houston() domain.Origin {
	return domain.Origin{Kind: domain.OriginFC, ID: "fc_houston_tx", ZIP: "77061", Location: central, Cutoff: domain.Clock{Hour: 14}}
}

func ground(minDays, maxDays int) domain.Transit {
	return domain.Transit{MinDays: minDays, MaxDays: maxDays, Source: "table"}
}

func plan(t *testing.T, in domain.PlanInput) domain.Promise {
	t.Helper()
	p := planner.Plan(in)
	if in.Customer == nil {
		in.Customer = central
	}
	domain.Describe(&p, in.Now, in.Customer)
	return p
}

func TestCarrierCutoff(t *testing.T) {
	rule, tr := domain.ServiceRule{}, ground(1, 1)
	before := plan(t, domain.PlanInput{Now: at(central, 10, 8, 11, 48), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr})
	if *before.ShipDate != day("2026-10-08") || *before.EarliestDate != day("2026-10-09") || before.Label != "Tomorrow, Oct 9" ||
		before.CutoffLabel != "Order within 2 h 12 min" {
		t.Fatalf("before cutoff: %+v", before)
	}
	if !before.ValidUntil.Equal(at(central, 10, 8, 12, 0)) {
		t.Fatalf("cache validity ends at the next hour: %v", before.ValidUntil)
	}
	after := plan(t, domain.PlanInput{Now: at(central, 10, 8, 14, 5), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr})
	// Ships Friday; Saturday/Sunday are not delivery days and Monday Oct 12 is Columbus Day.
	if *after.ShipDate != day("2026-10-09") || *after.EarliestDate != day("2026-10-13") || after.Label != "Tue, Oct 13" ||
		after.CutoffLabel != "Order within 23 h 55 min" {
		t.Fatalf("after cutoff: %+v", after)
	}
	cutoffSoon := plan(t, domain.PlanInput{Now: at(central, 10, 8, 13, 30), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr})
	if !cutoffSoon.ValidUntil.Equal(at(central, 10, 8, 14, 0)) {
		t.Fatalf("cache validity must stop at the cutoff: %v", cutoffSoon.ValidUntil)
	}
}

func TestWeekendAndHolidayRollover(t *testing.T) {
	rule, tr := domain.ServiceRule{}, ground(1, 1)
	friLate := plan(t, domain.PlanInput{Now: at(central, 10, 9, 15, 0), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr})
	if *friLate.ShipDate != day("2026-10-13") || *friLate.EarliestDate != day("2026-10-14") {
		t.Fatalf("Friday after cutoff ships Tuesday (Columbus Day Monday): %+v", friLate)
	}
	if friLate.CutoffLabel != "" {
		t.Fatalf("next cutoff is days away, no countdown: %q", friLate.CutoffLabel)
	}
	observed := plan(t, domain.PlanInput{Now: at(central, 7, 2, 15, 0), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr})
	if *observed.ShipDate != day("2026-07-06") || *observed.EarliestDate != day("2026-07-07") {
		t.Fatalf("Jul 3 2026 is the observed Independence Day: %+v", observed)
	}
	rangeRule, rangeTransit := domain.ServiceRule{}, ground(2, 3)
	r := plan(t, domain.PlanInput{Now: at(central, 10, 8, 9, 0), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rangeRule, Transit: rangeTransit})
	if *r.EarliestDate != day("2026-10-13") || *r.LatestDate != day("2026-10-14") || r.Label != "Tue, Oct 13 – Wed, Oct 14" {
		t.Fatalf("range %+v", r)
	}
	thanksgiving := plan(t, domain.PlanInput{Now: at(central, 11, 25, 15, 0), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr})
	if *thanksgiving.ShipDate != day("2026-11-27") || *thanksgiving.EarliestDate != day("2026-11-30") {
		t.Fatalf("Thanksgiving rollover %+v", thanksgiving)
	}
}

func TestSaturdayDeliveryPerServiceLevel(t *testing.T) {
	tr := domain.Transit{MinDays: 3, MaxDays: 4}
	overnight := plan(t, domain.PlanInput{Now: at(central, 10, 9, 10, 0), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelOvernight, Service: domain.ServiceRule{MaxDays: 1, SaturdayDelivery: true}, Transit: tr})
	if *overnight.EarliestDate != day("2026-10-10") || overnight.Label != "Tomorrow, Oct 10" || overnight.Transit.MaxDays != 1 {
		t.Fatalf("overnight with Saturday delivery: %+v", overnight)
	}
	twoDay := plan(t, domain.PlanInput{Now: at(central, 10, 9, 10, 0), Origin: houston(), Method: domain.MethodCarrier,
		Level: domain.LevelTwoDay, Service: domain.ServiceRule{MaxDays: 2}, Transit: tr})
	if *twoDay.EarliestDate != day("2026-10-14") || *twoDay.LatestDate != day("2026-10-14") {
		t.Fatalf("two-day without Saturday skips the weekend and Columbus Day: %+v", twoDay)
	}
}

func TestSellerHandlingDays(t *testing.T) {
	seller := domain.Origin{Kind: domain.OriginSeller, ID: "org_1", ZIP: "30310", Location: eastern, Cutoff: domain.Clock{Hour: 12}, HandlingDays: 2}
	rule, tr := domain.ServiceRule{}, ground(2, 2)
	early := plan(t, domain.PlanInput{Now: at(eastern, 10, 5, 10, 0), Origin: seller, Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr, Customer: eastern})
	if *early.ShipDate != day("2026-10-07") || *early.EarliestDate != day("2026-10-09") || early.CutoffLabel != "Order within 2 h" {
		t.Fatalf("Mon before cutoff + 2 handling days ships Wed: %+v", early)
	}
	late := plan(t, domain.PlanInput{Now: at(eastern, 10, 5, 13, 0), Origin: seller, Method: domain.MethodCarrier,
		Level: domain.LevelGround, Service: rule, Transit: tr, Customer: eastern})
	if *late.ShipDate != day("2026-10-08") || *late.EarliestDate != day("2026-10-13") {
		t.Fatalf("after the seller cutoff handling starts Tuesday: %+v", late)
	}
}

func afro(now time.Time, z domain.DeliveryZone, origin domain.Origin) domain.Promise {
	p := planner.Plan(domain.PlanInput{Now: now, Origin: origin, Method: domain.MethodAfroDelivery, Zone: z})
	domain.Describe(&p, now, central)
	return p
}

func TestAfroTransactDelivery(t *testing.T) {
	sameDayZone := domain.DeliveryZone{SameDay: true, SameDayCutoff: domain.Clock{Hour: 11}, DeliversSaturday: true}
	today := afro(at(central, 10, 8, 10, 30), sameDayZone, houston())
	if *today.EarliestDate != day("2026-10-08") || today.Label != "Today, Oct 8" || today.CutoffLabel != "Order within 30 min" {
		t.Fatalf("same day: %+v", today)
	}
	missed := afro(at(central, 10, 8, 11, 30), sameDayZone, houston())
	if *missed.EarliestDate != day("2026-10-09") || missed.Label != "Tomorrow, Oct 9" || missed.CutoffLabel != "Order within 2 h 30 min" {
		t.Fatalf("after same-day cutoff → next day: %+v", missed)
	}
	nextDayZone := domain.DeliveryZone{NextDay: true, SameDayCutoff: domain.Clock{Hour: 11}, DeliversSaturday: true}
	nd := afro(at(central, 10, 8, 10, 30), nextDayZone, houston())
	if *nd.EarliestDate != day("2026-10-09") {
		t.Fatalf("next-day zone: %+v", nd)
	}
	friday := afro(at(central, 10, 9, 13, 0), nextDayZone, houston())
	if *friday.EarliestDate != day("2026-10-10") {
		t.Fatalf("drivers deliver Saturdays: %+v", friday)
	}
	outside := afro(at(central, 10, 8, 10, 30), domain.DeliveryZone{}, houston())
	if outside.Available || outside.Reason == "" || outside.Label != "" {
		t.Fatalf("outside zone: %+v", outside)
	}
}

func TestPickupReadyTime(t *testing.T) {
	hours := []domain.OpeningHours{
		{Weekday: time.Monday, Open: "09:00", Close: "19:00"}, {Weekday: time.Tuesday, Open: "09:00", Close: "19:00"},
		{Weekday: time.Wednesday, Open: "09:00", Close: "19:00"}, {Weekday: time.Thursday, Open: "09:00", Close: "19:00"},
		{Weekday: time.Friday, Open: "09:00", Close: "19:00"}, {Weekday: time.Saturday, Open: "10:00", Close: "16:00"},
	}
	pick := func(now time.Time, origin domain.Origin) domain.Promise {
		p := planner.Plan(domain.PlanInput{Now: now, Origin: origin, Method: domain.MethodPickup,
			Pickup: domain.PickupWindow{ReadyHours: 2, Location: central, Hours: hours}})
		domain.Describe(&p, now, central)
		return p
	}
	if p := pick(at(central, 10, 8, 10, 0), houston()); p.Label != "Ready for pickup today at 12:00 PM" {
		t.Fatalf("same day: %+v", p)
	}
	if p := pick(at(central, 10, 8, 18, 0), houston()); p.Label != "Ready for pickup tomorrow at 9:00 AM" {
		t.Fatalf("after close: %+v", p)
	}
	if p := pick(at(central, 10, 10, 15, 30), houston()); p.Label != "Ready for pickup Tue, Oct 13 at 9:00 AM" ||
		*p.EarliestDate != day("2026-10-13") {
		t.Fatalf("Saturday late → closed Sunday and Columbus Day: %+v", p)
	}
	if p := pick(at(central, 10, 8, 6, 0), houston()); p.Label != "Ready for pickup today at 9:00 AM" {
		t.Fatalf("before opening: %+v", p)
	}
	seller := domain.Origin{Kind: domain.OriginSeller, ID: "org_1", Location: central, Cutoff: domain.Clock{Hour: 12}, HandlingDays: 1}
	if p := pick(at(central, 10, 5, 10, 0), seller); p.ReadyAt == nil || !p.ReadyAt.Equal(at(central, 10, 6, 9, 0)) {
		t.Fatalf("seller with one handling day: %+v", p)
	}
	closed := planner.Plan(domain.PlanInput{Now: at(central, 10, 8, 10, 0), Origin: houston(), Method: domain.MethodPickup,
		Pickup: domain.PickupWindow{ReadyHours: 2, Location: central}})
	if closed.Available {
		t.Fatal("no opening hours → unavailable")
	}
}

func TestLabels(t *testing.T) {
	today := day("2026-10-09")
	cases := []struct{ got, want string }{
		{domain.DeliveryLabel(day("2026-10-09"), day("2026-10-09"), today), "Today, Oct 9"},
		{domain.DeliveryLabel(day("2026-10-10"), day("2026-10-10"), today), "Tomorrow, Oct 10"},
		{domain.DeliveryLabel(day("2026-10-15"), day("2026-10-15"), today), "Thu, Oct 15"},
		{domain.DeliveryLabel(day("2026-10-14"), day("2026-10-16"), today), "Wed, Oct 14 – Fri, Oct 16"},
		{domain.DeliveryLabel(day("2026-12-31"), day("2027-01-04"), today), "Thu, Dec 31 – Mon, Jan 4"},
		{domain.CutoffLabel(3*time.Hour + 12*time.Minute + 40*time.Second), "Order within 3 h 12 min"},
		{domain.CutoffLabel(45 * time.Minute), "Order within 45 min"},
		{domain.CutoffLabel(20 * time.Second), "Order within 1 min"},
		{domain.CutoffLabel(2 * time.Hour), "Order within 2 h"},
		{domain.CutoffLabel(30 * time.Hour), ""},
		{domain.CutoffLabel(-time.Minute), ""},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("got %q want %q", c.got, c.want)
		}
	}
}
