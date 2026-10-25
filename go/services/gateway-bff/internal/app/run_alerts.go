package app

import (
	"context"
	"encoding/json"
	"net/mail"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// RunAlertService serves the storefront's delivery-run routes (contracts/openapi/gateway-bff.yaml, tag Delivery
// runs; S262, S263): the locations and their pages, subscriptions to a location's run alerts with consent per channel
// and the location-targeted advertorials. delivery-runs owns all of it (consent records, double opt-in, serving and
// counting); the BFF validates, supplies the shopper's place and relays the token of a signed-in shopper.
type RunAlertService interface {
	Locations(ctx context.Context) (map[string]any, error)
	LocationPage(ctx context.Context, slug string) (map[string]any, error)
	// Subscribe is the guest sign-up for one location by email (always accepted; the confirmation link follows).
	Subscribe(ctx context.Context, req RunSubscribeRequest) (map[string]any, error)
	Confirm(ctx context.Context, token string) (map[string]any, error)
	// Unsubscribe withdraws the channels (all when none are given) of the subscription a message link names.
	Unsubscribe(ctx context.Context, token string, channels []string) (map[string]any, error)
	Mine(ctx context.Context, c *domain.Caller) (map[string]any, error)
	SubscribeMine(ctx context.Context, c *domain.Caller, req MyRunSubscriptionRequest) (map[string]any, error)
	UnsubscribeMine(ctx context.Context, c *domain.Caller, locationID string, channels []string) (map[string]any, error)
	// Advertorials serves the visitor's variants for the explicit place or the session's delivery location.
	Advertorials(ctx context.Context, c *domain.Caller, q AdvertorialQuery) (map[string]any, error)
	// AdvertorialEvent counts a VIEW or a CLICK (best effort: the storefront ignores failures).
	AdvertorialEvent(ctx context.Context, id, eventType string) error
}

// RunSubscribeRequest is POST /v1/delivery-runs/subscriptions.
type RunSubscribeRequest struct {
	LocationID string `json:"locationId"`
	Email      string `json:"email"`
	Source     string `json:"source"`
}

// MyRunSubscriptionRequest is POST /v1/account/run-subscriptions.
type MyRunSubscriptionRequest struct {
	LocationID string   `json:"locationId"`
	Channels   []string `json:"channels"`
	Source     string   `json:"source"`
}

// AdvertorialQuery is GET /v1/delivery-runs/advertorials; an empty place means the session's delivery location.
type AdvertorialQuery struct {
	ZIP     string
	Lat     string
	Lng     string
	Country string
	Region  string
	City    string
	Visitor string
	Limit   string
}

type runAlertServiceImpl struct {
	up       port.RunAlertClient
	location LocationService
	market   string
}

var _ RunAlertService = (*runAlertServiceImpl)(nil)

// NewRunAlertService returns the storefront delivery-run use case; market is the BFF's market ("us").
func NewRunAlertService(up port.RunAlertClient, location LocationService, market string) RunAlertService {
	return &runAlertServiceImpl{up: up, location: location, market: market}
}

// runChannels are delivery-runs' consent channels; WHATSAPP is only effective once its templates are approved
// (notifications decides), but the consent is recorded either way.
var runChannels = map[string]bool{"EMAIL": true, "SMS": true, "PUSH": true, "WHATSAPP": true}

var (
	runSlugRe   = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,79}$`)
	runSourceRe = regexp.MustCompile(`^[a-z0-9][a-z0-9_.-]{0,79}$`)
	visitorRe   = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
)

// maxRunToken is delivery-runs' TokenRequest limit.
const maxRunToken = 200

// defaultRunSource is the consent source recorded when the caller names none.
const defaultRunSource = "storefront"

func runList(raw json.RawMessage) map[string]any {
	items := []any{}
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &items)
	}
	if items == nil {
		items = []any{}
	}
	return map[string]any{"items": items}
}

func runSource(source string) (string, error) {
	source = strings.ToLower(strings.TrimSpace(source))
	if source == "" {
		return defaultRunSource, nil
	}
	if !runSourceRe.MatchString(source) {
		return "", domain.ValidationProblem(map[string]string{"source": "Use lowercase letters, digits, dots, dashes or underscores"})
	}
	return source, nil
}

// runChannelList upper-cases, de-duplicates and checks channel names; none is allowed only when allowNone.
func runChannelList(in []string, allowNone bool) ([]string, error) {
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, ch := range in {
		ch = strings.ToUpper(strings.TrimSpace(ch))
		if !runChannels[ch] {
			return nil, domain.ValidationProblem(map[string]string{"channels": "Choose from EMAIL, SMS, PUSH and WHATSAPP"})
		}
		if !seen[ch] {
			seen[ch] = true
			out = append(out, ch)
		}
	}
	if len(out) == 0 && !allowNone {
		return nil, domain.ValidationProblem(map[string]string{"channels": "Choose at least one way to hear from us"})
	}
	return out, nil
}

func runToken(token string) (string, error) {
	token = strings.TrimSpace(token)
	if token == "" || len(token) > maxRunToken {
		return "", domain.ValidationProblem(map[string]string{"token": "This link is not valid"})
	}
	return token, nil
}

func runLocationID(id string) error {
	if p := domain.ValidUUID(id); p != nil {
		return domain.ValidationProblem(map[string]string{"locationId": "Choose a delivery location"})
	}
	return nil
}

func (s *runAlertServiceImpl) Locations(ctx context.Context) (map[string]any, error) {
	raw, err := s.up.Locations(ctx)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return runList(raw), nil
}

func (s *runAlertServiceImpl) LocationPage(ctx context.Context, slug string) (map[string]any, error) {
	slug = strings.ToLower(strings.TrimSpace(slug))
	if !runSlugRe.MatchString(slug) {
		return nil, domain.NewProblem(404, domain.CodeNotFound, "Not found", "We couldn't find that location.")
	}
	raw, err := s.up.LocationPage(ctx, slug)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return object(raw), nil
}

func (s *runAlertServiceImpl) Subscribe(ctx context.Context, req RunSubscribeRequest) (map[string]any, error) {
	fields := map[string]string{}
	if err := runLocationID(req.LocationID); err != nil {
		fields["locationId"] = "Choose a delivery location"
	}
	email := strings.TrimSpace(req.Email)
	if a, err := mail.ParseAddress(email); err != nil || a.Address != email || len(email) > 254 {
		fields["email"] = "Enter a valid email address"
	}
	source, err := runSource(req.Source)
	if err != nil {
		fields["source"] = "Use lowercase letters, digits, dots, dashes or underscores"
	}
	if len(fields) > 0 {
		return nil, domain.ValidationProblem(fields)
	}
	raw, err := s.up.SubscribeEmail(ctx, req.LocationID, email, source)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	_ = raw // delivery-runs answers 202 whether or not the address was known
	return map[string]any{"status": "CONFIRMATION_SENT"}, nil
}

func (s *runAlertServiceImpl) Confirm(ctx context.Context, token string) (map[string]any, error) {
	token, err := runToken(token)
	if err != nil {
		return nil, err
	}
	raw, err := s.up.Confirm(ctx, token)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return object(raw), nil
}

func (s *runAlertServiceImpl) Unsubscribe(ctx context.Context, token string, channels []string) (map[string]any, error) {
	token, err := runToken(token)
	if err != nil {
		return nil, err
	}
	chs, err := runChannelList(channels, true)
	if err != nil {
		return nil, err
	}
	raw, err := s.up.Unsubscribe(ctx, token, chs)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return object(raw), nil
}

func (s *runAlertServiceImpl) Mine(ctx context.Context, c *domain.Caller) (map[string]any, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	raw, err := s.up.Mine(ctx, c.BearerToken)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return runList(raw), nil
}

func (s *runAlertServiceImpl) SubscribeMine(ctx context.Context, c *domain.Caller, req MyRunSubscriptionRequest) (map[string]any, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	fields := map[string]string{}
	if err := runLocationID(req.LocationID); err != nil {
		fields["locationId"] = "Choose a delivery location"
	}
	chs, err := runChannelList(req.Channels, false)
	if err != nil {
		fields["channels"] = "Choose at least one way to hear from us, from EMAIL, SMS, PUSH and WHATSAPP"
	}
	source, err := runSource(req.Source)
	if err != nil {
		fields["source"] = "Use lowercase letters, digits, dots, dashes or underscores"
	}
	if len(fields) > 0 {
		return nil, domain.ValidationProblem(fields)
	}
	raw, err := s.up.SubscribeMine(ctx, c.BearerToken, req.LocationID, chs, source)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return object(raw), nil
}

func (s *runAlertServiceImpl) UnsubscribeMine(ctx context.Context, c *domain.Caller, locationID string, channels []string) (map[string]any, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	if p := domain.ValidUUID(locationID); p != nil {
		return nil, p
	}
	chs, err := runChannelList(channels, true)
	if err != nil {
		return nil, err
	}
	raw, err := s.up.UnsubscribeMine(ctx, c.BearerToken, locationID, chs)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	return object(raw), nil
}

// place builds the upstream query: an explicit ZIP, coordinates or country and region win; otherwise the session's
// delivery location (its ZIP, with the state and city so pre-order windows match too). No place at all gives nil.
func (s *runAlertServiceImpl) place(ctx context.Context, c *domain.Caller, q AdvertorialQuery) (url.Values, error) {
	out := url.Values{}
	fields := map[string]string{}
	switch {
	case strings.TrimSpace(q.ZIP) != "":
		if !zipRe.MatchString(strings.TrimSpace(q.ZIP)) {
			fields["zip"] = "Enter a 5-digit ZIP code"
		}
		out.Set("zip", strings.TrimSpace(q.ZIP))
	case q.Lat != "" || q.Lng != "":
		lat, e1 := strconv.ParseFloat(q.Lat, 64)
		lng, e2 := strconv.ParseFloat(q.Lng, 64)
		if e1 != nil || e2 != nil || lat < -90 || lat > 90 || lng < -180 || lng > 180 {
			fields["lat"] = "Give both coordinates as numbers"
		}
		out.Set("lat", q.Lat)
		out.Set("lng", q.Lng)
	}
	if strings.TrimSpace(q.Country) != "" || strings.TrimSpace(q.Region) != "" || strings.TrimSpace(q.City) != "" {
		if strings.TrimSpace(q.Region) == "" && strings.TrimSpace(q.City) != "" {
			fields["region"] = "Give the region (state) with the city"
		} else {
			p, errs := domain.NormalizePlace(firstNonEmpty(q.Country, s.market), q.Region, q.City)
			if len(errs) > 0 {
				for k, v := range errs {
					fields[k] = v
				}
			} else {
				out.Set("country", p.Country)
				out.Set("region", p.Region)
				if p.City != "" {
					out.Set("city", p.City)
				}
			}
		}
	}
	if len(fields) > 0 {
		return nil, domain.ValidationProblem(fields)
	}
	if len(out) > 0 {
		return out, nil
	}
	if c == nil || c.ZIP() == "" {
		return nil, nil
	}
	loc := s.location.Current(ctx, c)
	out.Set("zip", c.ZIP())
	if p, errs := domain.NormalizePlace(s.market, loc.State, loc.City); len(errs) == 0 {
		out.Set("country", p.Country)
		out.Set("region", p.Region)
		if p.City != "" {
			out.Set("city", p.City)
		}
	}
	return out, nil
}

func (s *runAlertServiceImpl) Advertorials(ctx context.Context, c *domain.Caller, q AdvertorialQuery) (map[string]any, error) {
	limit := 3
	if v := strings.TrimSpace(q.Limit); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 10 {
			return nil, domain.ValidationProblem(map[string]string{"limit": "Use a number from 1 to 10"})
		}
		limit = n
	}
	if v := strings.TrimSpace(q.Visitor); v != "" && !visitorRe.MatchString(v) {
		return nil, domain.ValidationProblem(map[string]string{"visitor": "Use up to 64 letters, digits, dashes or underscores"})
	}
	place, err := s.place(ctx, c, q)
	if err != nil {
		return nil, err
	}
	if place == nil {
		return map[string]any{"items": []any{}}, nil
	}
	place.Set("limit", strconv.Itoa(limit))
	if v := strings.TrimSpace(q.Visitor); v != "" {
		place.Set("visitor", v)
	}
	raw, err := s.up.Advertorials(ctx, place)
	if err != nil {
		return nil, mapErr("delivery-runs", err)
	}
	out := object(raw)
	if _, ok := out["items"].([]any); !ok {
		out["items"] = []any{}
	}
	return out, nil
}

func (s *runAlertServiceImpl) AdvertorialEvent(ctx context.Context, id, eventType string) error {
	if p := domain.ValidUUID(id); p != nil {
		return p
	}
	eventType = strings.ToUpper(strings.TrimSpace(eventType))
	if eventType != "VIEW" && eventType != "CLICK" {
		return domain.ValidationProblem(map[string]string{"type": "Use VIEW or CLICK"})
	}
	return mapErr("delivery-runs", s.up.AdvertorialEvent(ctx, id, eventType))
}
