// Package domain holds address, suggestion, validation and zone types.
package domain

import (
	"errors"
	"regexp"
	"strings"
)

// Suggestion is one autocomplete row.
type Suggestion struct {
	ID            string `json:"id"`
	Text          string `json:"text"`
	MainText      string `json:"mainText"`
	SecondaryText string `json:"secondaryText"`
	Provider      string `json:"provider"`
}

// Address is a structured postal address.
type Address struct {
	Line1      string  `json:"line1"`
	Line2      string  `json:"line2,omitempty"`
	City       string  `json:"city"`
	State      string  `json:"state"`
	PostalCode string  `json:"postalCode"`
	Country    string  `json:"country"`
	Lat        float64 `json:"lat,omitempty"`
	Lon        float64 `json:"lon,omitempty"`
}

// ValidationStatus of an address check.
type ValidationStatus string

// Validation outcomes.
const (
	StatusValid         ValidationStatus = "VALID"
	StatusCorrected     ValidationStatus = "CORRECTED"     // a standardised form differs; suggest, never auto-apply
	StatusMissingUnit   ValidationStatus = "MISSING_UNIT"  // multi-unit building without apt/suite
	StatusUndeliverable ValidationStatus = "UNDELIVERABLE" // not a deliverable USPS address
	StatusUnverified    ValidationStatus = "UNVERIFIED"    // provider unavailable; format checks passed
)

// Validation is the result of checking an address.
type Validation struct {
	Status      ValidationStatus `json:"status"`
	Standard    *Address         `json:"standardized,omitempty"`
	Suggestions []Address        `json:"suggestions"`
	Residential *bool            `json:"residential,omitempty"`
	Messages    []string         `json:"messages"`
}

// ErrInvalidAddress is returned when an address fails basic format rules.
var ErrInvalidAddress = errors.New("invalid address")

var (
	zipRe   = regexp.MustCompile(`^\d{5}(-\d{4})?$`)
	stateRe = regexp.MustCompile(`^[A-Z]{2}$`)
)

// Normalize trims fields, uppercases state and country and checks the US format rules.
func (a Address) Normalize() (Address, error) {
	a.Line1 = strings.Join(strings.Fields(a.Line1), " ")
	a.Line2 = strings.Join(strings.Fields(a.Line2), " ")
	a.City = strings.Join(strings.Fields(a.City), " ")
	a.State = strings.ToUpper(strings.TrimSpace(a.State))
	a.PostalCode = strings.TrimSpace(a.PostalCode)
	a.Country = strings.ToUpper(strings.TrimSpace(a.Country))
	if a.Country == "" {
		a.Country = "US"
	}
	var problems []error
	if len(a.Line1) < 3 {
		problems = append(problems, errors.New("street address is required"))
	}
	if a.City == "" {
		problems = append(problems, errors.New("city is required"))
	}
	if a.Country == "US" {
		if !stateRe.MatchString(a.State) || !IsUSState(a.State) {
			problems = append(problems, errors.New("state must be a US state code"))
		}
		if !zipRe.MatchString(a.PostalCode) {
			problems = append(problems, errors.New("ZIP code must be 5 digits or ZIP+4"))
		}
	}
	if len(problems) > 0 {
		return a, errors.Join(append([]error{ErrInvalidAddress}, problems...)...)
	}
	return a, nil
}

// Same reports whether two addresses are equal ignoring case and spacing (used to detect corrections).
func Same(a, b Address) bool {
	n := func(s string) string { return strings.ToUpper(strings.Join(strings.Fields(s), " ")) }
	return n(a.Line1) == n(b.Line1) && n(a.Line2) == n(b.Line2) && n(a.City) == n(b.City) &&
		n(a.State) == n(b.State) && n(a.PostalCode)[:min(5, len(n(a.PostalCode)))] ==
		n(b.PostalCode)[:min(5, len(n(b.PostalCode)))]
}
