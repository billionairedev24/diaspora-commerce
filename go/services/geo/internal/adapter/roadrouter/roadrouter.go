// Package roadrouter holds the routing providers behind port.RoadRouter: the Google Routes API computeRouteMatrix and
// a self-hosted OSRM table service. Each is enabled only when configured (API key / base URL); tests use httptest
// servers, never the network.
package roadrouter

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// MaxDestinations is the most destinations one provider call sends; the distance service chunks larger batches.
const MaxDestinations = 25

// ErrNoRoute is returned when the provider answered but found no driving route to a destination.
var ErrNoRoute = errors.New("no road route")

type osrmImpl struct {
	base   string
	client *http.Client
}

var _ port.RoadRouter = (*osrmImpl)(nil)

// NewOSRM returns a provider over an OSRM server's table service (self-hosted; base like http://osrm:5000).
func NewOSRM(base string, client *http.Client) port.RoadRouter {
	return &osrmImpl{base: strings.TrimRight(base, "/"), client: client}
}

func (o *osrmImpl) Name() string                  { return "osrm" }
func (o *osrmImpl) Method() domain.DistanceMethod { return domain.MethodRoadOSRM }

// Routes calls GET /table/v1/driving/{lng,lat;…}?sources=0&annotations=distance,duration (metres, seconds).
func (o *osrmImpl) Routes(ctx context.Context, from domain.Point, to []domain.Point) ([]domain.Route, error) {
	if len(to) == 0 {
		return []domain.Route{}, nil
	}
	if len(to) > MaxDestinations {
		return nil, fmt.Errorf("osrm: at most %d destinations", MaxDestinations)
	}
	coords := make([]string, 0, len(to)+1)
	coords = append(coords, lngLat(from))
	for _, p := range to {
		coords = append(coords, lngLat(p))
	}
	u := o.base + "/table/v1/driving/" + strings.Join(coords, ";") + "?sources=0&annotations=distance,duration"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("osrm request: %w", err)
	}
	var out struct {
		Code      string       `json:"code"`
		Message   string       `json:"message"`
		Distances [][]*float64 `json:"distances"`
		Durations [][]*float64 `json:"durations"`
	}
	if err := doJSON(o.client, req, "osrm", &out); err != nil {
		return nil, err
	}
	if out.Code != "Ok" || len(out.Distances) != 1 || len(out.Distances[0]) != len(to)+1 ||
		len(out.Durations) != 1 || len(out.Durations[0]) != len(to)+1 {
		return nil, fmt.Errorf("osrm: %s %s", out.Code, out.Message)
	}
	routes := make([]domain.Route, len(to))
	for i := range to {
		d, s := out.Distances[0][i+1], out.Durations[0][i+1]
		if d == nil || s == nil {
			return nil, fmt.Errorf("osrm destination %d: %w", i, ErrNoRoute)
		}
		routes[i] = domain.Route{Metres: *d, Seconds: *s}
	}
	return routes, nil
}

type googleImpl struct {
	key    string
	base   string
	client *http.Client
}

var _ port.RoadRouter = (*googleImpl)(nil)

const defaultGoogleRoutesBase = "https://routes.googleapis.com"

// NewGoogle returns a provider over the Google Routes API computeRouteMatrix (driving, metric). base may be empty for
// the public endpoint (tests pass httptest URLs).
func NewGoogle(apiKey, base string, client *http.Client) port.RoadRouter {
	if base == "" {
		base = defaultGoogleRoutesBase
	}
	return &googleImpl{key: apiKey, base: strings.TrimRight(base, "/"), client: client}
}

func (g *googleImpl) Name() string                  { return "google" }
func (g *googleImpl) Method() domain.DistanceMethod { return domain.MethodRoadGoogle }

type latLngJSON struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

type waypointJSON struct {
	Waypoint struct {
		Location struct {
			LatLng latLngJSON `json:"latLng"`
		} `json:"location"`
	} `json:"waypoint"`
}

func waypoint(p domain.Point) waypointJSON {
	var w waypointJSON
	w.Waypoint.Location.LatLng = latLngJSON{Latitude: p.Lat, Longitude: p.Lng}
	return w
}

// Routes calls POST /distanceMatrix/v2:computeRouteMatrix (travelMode DRIVE, units METRIC) with the key in
// X-Goog-Api-Key and a field mask; elements come back in any order and are matched by destinationIndex.
func (g *googleImpl) Routes(ctx context.Context, from domain.Point, to []domain.Point) ([]domain.Route, error) {
	if len(to) == 0 {
		return []domain.Route{}, nil
	}
	if len(to) > MaxDestinations {
		return nil, fmt.Errorf("google routes: at most %d destinations", MaxDestinations)
	}
	dests := make([]waypointJSON, len(to))
	for i, p := range to {
		dests[i] = waypoint(p)
	}
	body, err := json.Marshal(map[string]any{
		"origins": []waypointJSON{waypoint(from)}, "destinations": dests,
		"travelMode": "DRIVE", "units": "METRIC",
	})
	if err != nil {
		return nil, fmt.Errorf("google routes body: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.base+"/distanceMatrix/v2:computeRouteMatrix",
		bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("google routes request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Goog-Api-Key", g.key)
	req.Header.Set("X-Goog-FieldMask", "originIndex,destinationIndex,distanceMeters,duration,condition,status")
	var elements []struct {
		DestinationIndex int    `json:"destinationIndex"`
		DistanceMeters   *int64 `json:"distanceMeters"`
		Duration         string `json:"duration"`
		Condition        string `json:"condition"`
		Status           struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"status"`
	}
	if err := doJSON(g.client, req, "google routes", &elements); err != nil {
		return nil, err
	}
	routes := make([]domain.Route, len(to))
	seen := make([]bool, len(to))
	for _, e := range elements {
		if e.DestinationIndex < 0 || e.DestinationIndex >= len(to) {
			continue
		}
		if e.Status.Code != 0 || e.Condition != "ROUTE_EXISTS" || e.DistanceMeters == nil {
			return nil, fmt.Errorf("google routes destination %d (%s %s): %w", e.DestinationIndex, e.Condition,
				e.Status.Message, ErrNoRoute)
		}
		secs, err := time.ParseDuration(e.Duration)
		if err != nil {
			return nil, fmt.Errorf("google routes duration %q: %w", e.Duration, err)
		}
		routes[e.DestinationIndex] = domain.Route{Metres: float64(*e.DistanceMeters), Seconds: secs.Seconds()}
		seen[e.DestinationIndex] = true
	}
	for i, ok := range seen {
		if !ok {
			return nil, fmt.Errorf("google routes: destination %d missing: %w", i, ErrNoRoute)
		}
	}
	return routes, nil
}

func doJSON(client *http.Client, req *http.Request, name string, out any) error {
	res, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("%s: %w", name, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1024))
		return fmt.Errorf("%s: status %d: %s", name, res.StatusCode, msg)
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(out); err != nil {
		return fmt.Errorf("%s decode: %w", name, err)
	}
	return nil
}

func lngLat(p domain.Point) string {
	return strconv.FormatFloat(p.Lng, 'f', 6, 64) + "," + strconv.FormatFloat(p.Lat, 'f', 6, 64)
}
