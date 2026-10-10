// Package smarty implements port.Validator with the Smarty US Street API (USPS CASS/DPV), and provides the
// format-only fallback used when no Smarty credentials are configured.
package smarty

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type smartyImpl struct {
	authID, authToken, base string
	client                  *http.Client
}

type rulesImpl struct{}

var (
	_ port.Validator = (*smartyImpl)(nil)
	_ port.Validator = rulesImpl{}
)

// New returns a Smarty validator. base may be empty for the public endpoint.
func New(authID, authToken, base string, client *http.Client) port.Validator {
	if base == "" {
		base = "https://us-street.api.smarty.com"
	}
	return &smartyImpl{authID: authID, authToken: authToken, base: strings.TrimSuffix(base, "/"), client: client}
}

// NewRules returns the format-only validator (status UNVERIFIED when the format is fine).
func NewRules() port.Validator { return rulesImpl{} }

func (rulesImpl) Name() string { return "rules" }

func (rulesImpl) Validate(_ context.Context, a domain.Address) (domain.Validation, error) {
	return domain.Validation{Status: domain.StatusUnverified, Standard: &a, Suggestions: []domain.Address{},
		Messages: []string{"Address format looks right; deliverability was not verified."}}, nil
}

func (s *smartyImpl) Name() string { return "smarty" }

// Candidate is one Smarty US Street API result.
type Candidate struct {
	DeliveryLine1 string `json:"delivery_line_1"`
	DeliveryLine2 string `json:"delivery_line_2"`
	Components    struct {
		CityName            string `json:"city_name"`
		StateAbbreviation   string `json:"state_abbreviation"`
		Zipcode             string `json:"zipcode"`
		Plus4Code           string `json:"plus4_code"`
		SecondaryNumber     string `json:"secondary_number"`
		SecondaryDesignator string `json:"secondary_designator"`
	} `json:"components"`
	Metadata struct {
		RDI       string  `json:"rdi"`
		Latitude  float64 `json:"latitude"`
		Longitude float64 `json:"longitude"`
	} `json:"metadata"`
	Analysis struct {
		DPVMatchCode string `json:"dpv_match_code"`
		DPVFootnotes string `json:"dpv_footnotes"`
		DPVVacant    string `json:"dpv_vacant"`
	} `json:"analysis"`
}

func (s *smartyImpl) Validate(ctx context.Context, a domain.Address) (domain.Validation, error) {
	q := url.Values{"auth-id": {s.authID}, "auth-token": {s.authToken}, "street": {a.Line1},
		"secondary": {a.Line2}, "city": {a.City}, "state": {a.State}, "zipcode": {a.PostalCode},
		"candidates": {"3"}, "match": {"enhanced"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.base+"/street-address?"+q.Encode(), nil)
	if err != nil {
		return domain.Validation{}, fmt.Errorf("request: %w", err)
	}
	res, err := s.client.Do(req)
	if err != nil {
		return domain.Validation{}, fmt.Errorf("smarty: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return domain.Validation{}, fmt.Errorf("smarty: status %d", res.StatusCode)
	}
	var cs []Candidate
	if err := json.NewDecoder(res.Body).Decode(&cs); err != nil {
		return domain.Validation{}, fmt.Errorf("smarty decode: %w", err)
	}
	return Interpret(a, cs), nil
}

// Interpret maps Smarty candidates to a Validation. Corrections are suggested, never silently applied.
func Interpret(input domain.Address, cs []Candidate) domain.Validation {
	v := domain.Validation{Suggestions: []domain.Address{}, Messages: []string{}}
	if len(cs) == 0 {
		v.Status = domain.StatusUndeliverable
		v.Messages = append(v.Messages, "We couldn't find this address. Check the street and ZIP code.")
		return v
	}
	for _, c := range cs {
		v.Suggestions = append(v.Suggestions, toAddress(c))
	}
	best := cs[0]
	std := toAddress(best)
	v.Standard = &std
	residential := best.Metadata.RDI == "Residential"
	v.Residential = &residential
	switch best.Analysis.DPVMatchCode {
	case "Y":
		if domain.Same(input, std) {
			v.Status = domain.StatusValid
		} else {
			v.Status = domain.StatusCorrected
			v.Messages = append(v.Messages, "Did you mean "+std.Line1+", "+std.City+", "+std.State+" "+std.PostalCode+"?")
		}
	case "D":
		v.Status = domain.StatusMissingUnit
		v.Messages = append(v.Messages, "This building has multiple units. Add an apartment, suite or unit number.")
	case "S":
		v.Status = domain.StatusCorrected
		v.Messages = append(v.Messages, "The apartment or suite number wasn't recognised. Please check it.")
	default:
		v.Status = domain.StatusUndeliverable
		v.Messages = append(v.Messages, "USPS can't deliver to this address.")
	}
	if best.Analysis.DPVVacant == "Y" {
		v.Messages = append(v.Messages, "USPS lists this address as vacant.")
	}
	return v
}

func toAddress(c Candidate) domain.Address {
	zip := c.Components.Zipcode
	if c.Components.Plus4Code != "" {
		zip += "-" + c.Components.Plus4Code
	}
	line2 := strings.TrimSpace(c.Components.SecondaryDesignator + " " + c.Components.SecondaryNumber)
	line1 := c.DeliveryLine1
	if line2 != "" {
		line1 = strings.TrimSpace(strings.TrimSuffix(line1, line2))
	}
	return domain.Address{Line1: line1, Line2: line2, City: c.Components.CityName,
		State: c.Components.StateAbbreviation, PostalCode: zip, Country: "US",
		Lat: c.Metadata.Latitude, Lon: c.Metadata.Longitude}
}
