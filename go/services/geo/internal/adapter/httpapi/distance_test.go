package httpapi_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/fixture"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/smarty"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

// flat pretends every destination is 10 km away, so the handler contract is tested without PostGIS.
type flat struct{}

func (flat) Geodesic(_ context.Context, _ domain.Point, to []domain.Point) ([]float64, error) {
	out := make([]float64, len(to))
	for i := range out {
		out[i] = 10000
	}
	return out, nil
}

func server(t *testing.T) *httptest.Server {
	t.Helper()
	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))
	addresses := app.NewAddressService([]port.Autocompleter{fixture.New()}, nil, smarty.NewRules(), quiet)
	distances := app.NewDistanceService(addresses, flat{}, app.RoutingOptions{}, quiet)
	mux := http.NewServeMux()
	httpapi.New(addresses, app.NewReverseService(nil, quiet), nil, distances).Register(mux,
		auth.Middleware(nil, true), auth.Middleware(nil, false))
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func post(t *testing.T, url, body string) (int, map[string]any) {
	t.Helper()
	req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

func TestPostDistance(t *testing.T) {
	srv := server(t)
	status, body := post(t, srv.URL+"/v1/distance", `{"from":{"lat":29.7589,"lng":-95.3677},"to":{"zip":"77036"}}`)
	if status != http.StatusOK || body["straightLineMiles"] != 6.21 || body["estimatedRoadMiles"] != 8.07 || body["roadMiles"] != 8.07 ||
		body["method"] != "GEODESIC_DETOUR" {
		t.Fatalf("%d %v", status, body)
	}
	status, body = post(t, srv.URL+"/v1/distance", `{"from":{},"to":{"zip":"77036"}}`)
	if status != http.StatusBadRequest || body["code"] != "geo.invalid-place" {
		t.Fatalf("%d %v", status, body)
	}
}

func TestPostDistanceBatchAndGeocode(t *testing.T) {
	srv := server(t)
	status, body := post(t, srv.URL+"/v1/distance/batch",
		`{"from":{"zip":"77002"},"to":[{"zip":"77036"},{"zip":"99999"},{"lat":29.6,"lng":-95.6}]}`)
	results, _ := body["results"].([]any)
	if status != http.StatusOK || len(results) != 3 {
		t.Fatalf("%d %v", status, body)
	}
	miss, _ := results[1].(map[string]any)
	if e, _ := miss["error"].(map[string]any); e == nil || e["code"] != "geo.place-not-found" {
		t.Fatalf("missing ZIP: %v", miss)
	}
	status, body = post(t, srv.URL+"/v1/geocode", `{"line1":"9800 Bissonnet St","city":"Houston","state":"TX",`+
		`"postalCode":"77036"}`)
	if status != http.StatusOK || body["precision"] != "ADDRESS" || body["lat"] != 29.6713 {
		t.Fatalf("%d %v", status, body)
	}
}
