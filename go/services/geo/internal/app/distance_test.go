package app_test

import (
	"context"
	"errors"
	"math"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/fixture"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/routecache"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// haversine stands in for PostGIS in unit tests (the PostGIS calculator has its own Testcontainers test).
type haversine struct{ calls int }

func (h *haversine) Geodesic(_ context.Context, from domain.Point, to []domain.Point) ([]float64, error) {
	h.calls++
	out := make([]float64, len(to))
	for i, p := range to {
		dLat := (p.Lat - from.Lat) * math.Pi / 180
		dLon := (p.Lng - from.Lng) * math.Pi / 180
		a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(from.Lat*math.Pi/180)*math.Cos(p.Lat*math.Pi/180)*
			math.Sin(dLon/2)*math.Sin(dLon/2)
		out[i] = 2 * 6371008.8 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	}
	return out, nil
}

type fakeRouter struct {
	name   string
	method domain.DistanceMethod
	routes []domain.Route
	err    error
	delay  time.Duration
	calls  int
}

func (f *fakeRouter) Name() string                  { return f.name }
func (f *fakeRouter) Method() domain.DistanceMethod { return f.method }
func (f *fakeRouter) Routes(ctx context.Context, _ domain.Point, to []domain.Point) ([]domain.Route, error) {
	f.calls++
	if f.delay > 0 {
		select {
		case <-time.After(f.delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if f.err != nil {
		return nil, f.err
	}
	return f.routes[:len(to)], nil
}

func ptr(v float64) *float64 { return &v }

func distances(cache port.RouteCache, routers ...port.RoadRouter) (app.DistanceService, *haversine) {
	h := &haversine{}
	return app.NewDistanceService(svc(fixture.New()), h, app.RoutingOptions{Routers: routers, Cache: cache,
		Timeout: 50 * time.Millisecond}, quiet), h
}

// Downtown Houston store → 9800 Bissonnet St (fixture coordinates).
var (
	store = domain.Place{Lat: ptr(29.7589), Lng: ptr(-95.3677)}
	home  = domain.Place{Address: &domain.Address{Line1: "9800 Bissonnet St", City: "Houston", State: "TX",
		PostalCode: "77036", Country: "US"}}
)

func TestDistanceGeodesicTimesDetour(t *testing.T) {
	s, _ := distances(nil)
	d, err := s.Distance(context.Background(), store, home)
	if err != nil {
		t.Fatal(err)
	}
	if d.DurationMinutes != math.Round(d.RoadMiles/25*600)/10 || d.EstimatedRoadMiles != d.RoadMiles {
		t.Fatalf("fallback duration / alias: %+v", d)
	}
	if d.Method != domain.MethodGeodesicDetour || d.StraightLineMiles < 11.5 || d.StraightLineMiles > 12.5 {
		t.Fatalf("got %+v", d)
	}
	if d.RoadMiles != domain.Detour(d.StraightLineMiles, 1.3) {
		t.Fatalf("road miles %v != %v × 1.3", d.RoadMiles, d.StraightLineMiles)
	}
	if d.To.Precision != domain.PrecisionAddress || d.From.Precision != domain.PrecisionPoint {
		t.Fatalf("precisions %+v %+v", d.From, d.To)
	}
}

func TestDistanceByZipAndAddressID(t *testing.T) {
	s, _ := distances(nil)
	byZip, err := s.Distance(context.Background(), store, domain.Place{Zip: "77036"})
	if err != nil || byZip.To.Precision != domain.PrecisionZip {
		t.Fatalf("zip: %+v %v", byZip, err)
	}
	byID, err := s.Distance(context.Background(), store, domain.Place{AddressID: "f:1"})
	if err != nil || byID.StraightLineMiles != byZip.StraightLineMiles {
		t.Fatalf("addressId: %+v %v", byID, err)
	}
}

func TestProvidersInPriorityOrderThenGeodesic(t *testing.T) {
	google := &fakeRouter{name: "google", method: domain.MethodRoadGoogle,
		routes: []domain.Route{{Metres: 25000, Seconds: 1500}}}
	osrm := &fakeRouter{name: "osrm", method: domain.MethodRoadOSRM, routes: []domain.Route{{Metres: 24000, Seconds: 1400}}}
	s, _ := distances(nil, google, osrm)
	d, err := s.Distance(context.Background(), store, home)
	if err != nil || d.Method != domain.MethodRoadGoogle || d.RoadMiles != 15.53 || d.DurationMinutes != 25 ||
		osrm.calls != 0 {
		t.Fatalf("google first: %+v %v (osrm calls %d)", d, err, osrm.calls)
	}

	google.err = errors.New("quota exceeded")
	d, err = s.Distance(context.Background(), store, home)
	if err != nil || d.Method != domain.MethodRoadOSRM || d.DurationMinutes != 23.3 || osrm.calls != 1 {
		t.Fatalf("osrm second: %+v %v", d, err)
	}

	osrm.err = errors.New("connection refused")
	d, err = s.Distance(context.Background(), store, home)
	if err != nil || d.Method != domain.MethodGeodesicDetour || d.RoadMiles != domain.Detour(d.StraightLineMiles, 1.3) {
		t.Fatalf("geodesic last: %+v %v", d, err)
	}
}

func TestSlowProviderTimesOutAndTheNextAnswers(t *testing.T) {
	slow := &fakeRouter{name: "google", method: domain.MethodRoadGoogle, delay: time.Second,
		routes: []domain.Route{{Metres: 1, Seconds: 1}}}
	osrm := &fakeRouter{name: "osrm", method: domain.MethodRoadOSRM, routes: []domain.Route{{Metres: 24000, Seconds: 1400}}}
	s, _ := distances(nil, slow, osrm)
	start := time.Now()
	d, err := s.Distance(context.Background(), store, home)
	if err != nil || d.Method != domain.MethodRoadOSRM || time.Since(start) > 500*time.Millisecond {
		t.Fatalf("timeout fallback: %+v %v after %v", d, err, time.Since(start))
	}
}

func TestCacheHitsSkipTheProvider(t *testing.T) {
	osrm := &fakeRouter{name: "osrm", method: domain.MethodRoadOSRM, routes: []domain.Route{{Metres: 24000, Seconds: 1400}}}
	s, _ := distances(routecache.New(time.Hour, 50, 100, nil), osrm)
	first, err := s.Distance(context.Background(), store, home)
	if err != nil || first.Cached || osrm.calls != 1 {
		t.Fatalf("first: %+v %v", first, err)
	}
	// A few metres away from the same store: same cache cell.
	nearby := domain.Place{Lat: ptr(29.75893), Lng: ptr(-95.36772)}
	second, err := s.Distance(context.Background(), nearby, home)
	if err != nil || !second.Cached || osrm.calls != 1 || second.RoadMiles != first.RoadMiles ||
		second.Method != domain.MethodRoadOSRM {
		t.Fatalf("second: %+v %v (calls %d)", second, err, osrm.calls)
	}
}

func TestBatchOneOriginManyDestinations(t *testing.T) {
	s, h := distances(nil)
	res, err := s.Batch(context.Background(), store, []domain.Place{home, {Zip: "77479"}, {Zip: "1"},
		{Lat: ptr(29.7589), Lng: ptr(-95.3677)}})
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 4 || res[0].Distance == nil || res[1].Distance == nil || res[3].Distance == nil {
		t.Fatalf("got %+v", res)
	}
	if res[2].Err == nil || !errors.Is(res[2].Err, domain.ErrInvalidAddress) {
		t.Fatalf("bad ZIP should fail on its own: %+v", res[2])
	}
	if res[3].Distance.StraightLineMiles != 0 || h.calls != 1 {
		t.Fatalf("same point: %+v, PostGIS calls %d", res[3].Distance, h.calls)
	}
}

func TestBatchLimitAndInvalidPlaces(t *testing.T) {
	s, _ := distances(nil)
	if _, err := s.Batch(context.Background(), store, make([]domain.Place, 101)); !errors.Is(err,
		app.ErrTooManyDestinations) {
		t.Fatalf("want too many, got %v", err)
	}
	if _, err := s.Distance(context.Background(), domain.Place{}, home); !errors.Is(err, domain.ErrInvalidPlace) {
		t.Fatalf("want invalid place, got %v", err)
	}
	if _, err := s.Distance(context.Background(), domain.Place{Lat: ptr(95), Lng: ptr(0)}, home); !errors.Is(err,
		domain.ErrInvalidPoint) {
		t.Fatalf("want invalid point, got %v", err)
	}
}

func TestGeocodeFallsBackToZip(t *testing.T) {
	s, _ := distances(nil)
	l, err := s.Geocode(context.Background(), domain.Address{Line1: "1 Nowhere Lane", City: "Edison", State: "NJ",
		PostalCode: "08820"})
	if err != nil || l.Precision != domain.PrecisionZip || l.Lat != 40.5781 {
		t.Fatalf("got %+v %v", l, err)
	}
	if _, err := s.Geocode(context.Background(), domain.Address{Line1: "1 Nowhere Lane"}); !errors.Is(err,
		app.ErrNotFound) {
		t.Fatalf("want not found, got %v", err)
	}
}

func TestDetourNeverShortens(t *testing.T) {
	if domain.Detour(10, 0.5) != 10 || domain.Detour(10, 1.3) != 13 {
		t.Fatal("detour factor")
	}
}
