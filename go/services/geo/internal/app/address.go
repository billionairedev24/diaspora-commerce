// Package app holds the geo use cases.
package app

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// AddressService powers every address field: autocomplete, resolve, validate and ZIP lookup.
type AddressService interface {
	// Autocomplete suggests addresses from the first character. It returns the session token to reuse for the
	// following keystrokes and the resolve (a new one when the query had none).
	Autocomplete(ctx context.Context, q domain.AutocompleteQuery) (domain.Autocompletion, error)
	// Resolve turns a picked suggestion into street, unit, city, state, ZIP, country and coordinates.
	Resolve(ctx context.Context, id, sessionToken string) (domain.Address, error)
	// Validate checks that Google Maps knows the address: VALID or NOT_FOUND. Google being unreachable, or no Google key
	// (local / offline development; uat and prod refuse to start without one), accepts the address as VALID.
	Validate(ctx context.Context, req domain.ValidateRequest) (domain.Validation, error)
	LookupZip(ctx context.Context, zip string) (domain.Address, error)
}

type addressServiceImpl struct {
	providers []port.Autocompleter // ordered: primary first, then fallbacks
	validator port.Validator       // nil without a Google key: every address is accepted (local / offline only)
	log       *slog.Logger
}

var _ AddressService = (*addressServiceImpl)(nil)

// ErrNotFound is returned when nothing matches.
var ErrNotFound = errors.New("not found")

// NewAddressService returns the address use cases. If the primary provider fails, the next one answers,
// so typing never shows an empty dropdown because a vendor had an outage.
func NewAddressService(providers []port.Autocompleter, validator port.Validator, log *slog.Logger) AddressService {
	return &addressServiceImpl{providers: providers, validator: validator, log: log}
}

func (s *addressServiceImpl) Autocomplete(ctx context.Context, q domain.AutocompleteQuery) (domain.Autocompletion, error) {
	q, err := q.Normalize()
	if err != nil {
		return domain.Autocompletion{}, fmt.Errorf("autocomplete: %w", err)
	}
	if q.SessionToken == "" {
		q.SessionToken = newSessionToken()
	}
	out := domain.Autocompletion{Suggestions: []domain.Suggestion{}, Session: q.SessionToken}
	if q.Input == "" {
		return out, nil
	}
	sugs, err := s.suggest(ctx, q)
	if err != nil {
		return domain.Autocompletion{}, err
	}
	out.Suggestions = sugs
	return out, nil
}

// suggest asks the providers in order; the first that answers wins.
func (s *addressServiceImpl) suggest(ctx context.Context, q domain.AutocompleteQuery) ([]domain.Suggestion, error) {
	var errs []error
	for _, p := range s.providers {
		out, err := p.Suggest(ctx, q)
		if err == nil {
			if out == nil {
				out = []domain.Suggestion{}
			}
			return out, nil
		}
		s.log.WarnContext(ctx, "autocomplete provider failed", "provider", p.Name(), "error", err)
		errs = append(errs, fmt.Errorf("%s: %w", p.Name(), err))
	}
	return nil, errors.Join(errs...)
}

// newSessionToken is a URL-safe random token (22 characters), valid as a Google Places session token.
func newSessionToken() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func (s *addressServiceImpl) Resolve(ctx context.Context, id, session string) (domain.Address, error) {
	for _, p := range s.providers {
		if owns(p.Name(), id) {
			a, err := p.Resolve(ctx, id, session)
			if err != nil {
				return domain.Address{}, fmt.Errorf("resolve %s: %w", id, err)
			}
			return a, nil
		}
	}
	return domain.Address{}, fmt.Errorf("resolve %s: %w", id, ErrNotFound)
}

func (s *addressServiceImpl) Validate(ctx context.Context, req domain.ValidateRequest) (domain.Validation, error) {
	a, err := req.Address.Normalize()
	if err != nil {
		return domain.Validation{}, fmt.Errorf("validate: %w", err)
	}
	// A suggestion the person picked and Google resolves to a structured street address is final.
	if strings.HasPrefix(req.PlaceID, "g:") {
		if r, err := s.Resolve(ctx, req.PlaceID, ""); err == nil && r.Line1 != "" && r.PostalCode != "" && (r.Lat != 0 || r.Lon != 0) {
			if r.Line2 == "" {
				r.Line2 = a.Line2
			}
			return domain.Validation{Status: domain.StatusValid, Standard: &r, PlaceID: strings.TrimPrefix(req.PlaceID, "g:"),
				Suggestions: []domain.Address{}, Messages: []string{}}, nil
		}
	}
	accepted := domain.Validation{Status: domain.StatusValid, Standard: &a, Suggestions: []domain.Address{}, Messages: []string{}}
	if s.validator == nil {
		return accepted, nil
	}
	v, err := s.validator.Validate(ctx, a)
	if err != nil {
		// Google itself is unreachable: never mark the address invalid, never warn.
		s.log.WarnContext(ctx, "address validator unavailable; address accepted", "validator", s.validator.Name(), "error", err)
		return accepted, nil
	}
	return v, nil
}

func (s *addressServiceImpl) LookupZip(ctx context.Context, zip string) (domain.Address, error) {
	zip = strings.TrimSpace(zip)
	if len(zip) != 5 || strings.Trim(zip, "0123456789") != "" {
		return domain.Address{}, fmt.Errorf("zip %q: %w", zip, domain.ErrInvalidAddress)
	}
	sugs, err := s.suggest(ctx, domain.AutocompleteQuery{Input: zip, Country: "US", Kind: domain.KindPostalCode,
		Limit: domain.MaxSuggestions})
	if err != nil {
		return domain.Address{}, err
	}
	for _, sg := range sugs {
		a, err := s.Resolve(ctx, sg.ID, "")
		if err == nil && strings.HasPrefix(a.PostalCode, zip) {
			return domain.Address{City: a.City, State: a.State, PostalCode: zip, Country: "US", Lat: a.Lat, Lon: a.Lon}, nil
		}
	}
	return domain.Address{}, fmt.Errorf("zip %s: %w", zip, ErrNotFound)
}

func owns(provider, id string) bool {
	switch provider {
	case "google-places":
		return strings.HasPrefix(id, "g:")
	case "osm-nominatim":
		return strings.HasPrefix(id, "o:")
	case "fixture":
		return strings.HasPrefix(id, "f:")
	default:
		return false
	}
}
