package places

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

const defaultGeocodingBase = "https://maps.googleapis.com/maps/api"

type geocoderImpl struct {
	key    string
	base   string
	client *http.Client
}

var _ port.ReverseGeocoder = (*geocoderImpl)(nil)

// NewGeocoder returns a reverse geocoder over the Google Geocoding API (same Maps Platform key as Places).
// base may be empty for the public endpoint (tests pass httptest URLs).
func NewGeocoder(apiKey, base string, client *http.Client) port.ReverseGeocoder {
	if base == "" {
		base = defaultGeocodingBase
	}
	return &geocoderImpl{key: apiKey, base: base, client: client}
}

func (g *geocoderImpl) Name() string { return "google-geocoding" }

type geocodeResponse struct {
	Status       string `json:"status"`
	ErrorMessage string `json:"error_message"`
	Results      []struct {
		AddressComponents []struct {
			LongName  string   `json:"long_name"`
			ShortName string   `json:"short_name"`
			Types     []string `json:"types"`
		} `json:"address_components"`
		Geometry struct {
			Location struct {
				Lat float64 `json:"lat"`
				Lng float64 `json:"lng"`
			} `json:"location"`
		} `json:"geometry"`
	} `json:"results"`
}

func (g *geocoderImpl) Reverse(ctx context.Context, lat, lon float64) (domain.Address, error) {
	q := url.Values{
		"latlng":      {strconv.FormatFloat(lat, 'f', -1, 64) + "," + strconv.FormatFloat(lon, 'f', -1, 64)},
		"result_type": {"street_address|premise|route|postal_code"},
		"key":         {g.key},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, g.base+"/geocode/json?"+q.Encode(), nil)
	if err != nil {
		return domain.Address{}, fmt.Errorf("request: %w", err)
	}
	res, err := g.client.Do(req)
	if err != nil {
		return domain.Address{}, fmt.Errorf("google geocoding: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1024))
		return domain.Address{}, fmt.Errorf("google geocoding: status %d: %s", res.StatusCode, msg)
	}
	var out geocodeResponse
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return domain.Address{}, fmt.Errorf("google geocoding decode: %w", err)
	}
	switch out.Status {
	case "OK":
	case "ZERO_RESULTS":
		return domain.Address{}, domain.ErrNoAddress
	default:
		return domain.Address{}, fmt.Errorf("google geocoding: %s %s", out.Status, out.ErrorMessage)
	}
	for _, r := range out.Results {
		var a domain.Address
		var number, route string
		for _, c := range r.AddressComponents {
			for _, t := range c.Types {
				switch t {
				case "street_number":
					number = c.LongName
				case "route":
					route = c.ShortName
				case "locality", "postal_town":
					a.City = c.LongName
				case "sublocality", "neighborhood":
					if a.City == "" {
						a.City = c.LongName
					}
				case "administrative_area_level_1":
					a.State = c.ShortName
				case "postal_code":
					a.PostalCode = c.LongName
				case "country":
					a.Country = c.ShortName
				}
			}
		}
		if a.PostalCode == "" {
			continue
		}
		a.Line1 = joinNonEmpty(number, route)
		a.Lat, a.Lon = r.Geometry.Location.Lat, r.Geometry.Location.Lng
		return a, nil
	}
	return domain.Address{}, domain.ErrNoAddress
}
