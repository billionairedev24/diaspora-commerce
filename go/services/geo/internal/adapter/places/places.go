// Package places implements port.Autocompleter with Google Places API (New). Session tokens group the
// keystrokes and the final details call into one billable session; the API key never leaves the server.
package places

import (
	"bytes"
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

const defaultBase = "https://places.googleapis.com/v1"

type placesImpl struct {
	key    string
	base   string
	client *http.Client
}

var _ port.Autocompleter = (*placesImpl)(nil)

// New returns a Google Places autocompleter. base may be empty for the public endpoint (tests pass httptest URLs).
func New(apiKey, base string, client *http.Client) port.Autocompleter {
	if base == "" {
		base = defaultBase
	}
	return &placesImpl{key: apiKey, base: base, client: client}
}

func (p *placesImpl) Name() string { return "google-places" }

type autocompleteResponse struct {
	Suggestions []struct {
		PlacePrediction struct {
			PlaceID string `json:"placeId"`
			Text    struct {
				Text string `json:"text"`
			} `json:"text"`
			DistanceMeters   *float64 `json:"distanceMeters"`
			Types            []string `json:"types"`
			StructuredFormat struct {
				MainText struct {
					Text string `json:"text"`
				} `json:"mainText"`
				SecondaryText struct {
					Text string `json:"text"`
				} `json:"secondaryText"`
			} `json:"structuredFormat"`
		} `json:"placePrediction"`
	} `json:"suggestions"`
}

// primaryTypes are the Places (New) types each kind asks for (at most five).
func primaryTypes(k domain.AddressKind) []string {
	switch k {
	case domain.KindAddress:
		return []string{"street_address", "premise", "subpremise", "route"}
	case domain.KindPostalCode:
		return []string{"postal_code"}
	case domain.KindLocality:
		return []string{"locality", "sublocality", "postal_town", "administrative_area_level_3"}
	default:
		return []string{"street_address", "premise", "subpremise", "route", "postal_code"}
	}
}

func (p *placesImpl) Suggest(ctx context.Context, q domain.AutocompleteQuery) ([]domain.Suggestion, error) {
	payload := map[string]any{
		"input":                q.Input,
		"includedRegionCodes":  []string{q.Country},
		"regionCode":           strings.ToLower(q.Country),
		"includedPrimaryTypes": primaryTypes(q.Kind),
	}
	if q.SessionToken != "" {
		payload["sessionToken"] = q.SessionToken
	}
	if q.Language != "" {
		payload["languageCode"] = q.Language
	}
	if q.Near != nil {
		// A bias, not a restriction: nearby places rank first, others still appear. The origin returns distances.
		center := map[string]float64{"latitude": q.Near.Lat, "longitude": q.Near.Lng}
		payload["locationBias"] = map[string]any{"circle": map[string]any{"center": center, "radius": q.RadiusMeters}}
		payload["origin"] = center
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.base+"/places:autocomplete", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Goog-Api-Key", p.key)
	var out autocompleteResponse
	if err := p.do(req, &out); err != nil {
		return nil, err
	}
	res := make([]domain.Suggestion, 0, len(out.Suggestions))
	for _, s := range out.Suggestions {
		pp := s.PlacePrediction
		if pp.PlaceID == "" {
			continue
		}
		res = append(res, domain.Suggestion{ID: "g:" + pp.PlaceID, Text: pp.Text.Text,
			MainText: pp.StructuredFormat.MainText.Text, SecondaryText: pp.StructuredFormat.SecondaryText.Text,
			Provider: p.Name(), Kind: kindOf(pp.Types), DistanceMeters: pp.DistanceMeters})
		if len(res) == q.Limit {
			break
		}
	}
	return res, nil
}

// kindOf maps Places types to an address kind.
func kindOf(types []string) domain.AddressKind {
	for _, t := range types {
		switch t {
		case "street_address", "premise", "subpremise", "route":
			return domain.KindAddress
		case "postal_code":
			return domain.KindPostalCode
		case "locality", "sublocality", "postal_town", "administrative_area_level_3":
			return domain.KindLocality
		}
	}
	return domain.KindAny
}

type addressComponent struct {
	LongText  string   `json:"longText"`
	ShortText string   `json:"shortText"`
	Types     []string `json:"types"`
}

type placeDetails struct {
	FormattedAddress  string             `json:"formattedAddress"`
	AddressComponents []addressComponent `json:"addressComponents"`
	Location          struct {
		Latitude  float64 `json:"latitude"`
		Longitude float64 `json:"longitude"`
	} `json:"location"`
}

// detailsFieldMask asks Place Details (New) for the Essentials fields only (address components, formatted address,
// location): the cheapest Place Details SKU, and the one that ends an autocomplete session.
const detailsFieldMask = "addressComponents,formattedAddress,location"

func (p *placesImpl) Resolve(ctx context.Context, id, session string) (domain.Address, error) {
	placeID := strings.TrimPrefix(id, "g:")
	u := p.base + "/places/" + url.PathEscape(placeID)
	if session != "" {
		// Closes the autocomplete session: the keystrokes and this details call are billed as one session.
		u += "?sessionToken=" + url.QueryEscape(session)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return domain.Address{}, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("X-Goog-Api-Key", p.key)
	req.Header.Set("X-Goog-FieldMask", detailsFieldMask)
	var d placeDetails
	if err := p.do(req, &d); err != nil {
		return domain.Address{}, err
	}
	a := addressOf(d.AddressComponents, d.FormattedAddress)
	a.Lat, a.Lon = d.Location.Latitude, d.Location.Longitude
	return a, nil
}

// cityTypes are the components that can name the city, best first: locality (most of the world), postal_town (UK,
// Sweden), sublocality (New York boroughs: Brooklyn, Queens), then smaller units Google uses where there is no
// locality (unincorporated US areas, some African and Caribbean addresses).
var cityTypes = []string{"locality", "postal_town", "sublocality_level_1", "sublocality", "administrative_area_level_3",
	"neighborhood", "administrative_area_level_2"}

// addressOf maps Place Details (New) address components to a postal address: street number + route as line 1
// (premise or the first part of the formatted address when there is no street), subpremise as line 2, the city from
// the best of cityTypes, the first-level administrative area's short name (TX, ON, Lagos) as the state, the postal
// code and the country's ISO code.
func addressOf(components []addressComponent, formatted string) domain.Address {
	long, short := map[string]string{}, map[string]string{}
	for _, c := range components {
		for _, t := range c.Types {
			if _, seen := long[t]; !seen {
				long[t], short[t] = strings.TrimSpace(c.LongText), strings.TrimSpace(c.ShortText)
			}
		}
	}
	var a domain.Address
	route := firstNonEmpty(short["route"], long["route"])
	a.Line1 = joinNonEmpty(long["street_number"], route)
	if a.Line1 == "" {
		a.Line1 = firstNonEmpty(long["premise"], long["establishment"], strings.TrimSpace(strings.Split(formatted, ",")[0]))
	}
	if sub := long["subpremise"]; sub != "" {
		a.Line2 = sub
		if isUnitNumber(sub) {
			a.Line2 = "#" + sub
		}
	}
	for _, t := range cityTypes {
		if v := long[t]; v != "" {
			a.City = v
			break
		}
	}
	a.State = firstNonEmpty(short["administrative_area_level_1"], long["administrative_area_level_1"])
	a.PostalCode = long["postal_code"]
	a.Country = strings.ToUpper(short["country"])
	return a
}

// isUnitNumber is a bare unit such as "4B" or "210" (Google drops the "Apt"/"Suite" word): shown as "#4B".
func isUnitNumber(s string) bool {
	if s == "" || len(s) > 8 {
		return false
	}
	for _, r := range s {
		if !(r >= '0' && r <= '9' || r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z' || r == '-') {
			return false
		}
	}
	return strings.ContainsAny(s, "0123456789")
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func (p *placesImpl) do(req *http.Request, out any) error {
	res, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("google places: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1024))
		return fmt.Errorf("google places: status %d: %s", res.StatusCode, msg)
	}
	if err := json.NewDecoder(res.Body).Decode(out); err != nil {
		return fmt.Errorf("google places decode: %w", err)
	}
	return nil
}

func joinNonEmpty(a, b string) string {
	switch {
	case a == "":
		return b
	case b == "":
		return a
	default:
		return a + " " + b
	}
}
