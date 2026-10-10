// Package port declares the geo service's outbound interfaces.
package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

// Autocompleter suggests addresses while the user types (Google Places, OSM Nominatim or fixtures).
type Autocompleter interface {
	Name() string
	// Suggest returns up to q.Limit suggestions of q.Kind in q.Country, biased towards q.Near (q is normalised).
	Suggest(ctx context.Context, q domain.AutocompleteQuery) ([]domain.Suggestion, error)
	// Resolve turns a suggestion id into a structured address with coordinates.
	Resolve(ctx context.Context, id, sessionToken string) (domain.Address, error)
}

// Validator checks deliverability (Smarty / USPS CASS) or falls back to format rules.
type Validator interface {
	Name() string
	Validate(ctx context.Context, a domain.Address) (domain.Validation, error)
}

// ZoneStore persists zones and answers spatial queries (PostGIS).
type ZoneStore interface {
	Save(ctx context.Context, z domain.Zone) (domain.Zone, error)
	List(ctx context.Context, t domain.ZoneType, ownerID string) ([]domain.Zone, error)
	Containing(ctx context.Context, lat, lon float64, t domain.ZoneType) ([]domain.Zone, error)
	// Within returns active zones that contain the point or whose edge is at most metres away, nearest first.
	Within(ctx context.Context, lat, lon float64, t domain.ZoneType, metres float64) ([]domain.ZoneMatch, error)
	Ping(ctx context.Context) error
}

// ReverseGeocoder turns coordinates into the nearest postal address (Google Geocoding, OSM Nominatim or fixtures).
// It returns domain.ErrNoAddress when the provider answered but knows no address at that point.
type ReverseGeocoder interface {
	Name() string
	Reverse(ctx context.Context, lat, lon float64) (domain.Address, error)
}

// DistanceCalculator measures geodesic distances (PostGIS geography, metres). One origin, many destinations, so a
// checkout or a dispatch screen costs one round trip.
type DistanceCalculator interface {
	Geodesic(ctx context.Context, from domain.Point, to []domain.Point) ([]float64, error)
}

// RoadRouter is a routing provider (Google Routes API computeRouteMatrix, OSRM table service): driving distance and
// duration from one origin to each destination, in the destinations' order. Any error (including a destination
// without a route) makes the caller try the next provider.
type RoadRouter interface {
	Name() string
	Method() domain.DistanceMethod
	Routes(ctx context.Context, from domain.Point, to []domain.Point) ([]domain.Route, error)
}

// RouteCache keeps provider results by rounded origin / destination (about 50 m) for a TTL, so repeated checkouts and
// dispatch screens don't pay for the same route twice.
type RouteCache interface {
	Get(from, to domain.Point) (domain.CachedRoute, bool)
	Put(from, to domain.Point, r domain.CachedRoute)
}
