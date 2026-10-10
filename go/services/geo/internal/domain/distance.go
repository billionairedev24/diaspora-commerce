package domain

import (
	"errors"
	"math"
	"strings"
)

// MetresPerMile converts PostGIS geography distances (metres) to miles.
const MetresPerMile = 1609.344

// DefaultDetourFactor turns a geodesic distance into an estimated road distance when no routing provider answers.
const DefaultDetourFactor = 1.3

// DefaultFallbackSpeedMph estimates the duration of a detour-estimated trip (urban driving incl. stops).
const DefaultFallbackSpeedMph = 25.0

// Point is a WGS84 position.
type Point struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

// Precision says how a place was located.
type Precision string

// Precisions, from best to worst.
const (
	PrecisionPoint   Precision = "POINT"   // coordinates sent by the caller (e.g. stored on an address)
	PrecisionAddress Precision = "ADDRESS" // geocoded street address (provider chain)
	PrecisionZip     Precision = "ZIP"     // ZIP centroid
)

// Place is one end of a distance query: coordinates, a provider address id (autocomplete suggestion), a ZIP or a
// street address to geocode. Exactly one form is used, in that order of preference.
type Place struct {
	Lat       *float64 `json:"lat,omitempty"`
	Lng       *float64 `json:"lng,omitempty"`
	AddressID string   `json:"addressId,omitempty"`
	Zip       string   `json:"zip,omitempty"`
	Address   *Address `json:"address,omitempty"`
}

// ErrInvalidPlace is returned for a place with no usable form.
var ErrInvalidPlace = errors.New("invalid place")

// Kind returns the form the place uses, or ErrInvalidPlace.
func (p Place) Kind() (string, error) {
	switch {
	case p.Lat != nil || p.Lng != nil:
		if p.Lat == nil || p.Lng == nil {
			return "", errors.Join(ErrInvalidPlace, errors.New("lat and lng go together"))
		}
		if err := CheckPoint(*p.Lat, *p.Lng); err != nil {
			return "", err
		}
		return "point", nil
	case strings.TrimSpace(p.AddressID) != "":
		return "addressId", nil
	case strings.TrimSpace(p.Zip) != "":
		return "zip", nil
	case p.Address != nil:
		return "address", nil
	default:
		return "", errors.Join(ErrInvalidPlace, errors.New("give lat/lng, addressId, zip or address"))
	}
}

// Located is a place resolved to a point.
type Located struct {
	Point
	Precision Precision `json:"precision"`
}

// DistanceMethod says which routing provider produced roadMiles.
type DistanceMethod string

// Methods.
const (
	MethodRoadGoogle     DistanceMethod = "ROAD_GOOGLE"     // Google Routes API computeRouteMatrix (driving)
	MethodRoadOSRM       DistanceMethod = "ROAD_OSRM"       // self-hosted OSRM table service (driving)
	MethodGeodesicDetour DistanceMethod = "GEODESIC_DETOUR" // last resort: PostGIS geodesic × detour factor
)

// Route is one origin → destination driving result of a routing provider.
type Route struct {
	Metres  float64
	Seconds float64
}

// CachedRoute is a provider result kept in the route cache.
type CachedRoute struct {
	Route
	Method DistanceMethod
}

// Distance between two places. roadMiles and durationMinutes come from the first routing provider that answered
// (method), or, when none is configured or all failed, from the geodesic distance × detour factor at an assumed urban
// speed (method GEODESIC_DETOUR).
type Distance struct {
	StraightLineMiles float64        `json:"straightLineMiles"`
	RoadMiles         float64        `json:"roadMiles"`
	DurationMinutes   float64        `json:"durationMinutes"`
	Method            DistanceMethod `json:"method"`
	// Cached is true when the road distance came from the route cache (no provider call).
	Cached bool `json:"cached"`
	// EstimatedRoadMiles repeats RoadMiles for clients of the first S255 contract.
	EstimatedRoadMiles float64  `json:"estimatedRoadMiles"`
	From               *Located `json:"from,omitempty"`
	To                 *Located `json:"to,omitempty"`
}

// SecondsToMinutes rounds to 1 decimal.
func SecondsToMinutes(s float64) float64 {
	return math.Round(s/6) / 10
}

// MetresToMiles rounds to 2 decimals (about 16 m), enough for pricing and stable across calls.
func MetresToMiles(m float64) float64 {
	return RoundMiles(m / MetresPerMile)
}

// RoundMiles rounds miles to 2 decimals.
func RoundMiles(mi float64) float64 {
	return math.Round(mi*100) / 100
}

// Detour estimates road miles from geodesic miles; a factor below 1 is treated as 1 (roads are never shorter).
func Detour(straightMiles, factor float64) float64 {
	if factor < 1 || math.IsNaN(factor) {
		factor = 1
	}
	return RoundMiles(straightMiles * factor)
}
