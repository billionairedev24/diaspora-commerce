package app

import (
	"context"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type stubSessions struct {
	SessionService
	saved int
}

func (m *stubSessions) Ensure(c *domain.Caller) {
	if c.Session == nil {
		c.Session = &domain.Session{ID: "s"}
	}
}

func (m *stubSessions) Save(context.Context, *domain.Caller) error { m.saved++; return nil }

// placeGeo is a geo fake with ZIPs, a reverse geocoder and per-kind autocomplete.
type placeGeo struct {
	port.GeoClient
	zips    map[string]port.GeoAddress
	suggest map[string][]port.GeoSuggestion // by kind
	resolve map[string]port.GeoAddress
	reverse port.GeoAddress
	kinds   []string
}

func (g *placeGeo) Zip(_ context.Context, zip string) (port.GeoAddress, error) {
	if a, ok := g.zips[zip]; ok {
		return a, nil
	}
	return port.GeoAddress{}, port.ErrNotFound
}

func (g *placeGeo) Reverse(context.Context, float64, float64) (port.GeoAddress, error) {
	return g.reverse, nil
}

func (g *placeGeo) Autocomplete(_ context.Context, _, _, _, kind string) ([]port.GeoSuggestion, error) {
	g.kinds = append(g.kinds, kind)
	return g.suggest[kind], nil
}

func (g *placeGeo) Resolve(_ context.Context, id, _ string) (port.GeoAddress, error) {
	if a, ok := g.resolve[id]; ok {
		return a, nil
	}
	return port.GeoAddress{}, port.ErrNotFound
}

func newPlaceService(geo *placeGeo, o LocationOptions) (LocationService, *stubSessions) {
	sessions := &stubSessions{}
	o.DefaultZIP, o.DefaultCity, o.DefaultState = "77042", "Houston", "TX"
	return NewLocationService(geo, &cityContent{}, sessions, o), sessions
}

func f64(v float64) *float64 { return &v }

func TestDetectUsesEdgeHeadersAndFlagsDetected(t *testing.T) {
	geo := &placeGeo{zips: map[string]port.GeoAddress{"78626": {City: "Georgetown", State: "TX"}}}
	svc, sessions := newPlaceService(geo, LocationOptions{TrustEdgeGeo: true})
	c := &domain.Caller{}
	svc.Detect(context.Background(), c, domain.EdgeHints{Country: "US", City: "Austin", Region: "TX", ZIP: "78626-1234"})
	if c.Session == nil || c.Session.ZIP != "78626" || c.Session.City != "Georgetown" || c.Session.LocationSource != domain.LocationDetected || sessions.saved != 1 {
		t.Fatalf("session = %+v", c.Session)
	}
	loc := svc.Current(context.Background(), c)
	if loc.Label != "Georgetown" || loc.Source != domain.LocationDetected || loc.ZIP != "78626" {
		t.Errorf("loc = %+v", loc)
	}
}

func TestDetectFallsBackToCoordinatesThenNothing(t *testing.T) {
	geo := &placeGeo{reverse: port.GeoAddress{City: "Dallas", State: "TX", PostalCode: "75201"}}
	svc, _ := newPlaceService(geo, LocationOptions{TrustEdgeGeo: true})
	c := &domain.Caller{}
	svc.Detect(context.Background(), c, domain.EdgeHints{Country: "US", Lat: f64(32.7), Lon: f64(-96.8)})
	if c.Session == nil || c.Session.City != "Dallas" || c.Session.LocationSource != domain.LocationDetected {
		t.Fatalf("coordinates: %+v", c.Session)
	}
	for name, h := range map[string]domain.EdgeHints{"no hints": {}, "outside the market": {Country: "NG", ZIP: "78626"}} {
		c := &domain.Caller{}
		svc.Detect(context.Background(), c, h)
		if c.Session != nil {
			t.Errorf("%s: detected %+v", name, c.Session)
		}
		if loc := svc.Current(context.Background(), c); loc.Source != domain.LocationDefault || loc.Label != "Houston" {
			t.Errorf("%s: current = %+v", name, loc)
		}
	}
}

func TestDetectOverrideWinsAndNeverReplacesAChoice(t *testing.T) {
	geo := &placeGeo{zips: map[string]port.GeoAddress{"30303": {City: "Atlanta", State: "GA"}, "78626": {City: "Georgetown", State: "TX"}}}
	svc, _ := newPlaceService(geo, LocationOptions{DetectOverrideZIP: "30303"})
	c := &domain.Caller{}
	svc.Detect(context.Background(), c, domain.EdgeHints{Country: "US", ZIP: "78626"})
	if c.Session == nil || c.Session.City != "Atlanta" || c.Session.LocationSource != domain.LocationDetected {
		t.Fatalf("override: %+v", c.Session)
	}
	chosen := &domain.Caller{Session: &domain.Session{ZIP: "78626", City: "Georgetown", State: "TX", LocationSource: domain.LocationChosen}}
	svc.Detect(context.Background(), chosen, domain.EdgeHints{})
	if chosen.Session.City != "Georgetown" || chosen.Session.LocationSource != domain.LocationChosen {
		t.Errorf("a chosen location was replaced: %+v", chosen.Session)
	}
	if loc := svc.Current(context.Background(), chosen); loc.Source != domain.LocationChosen || loc.Label != "Georgetown" {
		t.Errorf("loc = %+v", loc)
	}
}

func TestSuggestPlacesListsCitiesWithStateThenStreets(t *testing.T) {
	geo := &placeGeo{
		suggest: map[string][]port.GeoSuggestion{
			"locality": {{ID: "g:1", MainText: "Georgetown", SecondaryText: "TX, USA", Provider: "google-places"},
				{ID: "g:2", MainText: "Georgetown", SecondaryText: "KY, USA", Provider: "google-places"}},
			"address": {{ID: "g:3", MainText: "12 George St", SecondaryText: "Houston, TX, USA", Provider: "google-places"}},
		},
	}
	svc, _ := newPlaceService(geo, LocationOptions{})
	got, err := svc.Suggest(context.Background(), "Georgetown", "", "", "place")
	if err != nil {
		t.Fatal(err)
	}
	var rows []string
	for _, s := range got.Suggestions {
		rows = append(rows, s.Label+"|"+s.Secondary)
	}
	want := []string{"Georgetown|TX", "Georgetown|KY", "12 George St|Houston, TX"}
	if len(rows) != len(want) || rows[0] != want[0] || rows[1] != want[1] || rows[2] != want[2] {
		t.Errorf("rows = %v, want %v", rows, want)
	}
	if len(geo.kinds) != 2 {
		t.Errorf("kinds asked = %v", geo.kinds)
	}
}

func TestSuggestPlacesZipShowsItsCityNeverTheZip(t *testing.T) {
	geo := &placeGeo{zips: map[string]port.GeoAddress{"78626": {City: "Georgetown", State: "TX"}}}
	svc, _ := newPlaceService(geo, LocationOptions{})
	got, err := svc.Suggest(context.Background(), "78626", "", "", "place")
	if err != nil || len(got.Suggestions) != 1 || got.Suggestions[0].Label != "Georgetown" || got.Suggestions[0].Secondary != "TX" || got.Suggestions[0].ID != "zip:78626" {
		t.Fatalf("zip = %+v, %v", got, err)
	}
	if got, _ = svc.Suggest(context.Background(), "786", "", "", "place"); len(got.Suggestions) != 0 {
		t.Errorf("partial zip = %+v", got.Suggestions)
	}
	if got, _ = svc.Suggest(context.Background(), "00000", "", "", "place"); len(got.Suggestions) != 0 {
		t.Errorf("unknown zip = %+v", got.Suggestions)
	}
	if len(geo.kinds) != 0 {
		t.Errorf("a ZIP must not hit autocomplete: %v", geo.kinds)
	}
}

func TestSetSuggestionCityUsesTheZipAtItsCentre(t *testing.T) {
	geo := &placeGeo{
		resolve: map[string]port.GeoAddress{"g:1": {City: "Georgetown", State: "TX", Country: "US", Lat: 30.63, Lon: -97.68}},
		reverse: port.GeoAddress{City: "Georgetown", State: "TX", PostalCode: "78626-1"},
		zips:    map[string]port.GeoAddress{"78626": {City: "Georgetown", State: "TX"}},
	}
	svc, _ := newPlaceService(geo, LocationOptions{})
	c := &domain.Caller{}
	loc, err := svc.SetSuggestion(context.Background(), c, "g:1", "")
	if err != nil || loc.ZIP != "78626" || loc.Label != "Georgetown" || loc.Source != domain.LocationChosen {
		t.Fatalf("loc = %+v, %v", loc, err)
	}
	if c.Session.LocationSource != domain.LocationChosen || c.Session.City != "Georgetown" {
		t.Errorf("session = %+v", c.Session)
	}
	if _, err := svc.SetSuggestion(context.Background(), &domain.Caller{}, "zip:78626", ""); err != nil {
		t.Errorf("zip suggestion: %v", err)
	}
	geo.resolve["g:9"] = port.GeoAddress{City: "Lagos", Country: "NG"}
	if _, err := svc.SetSuggestion(context.Background(), &domain.Caller{}, "g:9", ""); err == nil {
		t.Error("a place outside the market must be refused")
	}
}
