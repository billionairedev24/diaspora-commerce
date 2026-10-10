// Package port declares the geo service's outbound interfaces.
package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

// Autocompleter suggests addresses while the user types (Google Places, OSM Nominatim or fixtures).
type Autocompleter interface {
	Name() string
	Suggest(ctx context.Context, input, sessionToken, country string, limit int) ([]domain.Suggestion, error)
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
	Ping(ctx context.Context) error
}
