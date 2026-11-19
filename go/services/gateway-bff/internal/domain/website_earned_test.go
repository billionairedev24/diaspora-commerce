package domain

import (
	"testing"
	"time"
)

func TestEarnedWebsite(t *testing.T) {
	now := time.Date(2026, 11, 19, 0, 0, 0, 0, time.UTC)
	on := DefaultWebsiteThresholds()
	on.Enabled = true
	good := StoreTrustFacts{CompletedOrders: 30, FirstSaleAt: now.AddDate(0, 0, -90), DisputeRate: 0.002, LateShipmentRate: 0.01}
	yes, no := true, false
	cases := []struct {
		name string
		f    StoreTrustFacts
		t    WebsiteThresholds
		want bool
	}{
		{"default hides all", good, DefaultWebsiteThresholds(), false},
		{"earned", good, on, true},
		{"too few orders", StoreTrustFacts{CompletedOrders: 24, FirstSaleAt: good.FirstSaleAt}, on, false},
		{"too new", StoreTrustFacts{CompletedOrders: 30, FirstSaleAt: now.AddDate(0, 0, -59)}, on, false},
		{"no first sale", StoreTrustFacts{CompletedOrders: 30}, on, false},
		{"disputes", StoreTrustFacts{CompletedOrders: 30, FirstSaleAt: good.FirstSaleAt, DisputeRate: 0.05}, on, false},
		{"late shipments", StoreTrustFacts{CompletedOrders: 30, FirstSaleAt: good.FirstSaleAt, LateShipmentRate: 0.2}, on, false},
		{"flag blocks", StoreTrustFacts{CompletedOrders: 30, FirstSaleAt: good.FirstSaleAt, DiversionFlagged: true}, on, false},
		{"grant", StoreTrustFacts{StaffGrant: &yes}, on, true},
		{"grant with gate off", StoreTrustFacts{StaffGrant: &yes}, DefaultWebsiteThresholds(), false},
		{"grant with flag", StoreTrustFacts{StaffGrant: &yes, DiversionFlagged: true}, on, false},
		{"revoke", StoreTrustFacts{CompletedOrders: 99, FirstSaleAt: good.FirstSaleAt, StaffGrant: &no}, on, false},
	}
	for _, c := range cases {
		if got := EarnedWebsite(c.f, c.t, now); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}
