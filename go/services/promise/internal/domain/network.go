package domain

import (
	"fmt"
	"regexp"
	"slices"
	"time"
)

// ZipInfo is what we know about a ZIP code's region.
type ZipInfo struct {
	ZIP      string
	ZIP3     string
	State    string
	Lat, Lon float64
	Location *time.Location
}

var zip5 = regexp.MustCompile(`^\d{5}(-\d{4})?$`)

// NormalizeZIP validates a US ZIP (ZIP5 or ZIP+4) and returns the ZIP5.
func NormalizeZIP(s string) (string, error) {
	if !zip5.MatchString(s) {
		return "", fmt.Errorf("%w: %q is not a US ZIP code", ErrInvalid, s)
	}
	return s[:5], nil
}

// FulfilmentCentre is one of our FCs with its cutoffs, pickup counter and AfroTransact Delivery zones.
type FulfilmentCentre struct {
	ID               string
	ZIP              string
	Location         *time.Location
	Cutoff           Clock
	WorksSaturday    bool
	PickupReadyHours int
	PickupHours      []OpeningHours
	SameDayCutoff    Clock
	DeliversSaturday bool
	SameDayZIP3      []string
	NextDayZIP3      []string
}

// Origin returns the FC as a planning origin.
func (f FulfilmentCentre) Origin() Origin {
	return Origin{Kind: OriginFC, ID: f.ID, ZIP: f.ZIP, Location: f.Location, Cutoff: f.Cutoff, WorksSaturday: f.WorksSaturday}
}

// Serves reports whether the FC's drivers cover a ZIP3 (same-day or next-day zone).
func (f FulfilmentCentre) Serves(zip3 string) bool {
	return slices.Contains(f.SameDayZIP3, zip3) || slices.Contains(f.NextDayZIP3, zip3)
}

// Zone returns AfroTransact Delivery eligibility for a destination ZIP3.
func (f FulfilmentCentre) Zone(destZIP3 string) DeliveryZone {
	return DeliveryZone{
		SameDay: slices.Contains(f.SameDayZIP3, destZIP3), NextDay: slices.Contains(f.NextDayZIP3, destZIP3),
		SameDayCutoff: f.SameDayCutoff, DeliversSaturday: f.DeliversSaturday,
	}
}
