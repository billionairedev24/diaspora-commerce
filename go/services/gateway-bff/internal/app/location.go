package app

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// LocationService owns the session's deliver-to location (ZIP → geo; serviceability → content markets) and
// address autocomplete.
type LocationService interface {
	Current(ctx context.Context, c *domain.Caller) domain.DeliveryLocation
	SetZIP(ctx context.Context, c *domain.Caller, zip string) (domain.DeliveryLocation, error)
	SetCoordinates(ctx context.Context, c *domain.Caller, lat, lon float64) (domain.DeliveryLocation, error)
	// Suggest is one keystroke of an address field: up to five suggestions and the autocomplete session token to send
	// with the next keystroke and with ResolveSuggestion (a new one when sessionToken is blank). The country defaults
	// to the market's.
	Suggest(ctx context.Context, q, country, sessionToken string) (domain.AddressSuggestions, error)
	// ResolveSuggestion turns a picked suggestion into a structured address. With Google Places it is the Place
	// Details call that closes the session: one per address entry, never one per suggestion.
	ResolveSuggestion(ctx context.Context, id, sessionToken string) (domain.PostalAddress, error)
}

// LocationOptions configure the default location (used when the session has none or geo is down).
type LocationOptions struct {
	DefaultZIP, DefaultCity, DefaultState string
	// Country is the market's ISO 3166-1 alpha-2 country: address suggestions are restricted to it unless the
	// client asks for another (default US).
	Country string
	Log     *slog.Logger
}

type locationServiceImpl struct {
	geo      port.GeoClient
	content  port.ContentClient
	sessions SessionService
	o        LocationOptions
}

var _ LocationService = (*locationServiceImpl)(nil)

// NewLocationService returns the LocationService.
func NewLocationService(geo port.GeoClient, content port.ContentClient, sessions SessionService, o LocationOptions) LocationService {
	if o.Log == nil {
		o.Log = slog.Default()
	}
	o.Country = strings.ToUpper(strings.TrimSpace(o.Country))
	if !countryRe.MatchString(o.Country) {
		o.Country = "US"
	}
	return &locationServiceImpl{geo: geo, content: content, sessions: sessions, o: o}
}

var (
	zipRe = regexp.MustCompile(`^\d{5}$`)
	// countryRe is an ISO 3166-1 alpha-2 code; sessionTokenRe a Places session token (8–36 URL-safe characters).
	countryRe      = regexp.MustCompile(`^[A-Z]{2}$`)
	sessionTokenRe = regexp.MustCompile(`^[A-Za-z0-9_-]{8,36}$`)
)

func (l *locationServiceImpl) fallback() domain.DeliveryLocation {
	return domain.DeliveryLocation{ZIP: l.o.DefaultZIP, City: l.o.DefaultCity, State: l.o.DefaultState,
		Label: l.o.DefaultCity + " " + l.o.DefaultZIP, Serviceable: true}
}

// serviceable asks content's market eligibility (deliveryAllowed for country/state/ZIP/city: a city switched off or
// planned in Markets & locations is not served); unknown (content unavailable) counts as serviceable so shopping is
// never blocked by a missing dependency.
func (l *locationServiceImpl) serviceable(ctx context.Context, state, zip, city string) bool {
	e, err := l.content.Eligibility(ctx, "US", state, zip, city)
	if err != nil {
		return true
	}
	return e.DeliveryAllowed
}

func (l *locationServiceImpl) lookup(ctx context.Context, zip string) (domain.DeliveryLocation, error) {
	a, err := l.geo.Zip(ctx, zip)
	if err != nil {
		return domain.DeliveryLocation{}, fmt.Errorf("zip lookup: %w", err)
	}
	city := firstNonEmpty(a.City, zip)
	return domain.DeliveryLocation{ZIP: zip, City: city, State: a.State, Label: city + " " + zip, Serviceable: l.serviceable(ctx, a.State, zip, a.City)}, nil
}

func (l *locationServiceImpl) Current(ctx context.Context, c *domain.Caller) domain.DeliveryLocation {
	zip := c.ZIP()
	if zip == "" {
		zip = l.o.DefaultZIP
	}
	loc, err := l.lookup(ctx, zip)
	if err != nil {
		if zip != l.o.DefaultZIP {
			l.o.Log.InfoContext(ctx, "zip lookup failed; showing the zip only", "error", err)
			return domain.DeliveryLocation{ZIP: zip, City: "", State: "", Label: zip, Serviceable: true}
		}
		return l.fallback()
	}
	return loc
}

func zipNotServiceable() *domain.Problem {
	return domain.FieldProblem(domain.CodeZipNotServiceable, "We don’t deliver there yet", "zip", "We don’t deliver to that ZIP yet.")
}

func (l *locationServiceImpl) SetZIP(ctx context.Context, c *domain.Caller, zip string) (domain.DeliveryLocation, error) {
	zip = strings.TrimSpace(zip)
	if !zipRe.MatchString(zip) {
		return domain.DeliveryLocation{}, domain.ValidationProblem(map[string]string{"zip": "Enter a valid 5-digit US ZIP code."})
	}
	loc, err := l.lookup(ctx, zip)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return domain.DeliveryLocation{}, zipNotServiceable().WithCause(err)
		}
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnprocessableEntity {
			return domain.DeliveryLocation{}, zipNotServiceable().WithCause(err)
		}
		return domain.DeliveryLocation{}, mapErr("geo", err)
	}
	return loc, l.remember(ctx, c, zip)
}

func (l *locationServiceImpl) remember(ctx context.Context, c *domain.Caller, zip string) error {
	l.sessions.Ensure(c)
	c.Session.ZIP = zip
	if err := l.sessions.Save(ctx, c); err != nil {
		return fmt.Errorf("remember zip: %w", err)
	}
	return nil
}

func (l *locationServiceImpl) SetCoordinates(ctx context.Context, c *domain.Caller, lat, lon float64) (domain.DeliveryLocation, error) {
	a, err := l.geo.Reverse(ctx, lat, lon)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return domain.DeliveryLocation{}, zipNotServiceable().WithCause(err)
		}
		return domain.DeliveryLocation{}, mapErr("geo", err)
	}
	zip := a.PostalCode
	if len(zip) > 5 {
		zip = zip[:5]
	}
	if !zipRe.MatchString(zip) {
		return domain.DeliveryLocation{}, zipNotServiceable()
	}
	city := firstNonEmpty(a.City, zip)
	loc := domain.DeliveryLocation{ZIP: zip, City: city, State: a.State, Label: city + " " + zip, Serviceable: l.serviceable(ctx, a.State, zip, a.City)}
	return loc, l.remember(ctx, c, zip)
}

func sourceOf(provider, id string) string {
	switch {
	case strings.HasPrefix(provider, "google"), strings.HasPrefix(id, "g:"):
		return "google"
	case strings.HasPrefix(provider, "osm"), strings.Contains(provider, "nominatim"), strings.HasPrefix(id, "o:"):
		return "osm"
	default:
		return "fixture"
	}
}

var attributions = map[string]string{
	"google":  "Powered by Google",
	"osm":     "© OpenStreetMap contributors",
	"fixture": "Sample addresses",
}

func (l *locationServiceImpl) Suggest(ctx context.Context, q, country, sessionToken string) (domain.AddressSuggestions, error) {
	country = strings.ToUpper(strings.TrimSpace(country))
	if country == "" {
		country = l.o.Country
	}
	problems := map[string]string{}
	if !countryRe.MatchString(country) {
		problems["country"] = "Use a two-letter country code, e.g. US."
	}
	if sessionToken != "" && !sessionTokenRe.MatchString(sessionToken) {
		problems["sessionToken"] = "Use the session token of the previous answer (8–36 letters, digits, - or _)."
	}
	if len(problems) > 0 {
		return domain.AddressSuggestions{}, domain.ValidationProblem(problems)
	}
	if sessionToken == "" {
		sessionToken = newSessionToken()
	}
	sugs, err := l.geo.Autocomplete(ctx, q, sessionToken, country)
	if err != nil {
		return domain.AddressSuggestions{}, mapErr("geo", err)
	}
	if len(sugs) > 5 {
		sugs = sugs[:5]
	}
	out := make([]domain.AddressSuggestion, len(sugs))
	var wg sync.WaitGroup
	for i, s := range sugs {
		source := sourceOf(s.Provider, s.ID)
		out[i] = domain.AddressSuggestion{ID: s.ID, Label: firstNonEmpty(s.MainText, s.Text), Secondary: s.SecondaryText,
			Address: domain.PostalAddress{Line1: firstNonEmpty(s.MainText, s.Text), Country: country}, Source: source}
		if source == "google" {
			// Google suggestions resolve when picked (ResolveSuggestion): a Place Details call per suggestion and
			// keystroke would bill each one and end the session.
			continue
		}
		wg.Go(func() {
			if a, err := l.geo.Resolve(ctx, s.ID, sessionToken); err == nil {
				out[i].Address = postal(a)
				out[i].Resolved = true
			}
		})
	}
	wg.Wait()
	attribution := attributions["fixture"]
	if len(out) > 0 {
		attribution = attributions[out[0].Source]
	}
	return domain.AddressSuggestions{Suggestions: out, Attribution: attribution, SessionToken: sessionToken}, nil
}

func (l *locationServiceImpl) ResolveSuggestion(ctx context.Context, id, sessionToken string) (domain.PostalAddress, error) {
	if id == "" || len(id) > 512 {
		return domain.PostalAddress{}, domain.ValidationProblem(map[string]string{"id": "Pick a suggestion."})
	}
	if sessionToken != "" && !sessionTokenRe.MatchString(sessionToken) {
		return domain.PostalAddress{}, domain.ValidationProblem(map[string]string{
			"sessionToken": "Use the session token of the suggestions (8–36 letters, digits, - or _)."})
	}
	a, err := l.geo.Resolve(ctx, id, sessionToken)
	if err != nil {
		return domain.PostalAddress{}, mapErr("geo", err)
	}
	return postal(a), nil
}

// newSessionToken is a URL-safe random token (22 characters), valid as a Google Places session token.
func newSessionToken() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func postal(a port.GeoAddress) domain.PostalAddress {
	p := domain.PostalAddress{Line1: a.Line1, Line2: a.Line2, City: a.City, State: a.State, ZIP: a.PostalCode,
		Country: firstNonEmpty(strings.ToUpper(a.Country), "US")}
	if a.Lat != 0 || a.Lon != 0 {
		p.Lat, p.Lon = ptr(a.Lat), ptr(a.Lon)
	}
	return p
}
