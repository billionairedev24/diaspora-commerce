package domain

import "errors"

// ZoneType distinguishes delivery zones, fulfilment-centre catchments and provider service areas.
type ZoneType string

// Zone types.
const (
	ZoneDelivery    ZoneType = "DELIVERY"
	ZoneFCCatchment ZoneType = "FC_CATCHMENT"
	ZoneServiceArea ZoneType = "SERVICE_AREA"
)

// Zone is a polygon (GeoJSON) or a circle (center + radius) owned by the platform, an FC or a provider.
type Zone struct {
	ID          string   `json:"id"`
	Type        ZoneType `json:"type"`
	OwnerID     string   `json:"ownerId"`
	Name        string   `json:"name"`
	GeoJSON     string   `json:"geojson,omitempty"`
	CenterLat   float64  `json:"centerLat,omitempty"`
	CenterLon   float64  `json:"centerLon,omitempty"`
	RadiusMiles float64  `json:"radiusMiles,omitempty"`
	Active      bool     `json:"active"`
}

// ErrInvalidZone is returned when a zone has neither a polygon nor a valid circle.
var ErrInvalidZone = errors.New("invalid zone")

// Validate checks the zone shape.
func (z Zone) Validate() error {
	switch z.Type {
	case ZoneDelivery, ZoneFCCatchment, ZoneServiceArea:
	default:
		return errors.Join(ErrInvalidZone, errors.New("unknown zone type"))
	}
	if z.Name == "" || z.OwnerID == "" {
		return errors.Join(ErrInvalidZone, errors.New("name and owner are required"))
	}
	if z.GeoJSON == "" && (z.RadiusMiles <= 0 || z.RadiusMiles > 200) {
		return errors.Join(ErrInvalidZone, errors.New("provide a GeoJSON polygon or a radius of 0–200 miles"))
	}
	if z.GeoJSON == "" && (z.CenterLat < -90 || z.CenterLat > 90 || z.CenterLon < -180 || z.CenterLon > 180) {
		return errors.Join(ErrInvalidZone, errors.New("center is out of range"))
	}
	return nil
}

// ZoneMatch is a zone near a point: Inside when it contains the point, else DistanceMiles to its edge.
type ZoneMatch struct {
	Zone
	Inside        bool    `json:"inside"`
	DistanceMiles float64 `json:"distanceMiles"`
}
