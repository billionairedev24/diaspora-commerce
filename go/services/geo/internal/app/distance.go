package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// MaxBatchDestinations caps one batch distance request.
const MaxBatchDestinations = 100

// ErrTooManyDestinations is returned for a batch above MaxBatchDestinations.
var ErrTooManyDestinations = errors.New("too many destinations")

// DistanceService measures how far apart two places are (AfroTransact Delivery pricing, nearest drivers) and
// geocodes addresses with the address provider chain.
type DistanceService interface {
	// Locate turns a place (coordinates, provider address id, ZIP or street address) into a point.
	Locate(ctx context.Context, p domain.Place) (domain.Located, error)
	// Geocode returns the position of a street address: the provider chain first, the ZIP centroid as a fallback.
	Geocode(ctx context.Context, a domain.Address) (domain.Located, error)
	Distance(ctx context.Context, from, to domain.Place) (domain.Distance, error)
	// Batch measures one origin against many destinations; a destination that cannot be located gets its own error.
	Batch(ctx context.Context, from domain.Place, to []domain.Place) ([]BatchResult, error)
}

// BatchResult is one destination of a batch: a distance or why there is none.
type BatchResult struct {
	Index    int
	Distance *domain.Distance
	Err      error
}

type distanceServiceImpl struct {
	addresses    AddressService
	calculator   port.DistanceCalculator
	routers      []port.RoadRouter // priority order; empty = geodesic × detour only
	cache        port.RouteCache
	timeout      time.Duration // per provider call
	detourFactor float64
	fallbackMph  float64
	log          *slog.Logger
}

var _ DistanceService = (*distanceServiceImpl)(nil)

// RoutingOptions configure road distances.
type RoutingOptions struct {
	// Routers in priority order (Google Routes API, OSRM, …): the first that answers wins, a failure or timeout
	// moves on to the next. Empty = no provider configured.
	Routers []port.RoadRouter
	// Cache keeps provider results by rounded origin / destination; nil = no cache.
	Cache port.RouteCache
	// Timeout of one provider call (default 2 s).
	Timeout time.Duration
	// DetourFactor and FallbackSpeedMph estimate road miles and minutes from the PostGIS geodesic distance when no
	// provider is configured or all failed (defaults 1.3 and 25 mph).
	DetourFactor     float64
	FallbackSpeedMph float64
}

// NewDistanceService returns the distance use cases. Road miles and duration come from the routing providers in
// order; PostGIS geodesic × detour factor is only the last resort, so pricing never depends on a vendor being up.
func NewDistanceService(addresses AddressService, calculator port.DistanceCalculator, opts RoutingOptions,
	log *slog.Logger) DistanceService {
	if opts.DetourFactor <= 0 {
		opts.DetourFactor = domain.DefaultDetourFactor
	}
	if opts.FallbackSpeedMph <= 0 {
		opts.FallbackSpeedMph = domain.DefaultFallbackSpeedMph
	}
	if opts.Timeout <= 0 {
		opts.Timeout = 2 * time.Second
	}
	if log == nil {
		log = slog.Default()
	}
	return &distanceServiceImpl{addresses: addresses, calculator: calculator, routers: opts.Routers,
		cache: opts.Cache, timeout: opts.Timeout, detourFactor: opts.DetourFactor, fallbackMph: opts.FallbackSpeedMph,
		log: log}
}

func (s *distanceServiceImpl) Locate(ctx context.Context, p domain.Place) (domain.Located, error) {
	kind, err := p.Kind()
	if err != nil {
		return domain.Located{}, fmt.Errorf("locate: %w", err)
	}
	switch kind {
	case "point":
		return domain.Located{Point: domain.Point{Lat: *p.Lat, Lng: *p.Lng}, Precision: domain.PrecisionPoint}, nil
	case "addressId":
		a, err := s.addresses.Resolve(ctx, strings.TrimSpace(p.AddressID), "")
		if err != nil {
			return domain.Located{}, fmt.Errorf("locate address %s: %w", p.AddressID, err)
		}
		if a.Lat == 0 && a.Lon == 0 {
			return s.Geocode(ctx, a)
		}
		return domain.Located{Point: domain.Point{Lat: a.Lat, Lng: a.Lon}, Precision: domain.PrecisionAddress}, nil
	case "zip":
		return s.zip(ctx, p.Zip)
	default:
		return s.Geocode(ctx, *p.Address)
	}
}

func (s *distanceServiceImpl) Geocode(ctx context.Context, a domain.Address) (domain.Located, error) {
	line := strings.Join(strings.Fields(a.Line1), " ")
	zip := strings.TrimSpace(a.PostalCode)
	if len(zip) > 5 {
		zip = zip[:5]
	}
	if line == "" && zip == "" {
		return domain.Located{}, fmt.Errorf("geocode: %w", errors.Join(domain.ErrInvalidPlace,
			errors.New("a street line or a ZIP is required")))
	}
	if a.Lat != 0 || a.Lon != 0 {
		if err := domain.CheckPoint(a.Lat, a.Lon); err != nil {
			return domain.Located{}, fmt.Errorf("geocode: %w", err)
		}
		return domain.Located{Point: domain.Point{Lat: a.Lat, Lng: a.Lon}, Precision: domain.PrecisionPoint}, nil
	}
	if line != "" {
		query := strings.Join(nonEmpty(line, a.City, a.State, zip), " ")
		sugs, err := s.addresses.Autocomplete(ctx, query, "", a.Country)
		if err != nil {
			s.log.WarnContext(ctx, "geocode: address providers failed; trying the ZIP", "error", err)
		}
		for _, sg := range sugs {
			r, err := s.addresses.Resolve(ctx, sg.ID, "")
			if err != nil || (r.Lat == 0 && r.Lon == 0) {
				continue
			}
			if zip != "" && !strings.HasPrefix(r.PostalCode, zip) {
				continue
			}
			return domain.Located{Point: domain.Point{Lat: r.Lat, Lng: r.Lon}, Precision: domain.PrecisionAddress}, nil
		}
	}
	if zip == "" {
		return domain.Located{}, fmt.Errorf("geocode %q: %w", line, ErrNotFound)
	}
	return s.zip(ctx, zip)
}

func (s *distanceServiceImpl) zip(ctx context.Context, zip string) (domain.Located, error) {
	z, err := s.addresses.LookupZip(ctx, zip)
	if err != nil {
		return domain.Located{}, fmt.Errorf("locate zip: %w", err)
	}
	if z.Lat == 0 && z.Lon == 0 {
		return domain.Located{}, fmt.Errorf("zip %s has no position: %w", zip, ErrNotFound)
	}
	return domain.Located{Point: domain.Point{Lat: z.Lat, Lng: z.Lon}, Precision: domain.PrecisionZip}, nil
}

func (s *distanceServiceImpl) Distance(ctx context.Context, from, to domain.Place) (domain.Distance, error) {
	out, err := s.Batch(ctx, from, []domain.Place{to})
	if err != nil {
		return domain.Distance{}, err
	}
	if out[0].Err != nil {
		return domain.Distance{}, out[0].Err
	}
	return *out[0].Distance, nil
}

func (s *distanceServiceImpl) Batch(ctx context.Context, from domain.Place, to []domain.Place) ([]BatchResult, error) {
	if len(to) == 0 {
		return []BatchResult{}, nil
	}
	if len(to) > MaxBatchDestinations {
		return nil, fmt.Errorf("batch of %d: %w", len(to), ErrTooManyDestinations)
	}
	origin, err := s.Locate(ctx, from)
	if err != nil {
		return nil, fmt.Errorf("origin: %w", err)
	}
	results := make([]BatchResult, len(to))
	located := make([]domain.Located, 0, len(to))
	idx := make([]int, 0, len(to))
	for i, p := range to {
		results[i].Index = i
		l, err := s.Locate(ctx, p)
		if err != nil {
			results[i].Err = fmt.Errorf("destination %d: %w", i, err)
			continue
		}
		located = append(located, l)
		idx = append(idx, i)
	}
	if len(located) == 0 {
		return results, nil
	}
	points := make([]domain.Point, len(located))
	for i, l := range located {
		points[i] = l.Point
	}
	geodesic, err := s.calculator.Geodesic(ctx, origin.Point, points)
	if err != nil {
		return nil, fmt.Errorf("distance: %w", err)
	}
	roads := s.road(ctx, origin.Point, points)
	for j, i := range idx {
		straight := domain.MetresToMiles(geodesic[j])
		d := domain.Distance{StraightLineMiles: straight, From: &origin, To: &located[j]}
		if r := roads[j]; r != nil {
			d.RoadMiles = domain.MetresToMiles(max(r.Metres, geodesic[j]))
			d.DurationMinutes = domain.SecondsToMinutes(r.Seconds)
			d.Method, d.Cached = r.Method, r.cached
		} else {
			d.RoadMiles = domain.Detour(straight, s.detourFactor)
			d.DurationMinutes = math.Round(d.RoadMiles/s.fallbackMph*600) / 10
			d.Method = domain.MethodGeodesicDetour
		}
		d.EstimatedRoadMiles = d.RoadMiles
		results[i].Distance = &d
	}
	return results, nil
}

type roadResult struct {
	domain.CachedRoute
	cached bool
}

// road answers from the cache, then asks the providers in priority order for the misses (chunks of 25, one timeout
// per call); a provider that fails or times out hands over to the next. nil entries = use the detour estimate.
func (s *distanceServiceImpl) road(ctx context.Context, from domain.Point, to []domain.Point) []*roadResult {
	out := make([]*roadResult, len(to))
	var missing []int
	for i, p := range to {
		if s.cache != nil {
			if c, ok := s.cache.Get(from, p); ok {
				out[i] = &roadResult{CachedRoute: c, cached: true}
				continue
			}
		}
		missing = append(missing, i)
	}
	if len(missing) == 0 || len(s.routers) == 0 {
		return out
	}
	const chunk = 25
	for start := 0; start < len(missing); start += chunk {
		part := missing[start:min(start+chunk, len(missing))]
		points := make([]domain.Point, len(part))
		for k, i := range part {
			points[k] = to[i]
		}
		for _, r := range s.routers {
			callCtx, cancel := context.WithTimeout(ctx, s.timeout)
			routes, err := r.Routes(callCtx, from, points)
			cancel()
			if err != nil || len(routes) != len(points) {
				s.log.WarnContext(ctx, "routing provider failed; trying the next", "provider", r.Name(), "error", err)
				continue
			}
			for k, i := range part {
				c := domain.CachedRoute{Route: routes[k], Method: r.Method()}
				out[i] = &roadResult{CachedRoute: c}
				if s.cache != nil {
					s.cache.Put(from, to[i], c)
				}
			}
			break
		}
	}
	return out
}

func nonEmpty(parts ...string) []string {
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
