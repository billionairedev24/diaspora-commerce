package app

import (
	"context"
	"fmt"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// ZoneService manages delivery zones, FC catchments and provider service areas.
type ZoneService interface {
	Save(ctx context.Context, z domain.Zone) (domain.Zone, error)
	List(ctx context.Context, t domain.ZoneType, ownerID string) ([]domain.Zone, error)
	// Covering returns the active zones of type t that contain the point (all types when t is empty).
	Covering(ctx context.Context, lat, lon float64, t domain.ZoneType) ([]domain.Zone, error)
	// Near returns active zones of type t that contain the point or are at most withinMiles from it, nearest first
	// (seller pickup locations just outside a delivery zone).
	Near(ctx context.Context, lat, lon float64, t domain.ZoneType, withinMiles float64) ([]domain.ZoneMatch, error)
}

type zoneServiceImpl struct {
	store port.ZoneStore
}

var _ ZoneService = (*zoneServiceImpl)(nil)

// NewZoneService returns the zone use cases.
func NewZoneService(store port.ZoneStore) ZoneService { return &zoneServiceImpl{store: store} }

func (s *zoneServiceImpl) Save(ctx context.Context, z domain.Zone) (domain.Zone, error) {
	if err := z.Validate(); err != nil {
		return domain.Zone{}, fmt.Errorf("save zone: %w", err)
	}
	saved, err := s.store.Save(ctx, z)
	if err != nil {
		return domain.Zone{}, fmt.Errorf("save zone: %w", err)
	}
	return saved, nil
}

func (s *zoneServiceImpl) List(ctx context.Context, t domain.ZoneType, ownerID string) ([]domain.Zone, error) {
	zs, err := s.store.List(ctx, t, ownerID)
	if err != nil {
		return nil, fmt.Errorf("list zones: %w", err)
	}
	return zs, nil
}

func (s *zoneServiceImpl) Covering(ctx context.Context, lat, lon float64, t domain.ZoneType) ([]domain.Zone, error) {
	if lat < -90 || lat > 90 || lon < -180 || lon > 180 {
		return nil, fmt.Errorf("point out of range: %w", domain.ErrInvalidZone)
	}
	zs, err := s.store.Containing(ctx, lat, lon, t)
	if err != nil {
		return nil, fmt.Errorf("covering zones: %w", err)
	}
	return zs, nil
}

func (s *zoneServiceImpl) Near(ctx context.Context, lat, lon float64, t domain.ZoneType, withinMiles float64) ([]domain.ZoneMatch, error) {
	if err := domain.CheckPoint(lat, lon); err != nil {
		return nil, fmt.Errorf("near zones: %w", err)
	}
	if withinMiles < 0 || withinMiles > 200 {
		return nil, fmt.Errorf("withinMiles must be 0–200: %w", domain.ErrInvalidZone)
	}
	zs, err := s.store.Within(ctx, lat, lon, t, withinMiles*domain.MetresPerMile)
	if err != nil {
		return nil, fmt.Errorf("near zones: %w", err)
	}
	return zs, nil
}
