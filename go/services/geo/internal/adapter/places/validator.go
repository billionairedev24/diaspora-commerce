package places

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type validatorImpl struct {
	key    string
	base   string
	client *http.Client
}

var _ port.Validator = (*validatorImpl)(nil)

// NewValidator returns the address validator over the Google Geocoding API (same Maps Platform key as Places).
// A typed address is VALID when Google returns a street-level match (rooftop or range-interpolated) in the same ZIP.
// base may be empty for the public endpoint (tests pass httptest URLs).
func NewValidator(apiKey, base string, client *http.Client) port.Validator {
	if base == "" {
		base = defaultGeocodingBase
	}
	return &validatorImpl{key: apiKey, base: base, client: client}
}

func (v *validatorImpl) Name() string { return "google-geocoding" }

// streetLevel reports whether a Geocoding location type is a street-level match. A ZIP centroid, a city or any
// approximate result is not.
func streetLevel(locationType string) bool {
	return locationType == "ROOFTOP" || locationType == "RANGE_INTERPOLATED"
}

func (v *validatorImpl) Validate(ctx context.Context, a domain.Address) (domain.Validation, error) {
	line := strings.Join([]string{a.Line1, a.City, strings.TrimSpace(a.State + " " + a.PostalCode)}, ", ")
	country := a.Country
	if country == "" {
		country = "US"
	}
	q := url.Values{"address": {line}, "components": {"country:" + country}, "key": {v.key}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.base+"/geocode/json?"+q.Encode(), nil)
	if err != nil {
		return domain.Validation{}, fmt.Errorf("request: %w", err)
	}
	res, err := v.client.Do(req)
	if err != nil {
		return domain.Validation{}, fmt.Errorf("google geocoding: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1024))
		return domain.Validation{}, fmt.Errorf("google geocoding: status %d: %s", res.StatusCode, msg)
	}
	var out geocodeResponse
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return domain.Validation{}, fmt.Errorf("google geocoding decode: %w", err)
	}
	switch out.Status {
	case "OK", "ZERO_RESULTS":
	default:
		return domain.Validation{}, fmt.Errorf("google geocoding: %s %s", out.Status, out.ErrorMessage)
	}
	notFound := domain.Validation{Status: domain.StatusNotFound, Suggestions: []domain.Address{},
		Messages: []string{domain.MessageNotFound}}
	for _, r := range out.Results {
		if !streetLevel(r.Geometry.LocationType) {
			continue
		}
		found := addressFromComponents(r.AddressComponents)
		if found.Line1 == "" || found.PostalCode == "" {
			continue
		}
		found.Lat, found.Lon = r.Geometry.Location.Lat, r.Geometry.Location.Lng
		if !sameZip(a, found) {
			if len(notFound.Suggestions) == 0 {
				notFound.Suggestions = append(notFound.Suggestions, found)
			}
			continue
		}
		std := found
		std.Line2 = a.Line2
		return domain.Validation{Status: domain.StatusValid, Standard: &std, PlaceID: r.PlaceID,
			Suggestions: []domain.Address{}, Messages: []string{}}, nil
	}
	return notFound, nil
}

// sameZip compares ZIP codes on their first five digits for the US, and as whole normalised codes elsewhere.
func sameZip(typed, found domain.Address) bool {
	norm := func(s string) string { return strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(s), " ", "")) }
	t, f := norm(typed.PostalCode), norm(found.PostalCode)
	if strings.EqualFold(found.Country, "US") || typed.Country == "" || strings.EqualFold(typed.Country, "US") {
		return len(t) >= 5 && len(f) >= 5 && t[:5] == f[:5]
	}
	return t == f
}
