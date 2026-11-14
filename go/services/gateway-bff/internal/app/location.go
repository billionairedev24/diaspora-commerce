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
	// Detect silently sets an approximate deliver-to location (source "detected") on a session that has none: the
	// configured override ZIP, else the edge's visitor-location headers. It never replaces a chosen location and
	// never fails; without a usable signal the market's default location stays in force.
	Detect(ctx context.Context, c *domain.Caller, h domain.EdgeHints)
	SetZIP(ctx context.Context, c *domain.Caller, zip string) (domain.DeliveryLocation, error)
	SetCoordinates(ctx context.Context, c *domain.Caller, lat, lon float64) (domain.DeliveryLocation, error)
	// SetSuggestion applies a picked place suggestion (a city, ZIP or street from Suggest with kind "place").
	SetSuggestion(ctx context.Context, c *domain.Caller, id, sessionToken string) (domain.DeliveryLocation, error)
	// Suggest is one keystroke of an address field: up to five suggestions and the autocomplete session token to send
	// with the next keystroke and with ResolveSuggestion (a new one when sessionToken is blank). The country defaults
	// to the market's. kind "place" is the deliver-to picker: cities ("Georgetown" / "TX"), a five-digit ZIP as its
	// city, and streets ("12 Main St" / "Georgetown, TX"); "" is street addresses and ZIP codes.
	Suggest(ctx context.Context, q, country, sessionToken, kind string) (domain.AddressSuggestions, error)
	// ResolveSuggestion turns a picked suggestion into a structured address. With Google Places it is the Place
	// Details call that closes the session: one per address entry, never one per suggestion.
	ResolveSuggestion(ctx context.Context, id, sessionToken string) (domain.PostalAddress, error)
}

// LocationOptions configure the default location (used when the session has none or geo is down).
type LocationOptions struct {
	DefaultZIP, DefaultCity, DefaultState string
	// DetectOverrideZIP is the ZIP first-visit detection answers whatever the request says (local development).
	DetectOverrideZIP string
	// TrustEdgeGeo reads the edge's visitor-location headers for detection.
	TrustEdgeGeo bool
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
		Label: l.o.DefaultCity, Serviceable: true, Source: domain.LocationDefault}
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

// place builds the location of a ZIP in a city. The label is the city only: the ZIP never reaches the header.
func (l *locationServiceImpl) place(ctx context.Context, zip, city, state, source string) domain.DeliveryLocation {
	return domain.DeliveryLocation{ZIP: zip, City: city, State: state, Label: city, Source: source,
		Serviceable: l.serviceable(ctx, state, zip, city)}
}

// lookup resolves a ZIP through geo; a city and state the shopper (or the edge) named win over geo's for the ZIP.
func (l *locationServiceImpl) lookup(ctx context.Context, zip, city, state, source string) (domain.DeliveryLocation, error) {
	a, err := l.geo.Zip(ctx, zip)
	if err != nil {
		return domain.DeliveryLocation{}, fmt.Errorf("zip lookup: %w", err)
	}
	return l.place(ctx, zip, firstNonEmpty(city, a.City), firstNonEmpty(state, a.State), source), nil
}

func (l *locationServiceImpl) Current(ctx context.Context, c *domain.Caller) domain.DeliveryLocation {
	zip, city, state, source, precision := c.ZIP(), "", "", domain.LocationDefault, ""
	if zip != "" && c.Session != nil {
		city, state = c.Session.City, c.Session.State
		source = firstNonEmpty(c.Session.LocationSource, domain.LocationChosen)
		precision = c.Session.LocationPrecision
	}
	if zip == "" {
		zip = l.o.DefaultZIP
	}
	loc, err := l.lookup(ctx, zip, city, state, source)
	if err != nil {
		if zip != l.o.DefaultZIP {
			l.o.Log.InfoContext(ctx, "zip lookup failed; showing the stored city only", "error", err)
			return domain.DeliveryLocation{ZIP: zip, City: city, State: state, Label: city, Serviceable: true, Source: source, Precision: precision}
		}
		return l.fallback()
	}
	loc.Precision = precision
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
	loc, err := l.lookup(ctx, zip, "", "", domain.LocationChosen)
	if err != nil {
		return domain.DeliveryLocation{}, l.zipErr(err)
	}
	return loc, l.remember(ctx, c, loc)
}

func (l *locationServiceImpl) zipErr(err error) error {
	if errors.Is(err, port.ErrNotFound) {
		return zipNotServiceable().WithCause(err)
	}
	if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnprocessableEntity {
		return zipNotServiceable().WithCause(err)
	}
	return mapErr("geo", err)
}

func (l *locationServiceImpl) remember(ctx context.Context, c *domain.Caller, loc domain.DeliveryLocation) error {
	l.sessions.Ensure(c)
	c.Session.ZIP, c.Session.City, c.Session.State, c.Session.LocationSource = loc.ZIP, loc.City, loc.State, loc.Source
	c.Session.LocationPrecision = loc.Precision
	if err := l.sessions.Save(ctx, c); err != nil {
		return fmt.Errorf("remember location: %w", err)
	}
	return nil
}

func firstFive(s string) string {
	s = strings.TrimSpace(s)
	if len(s) > 5 {
		s = s[:5]
	}
	return s
}

// fromPoint reverse-geocodes a point to a location in the given source (not remembered).
func (l *locationServiceImpl) fromPoint(ctx context.Context, lat, lon float64, source string) (domain.DeliveryLocation, error) {
	a, err := l.geo.Reverse(ctx, lat, lon)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return domain.DeliveryLocation{}, zipNotServiceable().WithCause(err)
		}
		return domain.DeliveryLocation{}, mapErr("geo", err)
	}
	zip := firstFive(a.PostalCode)
	if !zipRe.MatchString(zip) {
		return domain.DeliveryLocation{}, zipNotServiceable()
	}
	return l.place(ctx, zip, a.City, a.State, source), nil
}

func (l *locationServiceImpl) SetCoordinates(ctx context.Context, c *domain.Caller, lat, lon float64) (domain.DeliveryLocation, error) {
	loc, err := l.fromPoint(ctx, lat, lon, domain.LocationChosen)
	if err != nil {
		return domain.DeliveryLocation{}, err
	}
	return loc, l.remember(ctx, c, loc)
}

// zipSuggestionPrefix marks the id of a ZIP suggestion (a five-digit query answers its city directly).
const zipSuggestionPrefix = "zip:"

func (l *locationServiceImpl) SetSuggestion(ctx context.Context, c *domain.Caller, id, sessionToken string) (domain.DeliveryLocation, error) {
	if zip, ok := strings.CutPrefix(id, zipSuggestionPrefix); ok {
		return l.SetZIP(ctx, c, zip)
	}
	if id == "" || len(id) > 512 {
		return domain.DeliveryLocation{}, domain.ValidationProblem(map[string]string{"suggestionId": "Pick a suggestion."})
	}
	if sessionToken != "" && !sessionTokenRe.MatchString(sessionToken) {
		return domain.DeliveryLocation{}, domain.ValidationProblem(map[string]string{"sessionToken": "Use the session token of the suggestions."})
	}
	a, err := l.geo.Resolve(ctx, id, sessionToken)
	if err != nil {
		return domain.DeliveryLocation{}, mapErr("geo", err)
	}
	if a.Country != "" && !strings.EqualFold(a.Country, l.o.Country) {
		return domain.DeliveryLocation{}, zipNotServiceable()
	}
	zip := firstFive(a.PostalCode)
	if !zipRe.MatchString(zip) && (a.Lat != 0 || a.Lon != 0) {
		// A city has no ZIP of its own: the ZIP at its centre is the delivery ZIP.
		r, err := l.geo.Reverse(ctx, a.Lat, a.Lon)
		if err != nil && !errors.Is(err, port.ErrNotFound) {
			return domain.DeliveryLocation{}, mapErr("geo", err)
		}
		zip = firstFive(r.PostalCode)
	}
	if !zipRe.MatchString(zip) {
		return domain.DeliveryLocation{}, zipNotServiceable()
	}
	loc := l.place(ctx, zip, a.City, a.State, domain.LocationChosen)
	if loc.City == "" {
		if loc, err = l.lookup(ctx, zip, "", "", domain.LocationChosen); err != nil {
			return domain.DeliveryLocation{}, l.zipErr(err)
		}
	}
	return loc, l.remember(ctx, c, loc)
}

func (l *locationServiceImpl) Detect(ctx context.Context, c *domain.Caller, h domain.EdgeHints) {
	if c.ZIP() != "" {
		return
	}
	loc, ok := l.detect(ctx, h)
	if !ok {
		return
	}
	if err := l.remember(ctx, c, loc); err != nil {
		l.o.Log.InfoContext(ctx, "detected location not remembered", "error", err)
	}
}

// detect works out the approximate location: override ZIP, then the edge's ZIP, then its coordinates, then its city.
func (l *locationServiceImpl) detect(ctx context.Context, h domain.EdgeHints) (domain.DeliveryLocation, bool) {
	if zip := firstFive(l.o.DetectOverrideZIP); zipRe.MatchString(zip) {
		loc, err := l.lookup(ctx, zip, "", "", domain.LocationDetected)
		if err != nil {
			l.o.Log.InfoContext(ctx, "detect override ZIP unusable", "error", err)
			return domain.DeliveryLocation{}, false
		}
		return loc, true
	}
	if !l.o.TrustEdgeGeo || (h.Country != "" && !strings.EqualFold(h.Country, l.o.Country)) {
		return domain.DeliveryLocation{}, false
	}
	if zip := firstFive(h.ZIP); zipRe.MatchString(zip) {
		if loc, err := l.lookup(ctx, zip, "", "", domain.LocationDetected); err == nil {
			return loc, true
		}
		if h.City != "" {
			return l.place(ctx, zip, h.City, h.Region, domain.LocationDetected), true
		}
	}
	if h.Lat != nil && h.Lon != nil {
		if loc, err := l.fromPoint(ctx, *h.Lat, *h.Lon, domain.LocationDetected); err == nil {
			return loc, true
		}
	}
	return l.detectByCity(ctx, h)
}

var edgeStateRe = regexp.MustCompile(`^[A-Za-z]{2}$`)

// detectByCity is the last signal: the edge named a city and a two-letter state but no ZIP and no usable point. The ZIP is
// the city's representative one from geo's ZIP table (real data, never invented) and the place is marked CITY precision,
// so an estimate made from it says so. No such city in the table means no detection.
func (l *locationServiceImpl) detectByCity(ctx context.Context, h domain.EdgeHints) (domain.DeliveryLocation, bool) {
	city, state := strings.TrimSpace(h.City), strings.TrimSpace(h.Region)
	if city == "" || !edgeStateRe.MatchString(state) {
		return domain.DeliveryLocation{}, false
	}
	z, err := l.geo.ZipsByCity(ctx, city, state)
	if err != nil || !zipRe.MatchString(z.Zip) {
		if err != nil && !errors.Is(err, port.ErrNotFound) {
			l.o.Log.InfoContext(ctx, "city to ZIP lookup failed; no location detected", "error", err)
		}
		return domain.DeliveryLocation{}, false
	}
	loc := l.place(ctx, z.Zip, firstNonEmpty(z.City, city), strings.ToUpper(firstNonEmpty(z.State, state)), domain.LocationDetected)
	loc.Precision = domain.PrecisionCity
	return loc, true
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
	"geo":     "",
	"google":  "Powered by Google",
	"osm":     "© OpenStreetMap contributors",
	"fixture": "Sample addresses",
}

func (l *locationServiceImpl) Suggest(ctx context.Context, q, country, sessionToken, kind string) (domain.AddressSuggestions, error) {
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
	var out []domain.AddressSuggestion
	var err error
	if kind == placeKind {
		out, err = l.suggestPlaces(ctx, q, country, sessionToken)
	} else {
		out, err = l.suggest(ctx, q, country, sessionToken, "")
	}
	if err != nil {
		return domain.AddressSuggestions{}, err
	}
	attribution := attributions["fixture"]
	if len(out) > 0 {
		attribution = attributions[out[0].Source]
	}
	return domain.AddressSuggestions{Suggestions: out, Attribution: attribution, SessionToken: sessionToken}, nil
}

// placeKind is the Suggest kind of the deliver-to picker.
const placeKind = "place"

// maxPlaces caps the deliver-to list: up to four cities, then streets.
const (
	maxPlaces     = 6
	maxPlaceCitys = 4
)

// suggestPlaces is the deliver-to combobox: a five-digit ZIP answers its city, a house number means streets, and
// anything else is cities first (every one with its state, so "Georgetown, TX" and "Georgetown, KY" are told apart),
// then streets. The ZIP never appears as a headline.
func (l *locationServiceImpl) suggestPlaces(ctx context.Context, q, country, sessionToken string) ([]domain.AddressSuggestion, error) {
	if strings.Trim(q, "0123456789") == "" {
		if !zipRe.MatchString(q) {
			return nil, nil
		}
		a, err := l.geo.Zip(ctx, q)
		if err != nil {
			if errors.Is(err, port.ErrNotFound) {
				return nil, nil
			}
			return nil, mapErr("geo", err)
		}
		return []domain.AddressSuggestion{{ID: zipSuggestionPrefix + q, Label: a.City, Secondary: a.State, Source: "geo", Resolved: true,
			Address: domain.PostalAddress{City: a.City, State: a.State, ZIP: q, Country: country}}}, nil
	}
	if first := strings.Fields(q)[0]; strings.Trim(first, "0123456789") == "" {
		streets, err := l.suggest(ctx, q, country, sessionToken, "address")
		return placeRows(streets, maxPlaces), err
	}
	var cities, streets []domain.AddressSuggestion
	var cityErr, streetErr error
	var wg sync.WaitGroup
	wg.Go(func() { cities, cityErr = l.suggest(ctx, q, country, sessionToken, "locality") })
	wg.Go(func() { streets, streetErr = l.suggest(ctx, q, country, sessionToken, "address") })
	wg.Wait()
	if cityErr != nil && streetErr != nil {
		return nil, cityErr
	}
	rows := placeRows(cities, maxPlaceCitys)
	return append(rows, placeRows(streets, maxPlaces-len(rows))...), nil
}

// placeRows shapes rows for the deliver-to list: a city is "Georgetown" / "TX"; a street "12 Main St" / "Georgetown, TX".
func placeRows(in []domain.AddressSuggestion, limit int) []domain.AddressSuggestion {
	out := make([]domain.AddressSuggestion, 0, min(limit, len(in)))
	seen := map[string]bool{}
	for _, s := range in {
		a := s.Address
		if a.City != "" && a.State != "" {
			if a.Line1 == "" || s.Label == a.City || strings.EqualFold(s.Label, a.City) {
				s.Label, s.Secondary = a.City, a.State
			} else {
				s.Secondary = a.City + ", " + a.State
			}
		} else {
			s.Secondary = strings.TrimSuffix(strings.TrimSuffix(s.Secondary, ", USA"), ", United States")
		}
		key := s.Label + "|" + s.Secondary
		if seen[key] || len(out) == limit {
			continue
		}
		seen[key] = true
		out = append(out, s)
	}
	return out
}

// suggest asks geo for up to five suggestions of a kind and resolves the non-Google ones.
func (l *locationServiceImpl) suggest(ctx context.Context, q, country, sessionToken, kind string) ([]domain.AddressSuggestion, error) {
	sugs, err := l.geo.Autocomplete(ctx, q, sessionToken, country, kind)
	if err != nil {
		return nil, mapErr("geo", err)
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
	return out, nil
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
