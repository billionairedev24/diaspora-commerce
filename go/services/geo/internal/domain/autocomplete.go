package domain

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
)

// AddressKind narrows suggestions to what a form needs (S139).
type AddressKind string

// Address kinds.
const (
	// KindAny is street addresses and ZIP codes (the default).
	KindAny AddressKind = ""
	// KindAddress is street addresses only: checkout, account addresses, onboarding, warehouses, pickup points.
	KindAddress AddressKind = "address"
	// KindPostalCode is ZIP codes only: the deliver-to picker.
	KindPostalCode AddressKind = "postal_code"
	// KindLocality is cities and towns only: service areas, category forms that ask for a city.
	KindLocality AddressKind = "locality"
)

// AutocompleteQuery is one keystroke of an address field.
type AutocompleteQuery struct {
	Input string
	// SessionToken groups the keystrokes of one address entry and its final resolve into one billable session
	// (Google Places); the service issues one when the client sends none and returns it.
	SessionToken string
	// Country is the ISO 3166-1 alpha-2 country the suggestions are restricted to (default US).
	Country string
	Kind    AddressKind
	// Near biases suggestions towards a point (deliver-to location, warehouse, the seller's city) within
	// RadiusMeters; results elsewhere still appear, ranked lower.
	Near         *Point
	RadiusMeters float64
	// Language is a BCP 47 tag for result text (e.g. fr); empty = provider default.
	Language string
	Limit    int
}

// Autocomplete limits.
const (
	MaxAutocompleteInput   = 120
	MaxSuggestions         = 5
	DefaultBiasRadiusMeter = 50_000.0 // also the Places API maximum
)

var (
	sessionTokenRe = regexp.MustCompile(`^[A-Za-z0-9_-]{8,36}$`)
	countryRe      = regexp.MustCompile(`^[A-Z]{2}$`)
	languageRe     = regexp.MustCompile(`^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$`)
)

// ErrInvalidQuery is returned for malformed autocomplete parameters.
var ErrInvalidQuery = errors.New("invalid autocomplete query")

// Normalize trims the input (first character is enough), applies defaults and validates the parameters. A session
// token must be URL-safe base64 of 8–36 characters, as Google Places requires.
func (q AutocompleteQuery) Normalize() (AutocompleteQuery, error) {
	q.Input = strings.Join(strings.Fields(q.Input), " ")
	if len(q.Input) > MaxAutocompleteInput {
		q.Input = q.Input[:MaxAutocompleteInput]
	}
	q.Country = strings.ToUpper(strings.TrimSpace(q.Country))
	if q.Country == "" {
		q.Country = "US"
	}
	if q.Limit <= 0 || q.Limit > MaxSuggestions {
		q.Limit = MaxSuggestions
	}
	var problems []error
	if !countryRe.MatchString(q.Country) {
		problems = append(problems, errors.New("country must be an ISO 3166-1 alpha-2 code"))
	}
	if q.SessionToken != "" && !sessionTokenRe.MatchString(q.SessionToken) {
		problems = append(problems, errors.New("session must be 8–36 URL-safe characters"))
	}
	switch q.Kind {
	case KindAny, KindAddress, KindPostalCode, KindLocality:
	default:
		problems = append(problems, fmt.Errorf("kind must be address, postal_code or locality, not %q", q.Kind))
	}
	if q.Language != "" && !languageRe.MatchString(q.Language) {
		problems = append(problems, errors.New("lang must be a BCP 47 language tag"))
	}
	if q.Near != nil {
		if err := CheckPoint(q.Near.Lat, q.Near.Lng); err != nil {
			problems = append(problems, err)
		}
		if q.RadiusMeters <= 0 || q.RadiusMeters > DefaultBiasRadiusMeter || math.IsNaN(q.RadiusMeters) {
			q.RadiusMeters = DefaultBiasRadiusMeter
		}
	} else {
		q.RadiusMeters = 0
	}
	if len(problems) > 0 {
		return q, errors.Join(append([]error{ErrInvalidQuery}, problems...)...)
	}
	return q, nil
}

// Autocompletion is the answer to one keystroke: suggestions and the session token to send with the next keystroke
// and with the resolve of the picked suggestion.
type Autocompletion struct {
	Suggestions []Suggestion `json:"suggestions"`
	Session     string       `json:"session"`
}

// HaversineMeters is the great-circle distance between two points (for ranking suggestions near a bias point; road
// distances come from the distance service).
func HaversineMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const earthMeters = 6_371_000.0
	rad := math.Pi / 180
	dLat, dLon := (lat2-lat1)*rad, (lon2-lon1)*rad
	h := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * earthMeters * math.Asin(math.Sqrt(h))
}
