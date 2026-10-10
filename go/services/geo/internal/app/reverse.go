package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// ReverseService turns device coordinates into ZIP, city, state and country (deliver-to by location).
type ReverseService interface {
	Reverse(ctx context.Context, lat, lon float64) (domain.Address, error)
}

type reverseServiceImpl struct {
	providers []port.ReverseGeocoder // ordered: Google first, then Nominatim (then fixtures locally)
	log       *slog.Logger
}

var _ ReverseService = (*reverseServiceImpl)(nil)

// NewReverseService returns the reverse-geocoding use case. Providers are tried in order: a provider that fails or
// knows no address hands over to the next, so an outage at one vendor never blanks the deliver-to picker.
func NewReverseService(providers []port.ReverseGeocoder, log *slog.Logger) ReverseService {
	if log == nil {
		log = slog.Default()
	}
	return &reverseServiceImpl{providers: providers, log: log}
}

// precision rounds coordinates to ~11 m: enough for a ZIP, kinder to provider caches and to the shopper's privacy.
const precision = 1e4

func (s *reverseServiceImpl) Reverse(ctx context.Context, lat, lon float64) (domain.Address, error) {
	if err := domain.CheckPoint(lat, lon); err != nil {
		return domain.Address{}, fmt.Errorf("reverse %v,%v: %w", lat, lon, err)
	}
	lat, lon = math.Round(lat*precision)/precision, math.Round(lon*precision)/precision
	var errs []error
	notFound := 0
	for _, p := range s.providers {
		a, err := p.Reverse(ctx, lat, lon)
		if err == nil && a.PostalCode != "" {
			a.Country = strings.ToUpper(a.Country)
			if a.Country == "US" && len(a.PostalCode) > 5 {
				a.PostalCode = a.PostalCode[:5]
			}
			if a.Lat == 0 && a.Lon == 0 {
				a.Lat, a.Lon = lat, lon
			}
			return a, nil
		}
		if err == nil || errors.Is(err, domain.ErrNoAddress) {
			notFound++
			continue
		}
		s.log.WarnContext(ctx, "reverse geocoding provider failed", "provider", p.Name(), "error", err)
		errs = append(errs, fmt.Errorf("%s: %w", p.Name(), err))
	}
	if len(errs) == 0 || notFound > 0 {
		return domain.Address{}, fmt.Errorf("reverse %v,%v: %w", lat, lon, ErrNotFound)
	}
	return domain.Address{}, fmt.Errorf("reverse %v,%v: %w", lat, lon, errors.Join(errs...))
}
