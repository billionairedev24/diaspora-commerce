package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

func TestWindowLabelIsARangeNotASingleInvention(t *testing.T) {
	d := func(s string) time.Time { v, _ := time.Parse(time.DateOnly, s); return v }
	for _, c := range []struct{ from, to, want string }{
		{"2026-11-18", "2026-11-20", "Nov 18 – 20"},
		{"2026-11-18", "2026-11-18", "Wed, Nov 18"},
		{"2026-11-30", "2026-12-02", "Nov 30 – Dec 2"},
		{"2026-12-30", "2027-01-02", "Dec 30 – Jan 2"},
	} {
		if got := domain.WindowLabel(d(c.from), d(c.to)); got != c.want {
			t.Errorf("%s..%s = %q, want %q", c.from, c.to, got, c.want)
		}
	}
}

func TestArrivalOfNeedsRealDates(t *testing.T) {
	today := time.Date(2026, 11, 14, 9, 0, 0, 0, time.UTC)
	a := domain.ArrivalOf("2026-11-18", "2026-11-20", "Order within 3 hrs 12 mins", today)
	if !a.Known() || a.Label != "Nov 18 – 20" || a.Days != 4 || a.CutoffLabel == "" || a.Basis != domain.ArrivalPromise {
		t.Fatalf("%+v", a)
	}
	if one := domain.ArrivalOf("2026-11-18", "", "", today); one.Label != "Wed, Nov 18" || one.LatestDate != "2026-11-18" {
		t.Fatalf("a single day: %+v", one)
	}
	for name, bad := range map[string]domain.Arrival{
		"unparseable": domain.ArrivalOf("soon", "", "", today),
		"reversed":    domain.ArrivalOf("2026-11-20", "2026-11-18", "", today),
		"empty":       domain.ArrivalOf("", "", "", today),
	} {
		if bad.Known() || bad.Label != "" || bad.Basis != domain.ArrivalNone {
			t.Errorf("%s: a malformed answer is never a date: %+v", name, bad)
		}
	}
}

func TestOnlyARealArrivalDatesAPromiseOrAnOption(t *testing.T) {
	e := domain.NewEstimator(domain.DeliveryPolicy{})
	p := e.Promise(domain.ShipsFromSeller)
	if p.Basis != domain.ArrivalNone || p.Label != "" {
		t.Fatalf("undated until a real window is applied: %+v", p)
	}
	today := time.Date(2026, 11, 14, 9, 0, 0, 0, time.UTC)
	dated := p.WithArrival(domain.ArrivalOf("2026-11-18", "2026-11-20", "", today))
	if dated.Basis != domain.ArrivalPromise || dated.Label != "Nov 18 – 20" || dated.EarliestDate != "2026-11-18" || dated.Reason != "" {
		t.Fatalf("%+v", dated)
	}
	none := dated.WithArrival(domain.NoArrival(domain.ArrivalNoPlace))
	if none.Label != "" || none.EarliestDate != "" || none.Reason != domain.ArrivalNoPlace || none.Days != 0 || none.Cutoff != nil {
		t.Fatalf("a no-basis arrival removes the date: %+v", none)
	}
	o := domain.DeliveryOption{Key: domain.OptionDelivery, Label: "Delivery"}.WithArrival(domain.NoArrival(domain.ArrivalNoOrigin))
	if o.ETA != "" || o.EarliestDate != "" {
		t.Fatalf("%+v", o)
	}
}
