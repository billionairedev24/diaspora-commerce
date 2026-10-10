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

func (p *placesImpl) Suggest(ctx context.Context, input, session, country string, limit int) ([]domain.Suggestion, error) {
	body, err := json.Marshal(map[string]any{
		"input":                input,
		"sessionToken":         session,
		"includedRegionCodes":  []string{country},
		"includedPrimaryTypes": []string{"street_address", "premise", "subpremise", "route", "postal_code"},
	})
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
			Provider: p.Name()})
		if len(res) == limit {
			break
		}
	}
	return res, nil
}

type placeDetails struct {
	AddressComponents []struct {
		LongText  string   `json:"longText"`
		ShortText string   `json:"shortText"`
		Types     []string `json:"types"`
	} `json:"addressComponents"`
	Location struct {
		Latitude  float64 `json:"latitude"`
		Longitude float64 `json:"longitude"`
	} `json:"location"`
}

func (p *placesImpl) Resolve(ctx context.Context, id, session string) (domain.Address, error) {
	placeID := id
	if len(id) > 2 && id[:2] == "g:" {
		placeID = id[2:]
	}
	u := p.base + "/places/" + url.PathEscape(placeID) + "?sessionToken=" + url.QueryEscape(session)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return domain.Address{}, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("X-Goog-Api-Key", p.key)
	req.Header.Set("X-Goog-FieldMask", "addressComponents,location")
	var d placeDetails
	if err := p.do(req, &d); err != nil {
		return domain.Address{}, err
	}
	var a domain.Address
	var number, route string
	for _, c := range d.AddressComponents {
		for _, t := range c.Types {
			switch t {
			case "street_number":
				number = c.LongText
			case "route":
				route = c.ShortText
			case "subpremise":
				a.Line2 = c.LongText
			case "locality", "postal_town":
				a.City = c.LongText
			case "administrative_area_level_1":
				a.State = c.ShortText
			case "postal_code":
				a.PostalCode = c.LongText
			case "country":
				a.Country = c.ShortText
			}
		}
	}
	a.Line1 = joinNonEmpty(number, route)
	a.Lat, a.Lon = d.Location.Latitude, d.Location.Longitude
	return a, nil
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
