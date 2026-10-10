package app

import (
	"context"
	"encoding/json"
	"net/mail"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// PreorderService serves the storefront's pre-order routes (contracts/openapi/gateway-bff.yaml, tag Pre-orders): the
// window covering the shopper's delivery location (ribbon, hero, badges, PDP), the /pre-orders page list and the
// waitlist of areas a pre-order does not cover yet. delivery-runs answers; the BFF supplies the place.
type PreorderService interface {
	// Resolve answers for the explicit place when country and region are given, else for the session's delivery
	// location (market country, state, city).
	Resolve(ctx context.Context, c *domain.Caller, country, region, city string) (map[string]any, error)
	Open(ctx context.Context) (map[string]any, error)
	Waitlist(ctx context.Context, c *domain.Caller, req PreorderWaitlistRequest) (map[string]any, error)
}

// PreorderWaitlistRequest is POST /v1/preorders/waitlist; the area defaults to the session's delivery location.
type PreorderWaitlistRequest struct {
	Email   string `json:"email"`
	Country string `json:"country"`
	Region  string `json:"region"`
	City    string `json:"city"`
}

type preorderServiceImpl struct {
	up       port.PreorderClient
	location LocationService
	market   string
}

var _ PreorderService = (*preorderServiceImpl)(nil)

// NewPreorderService returns the storefront pre-order use case; market is the BFF's market ("us").
func NewPreorderService(up port.PreorderClient, location LocationService, market string) PreorderService {
	return &preorderServiceImpl{up: up, location: location, market: market}
}

// place picks the explicit place or the session's delivery location.
func (s *preorderServiceImpl) place(ctx context.Context, c *domain.Caller, country, region, city string) (domain.PreorderPlace, error) {
	source := "query"
	if strings.TrimSpace(country) == "" && strings.TrimSpace(region) == "" {
		if strings.TrimSpace(city) != "" {
			return domain.PreorderPlace{}, domain.ValidationProblem(map[string]string{"region": "Give the region (state) with the city"})
		}
		loc := s.location.Current(ctx, c)
		country, region, city, source = s.market, loc.State, loc.City, "session"
		if c == nil || c.ZIP() == "" {
			source = "default"
		}
	}
	if strings.TrimSpace(country) == "" {
		country = s.market
	}
	p, errs := domain.NormalizePlace(country, region, city)
	if len(errs) > 0 {
		return domain.PreorderPlace{}, domain.ValidationProblem(errs)
	}
	p.Source = source
	return p, nil
}

// object decodes a relayed JSON object (anything else becomes an empty object).
func object(raw json.RawMessage) map[string]any {
	m := map[string]any{}
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &m)
	}
	if m == nil {
		m = map[string]any{}
	}
	return m
}

func (s *preorderServiceImpl) Resolve(ctx context.Context, c *domain.Caller, country, region, city string) (map[string]any, error) {
	p, err := s.place(ctx, c, country, region, city)
	if err != nil {
		return nil, err
	}
	raw, err := s.up.Resolve(ctx, port.PreorderArea{Country: p.Country, Region: p.Region, City: p.City})
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	out := object(raw)
	out["place"] = p
	if _, ok := out["on"]; !ok {
		out["on"] = false
	}
	return out, nil
}

func (s *preorderServiceImpl) Open(ctx context.Context) (map[string]any, error) {
	raw, err := s.up.Open(ctx)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	trimmed := strings.TrimSpace(string(raw))
	if strings.HasPrefix(trimmed, "[") {
		var items []any
		_ = json.Unmarshal(raw, &items)
		if items == nil {
			items = []any{}
		}
		return map[string]any{"items": items}, nil
	}
	out := object(raw)
	if _, ok := out["items"].([]any); !ok {
		out["items"] = []any{}
	}
	return out, nil
}

func (s *preorderServiceImpl) Waitlist(ctx context.Context, c *domain.Caller, req PreorderWaitlistRequest) (map[string]any, error) {
	email := strings.TrimSpace(req.Email)
	if a, err := mail.ParseAddress(email); err != nil || a.Address != email || len(email) > 254 {
		return nil, domain.ValidationProblem(map[string]string{"email": "Enter a valid email address"})
	}
	p, err := s.place(ctx, c, req.Country, req.Region, req.City)
	if err != nil {
		return nil, err
	}
	raw, err := s.up.Waitlist(ctx, port.PreorderWaitlist{Email: email, Area: p.Key(), Country: p.Country, Region: p.Region, City: p.City,
		Source: domain.PreorderWaitlistSource})
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	out := object(raw)
	if _, ok := out["status"].(string); !ok {
		out["status"] = "CONFIRMATION_SENT"
	}
	return map[string]any{"status": out["status"], "area": p.Key()}, nil
}
