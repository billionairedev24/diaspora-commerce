// Package app holds the geo use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// AddressService powers every address field: autocomplete, resolve, validate and ZIP lookup.
type AddressService interface {
	Autocomplete(ctx context.Context, input, sessionToken, country string) ([]domain.Suggestion, error)
	Resolve(ctx context.Context, id, sessionToken string) (domain.Address, error)
	Validate(ctx context.Context, a domain.Address) (domain.Validation, error)
	LookupZip(ctx context.Context, zip string) (domain.Address, error)
}

type addressServiceImpl struct {
	providers []port.Autocompleter // ordered: primary first, then fallbacks
	validator port.Validator
	fallback  port.Validator
	log       *slog.Logger
}

var _ AddressService = (*addressServiceImpl)(nil)

// ErrNotFound is returned when nothing matches.
var ErrNotFound = errors.New("not found")

// NewAddressService returns the address use cases. If the primary provider fails, the next one answers,
// so typing never shows an empty dropdown because a vendor had an outage.
func NewAddressService(providers []port.Autocompleter, validator, fallback port.Validator, log *slog.Logger) AddressService {
	return &addressServiceImpl{providers: providers, validator: validator, fallback: fallback, log: log}
}

const maxSuggestions = 5

func (s *addressServiceImpl) Autocomplete(ctx context.Context, input, session, country string) ([]domain.Suggestion, error) {
	input = strings.Join(strings.Fields(input), " ")
	if input == "" {
		return []domain.Suggestion{}, nil
	}
	if len(input) > 120 {
		input = input[:120]
	}
	if country == "" {
		country = "US"
	}
	var errs []error
	for _, p := range s.providers {
		out, err := p.Suggest(ctx, input, session, country, maxSuggestions)
		if err == nil {
			return out, nil
		}
		s.log.WarnContext(ctx, "autocomplete provider failed", "provider", p.Name(), "error", err)
		errs = append(errs, fmt.Errorf("%s: %w", p.Name(), err))
	}
	return nil, errors.Join(errs...)
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

func (s *addressServiceImpl) Validate(ctx context.Context, a domain.Address) (domain.Validation, error) {
	a, err := a.Normalize()
	if err != nil {
		return domain.Validation{}, fmt.Errorf("validate: %w", err)
	}
	if a.Country != "US" || s.validator == nil {
		v, err := s.fallback.Validate(ctx, a)
		if err != nil {
			return domain.Validation{}, fmt.Errorf("validate: %w", err)
		}
		return v, nil
	}
	v, err := s.validator.Validate(ctx, a)
	if err != nil {
		s.log.WarnContext(ctx, "validator failed; format-only result", "validator", s.validator.Name(), "error", err)
		v, err = s.fallback.Validate(ctx, a)
		if err != nil {
			return domain.Validation{}, fmt.Errorf("validate: %w", err)
		}
	}
	return v, nil
}

func (s *addressServiceImpl) LookupZip(ctx context.Context, zip string) (domain.Address, error) {
	zip = strings.TrimSpace(zip)
	if len(zip) != 5 || strings.Trim(zip, "0123456789") != "" {
		return domain.Address{}, fmt.Errorf("zip %q: %w", zip, domain.ErrInvalidAddress)
	}
	sugs, err := s.Autocomplete(ctx, zip, "", "US")
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
