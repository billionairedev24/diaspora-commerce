package domain

import "time"

// WebsiteThresholds are what a store must have shown before its stored website links appear on the public page. They are a
// staff setting (diversion:manage, audited); DefaultWebsiteThresholds are the safe defaults.
type WebsiteThresholds struct {
	// Enabled false (the default) hides every website link, whatever the store has earned.
	Enabled               bool    `json:"enabled"`
	MinCompletedOrders    int     `json:"minCompletedOrders"`
	MinDaysSinceFirstSale int     `json:"minDaysSinceFirstSale"`
	MaxDisputeRate        float64 `json:"maxDisputeRate"`
	MaxLateShipmentRate   float64 `json:"maxLateShipmentRate"`
}

// DefaultWebsiteThresholds hide all website links (Enabled false); once staff switch the gate on these apply.
func DefaultWebsiteThresholds() WebsiteThresholds {
	return WebsiteThresholds{Enabled: false, MinCompletedOrders: 25, MinDaysSinceFirstSale: 60, MaxDisputeRate: 0.01, MaxLateShipmentRate: 0.04}
}

// StoreTrustFacts are a store's record.
type StoreTrustFacts struct {
	CompletedOrders  int
	FirstSaleAt      time.Time
	DisputeRate      float64
	LateShipmentRate float64
	// DiversionFlagged is true while staff have an open diversion flag on the store.
	DiversionFlagged bool
	// StaffGrant is the staff override: true grants, false revokes, nil leaves it to the numbers.
	StaffGrant *bool
}

// EarnedWebsite reports whether the store's website links may be shown. A staff revoke always hides; a staff grant shows
// unless the gate is off or the store has an open diversion flag.
func EarnedWebsite(f StoreTrustFacts, t WebsiteThresholds, now time.Time) bool {
	if !t.Enabled || f.DiversionFlagged {
		return false
	}
	if f.StaffGrant != nil {
		return *f.StaffGrant
	}
	if f.FirstSaleAt.IsZero() || f.CompletedOrders < t.MinCompletedOrders {
		return false
	}
	if now.Sub(f.FirstSaleAt) < time.Duration(t.MinDaysSinceFirstSale)*24*time.Hour {
		return false
	}
	return f.DisputeRate <= t.MaxDisputeRate && f.LateShipmentRate <= t.MaxLateShipmentRate
}
