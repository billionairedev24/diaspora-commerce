package roadrouter_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/adapter/roadrouter"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

var (
	store = domain.Point{Lat: 29.7604, Lng: -95.3698}
	homeA = domain.Point{Lat: 29.7370, Lng: -95.5130}
	homeB = domain.Point{Lat: 29.8000, Lng: -95.4000}
)

func TestOSRMTableParsesDistanceAndDuration(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/table/v1/driving/-95.369800,29.760400;-95.513000,29.737000;") ||
			r.URL.Query().Get("sources") != "0" || r.URL.Query().Get("annotations") != "distance,duration" {
			t.Errorf("unexpected request %s", r.URL)
		}
		_, _ = w.Write([]byte(`{"code":"Ok","distances":[[0,16093.4,8046.7]],"durations":[[0,1260.5,600]]}`))
	}))
	defer srv.Close()
	got, err := roadrouter.NewOSRM(srv.URL, srv.Client()).Routes(context.Background(), store,
		[]domain.Point{homeA, homeB})
	if err != nil || len(got) != 2 || got[0].Metres != 16093.4 || got[0].Seconds != 1260.5 || got[1].Seconds != 600 {
		t.Fatalf("got %+v %v", got, err)
	}
}

func TestOSRMNoRoute(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"code":"Ok","distances":[[0,null]],"durations":[[0,null]]}`))
	}))
	defer srv.Close()
	_, err := roadrouter.NewOSRM(srv.URL, srv.Client()).Routes(context.Background(), store, []domain.Point{homeA})
	if !errors.Is(err, roadrouter.ErrNoRoute) {
		t.Fatalf("want ErrNoRoute, got %v", err)
	}
}

func TestGoogleRouteMatrixParsesElementsInAnyOrder(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/distanceMatrix/v2:computeRouteMatrix" ||
			r.Header.Get("X-Goog-Api-Key") != "k" || !strings.Contains(r.Header.Get("X-Goog-FieldMask"), "duration") {
			t.Errorf("unexpected request %s %s %v", r.Method, r.URL, r.Header)
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["travelMode"] != "DRIVE" || body["units"] != "METRIC" || len(body["destinations"].([]any)) != 2 {
			t.Errorf("unexpected body %v", body)
		}
		_, _ = w.Write([]byte(`[
			{"originIndex":0,"destinationIndex":1,"status":{},"distanceMeters":9000,"duration":"640s","condition":"ROUTE_EXISTS"},
			{"originIndex":0,"destinationIndex":0,"status":{},"distanceMeters":17000,"duration":"1325s","condition":"ROUTE_EXISTS"}]`))
	}))
	defer srv.Close()
	r := roadrouter.NewGoogle("k", srv.URL, srv.Client())
	got, err := r.Routes(context.Background(), store, []domain.Point{homeA, homeB})
	if err != nil || got[0].Metres != 17000 || got[0].Seconds != 1325 || got[1].Metres != 9000 ||
		r.Method() != domain.MethodRoadGoogle {
		t.Fatalf("got %+v %v", got, err)
	}
}

func TestGoogleRouteNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`[{"originIndex":0,"destinationIndex":0,"status":{},"condition":"ROUTE_NOT_FOUND"}]`))
	}))
	defer srv.Close()
	_, err := roadrouter.NewGoogle("k", srv.URL, srv.Client()).Routes(context.Background(), store,
		[]domain.Point{homeA})
	if !errors.Is(err, roadrouter.ErrNoRoute) {
		t.Fatalf("want ErrNoRoute, got %v", err)
	}
}

func TestGoogleDenied(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":{"code":403,"message":"API key not valid"}}`))
	}))
	defer srv.Close()
	if _, err := roadrouter.NewGoogle("k", srv.URL, srv.Client()).Routes(context.Background(), store,
		[]domain.Point{homeA}); err == nil {
		t.Fatal("want an error")
	}
}
