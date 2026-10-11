package app

import (
	"context"
	"fmt"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// MaxCities caps one cities answer (Texas has about 1,200).
const MaxCities = 5000

// RegionList is a country with its regions.
type RegionList struct {
	Country     string               `json:"country"`
	CountryName string               `json:"countryName"`
	RegionLabel string               `json:"regionLabel"`
	Items       []domain.PlaceRegion `json:"items"`
}

// CityList is a region's cities matching a filter, by name.
type CityList struct {
	Country    string   `json:"country"`
	Region     string   `json:"region"`
	RegionName string   `json:"regionName"`
	Items      []string `json:"items"`
	Total      int      `json:"total"`
}

// PlaceService answers the back-office place pickers from the gazetteer.
type PlaceService interface {
	Countries(ctx context.Context) []domain.PlaceCountry
	Regions(ctx context.Context, country string) (RegionList, error)
	// Cities of a region whose name contains q (all when q is blank), at most limit (≤ MaxCities).
	Cities(ctx context.Context, country, region, q string, limit int) (CityList, error)
	Search(ctx context.Context, q, country string, limit int) ([]domain.PlaceMatch, error)
}

type placeServiceImpl struct {
	gaz port.Gazetteer
}

var _ PlaceService = (*placeServiceImpl)(nil)

// NewPlaceService returns the place pickers' use cases.
func NewPlaceService(gaz port.Gazetteer) PlaceService {
	return &placeServiceImpl{gaz: gaz}
}

func (s *placeServiceImpl) Countries(_ context.Context) []domain.PlaceCountry {
	return s.gaz.Countries()
}

func (s *placeServiceImpl) Regions(_ context.Context, country string) (RegionList, error) {
	c, regions, ok := s.gaz.Regions(country)
	if !ok {
		return RegionList{}, fmt.Errorf("country %q: %w", country, domain.ErrUnknownPlace)
	}
	return RegionList{Country: c.Code, CountryName: c.Name, RegionLabel: c.RegionLabel, Items: regions}, nil
}

func (s *placeServiceImpl) Cities(_ context.Context, country, region, q string, limit int) (CityList, error) {
	r, cities, ok := s.gaz.Cities(country, region)
	if !ok {
		return CityList{}, fmt.Errorf("region %q of %q: %w", region, country, domain.ErrUnknownPlace)
	}
	if limit < 1 || limit > MaxCities {
		limit = MaxCities
	}
	t := strings.ToLower(strings.TrimSpace(q))
	items := make([]string, 0, min(limit, len(cities)))
	total := 0
	for _, c := range cities {
		if t != "" && !strings.Contains(strings.ToLower(c), t) {
			continue
		}
		total++
		if len(items) < limit {
			items = append(items, c)
		}
	}
	return CityList{Country: strings.ToUpper(strings.TrimSpace(country)), Region: r.Code, RegionName: r.Name, Items: items,
		Total: total}, nil
}

func (s *placeServiceImpl) Search(_ context.Context, q, country string, limit int) ([]domain.PlaceMatch, error) {
	if len([]rune(strings.TrimSpace(q))) < 2 {
		return nil, fmt.Errorf("q needs at least 2 characters: %w", domain.ErrInvalidQuery)
	}
	return s.gaz.Search(q, country, limit), nil
}
