package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type zipFirstImpl struct {
	AddressService
	zips port.ZipIndex
}

var _ AddressService = (*zipFirstImpl)(nil)

// WithZipIndex answers ZIP lookups from the embedded ZIP table (offline, instant, same city/state/centroid as the
// service-area matching uses) and falls back to the provider chain for ZIPs the table does not know.
func WithZipIndex(inner AddressService, zips port.ZipIndex) AddressService {
	return &zipFirstImpl{AddressService: inner, zips: zips}
}

func (s *zipFirstImpl) LookupZip(ctx context.Context, zip string) (domain.Address, error) {
	if p, ok := s.zips.Lookup(strings.TrimSpace(zip)); ok {
		return domain.Address{City: p.City, State: p.State, PostalCode: p.Zip, Country: "US", Lat: p.Lat, Lon: p.Lon}, nil
	}
	return s.AddressService.LookupZip(ctx, zip)
}
