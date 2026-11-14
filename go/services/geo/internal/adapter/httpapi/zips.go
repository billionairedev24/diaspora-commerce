package httpapi

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

const (
	maxNearbyMiles = 150
	maxNearbyLimit = 200
)

type zipHandlersImpl struct {
	zips port.ZipIndex
}

var _ Handlers = (*zipHandlersImpl)(nil)

// NewZips returns GET /v1/zips/nearby: the cities around a point, from the ZIP-centroid table.
func NewZips(zips port.ZipIndex) Handlers { return &zipHandlersImpl{zips: zips} }

func (h *zipHandlersImpl) Register(mux *http.ServeMux, public, _ func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/zips/nearby", public(http.HandlerFunc(h.nearby)))
	mux.Handle("GET /v1/zips/by-city", public(http.HandlerFunc(h.byCity)))
}

func (h *zipHandlersImpl) nearby(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	lat, e1 := strconv.ParseFloat(q.Get("lat"), 64)
	lon, e2 := strconv.ParseFloat(q.Get("lon"), 64)
	miles, e3 := strconv.ParseFloat(q.Get("miles"), 64)
	if e1 != nil || e2 != nil || e3 != nil || lat < -90 || lat > 90 || lon < -180 || lon > 180 ||
		miles <= 0 || miles > maxNearbyMiles {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-query",
			"lat, lon and miles (1-150) are required numbers"))
		return
	}
	limit := 50
	if v, err := strconv.Atoi(q.Get("limit")); err == nil && v > 0 {
		limit = min(v, maxNearbyLimit)
	}
	items := h.zips.Nearby(lat, lon, miles, limit)
	if items == nil {
		items = []domain.NearbyCity{}
	}
	w.Header().Set("Cache-Control", "public, max-age=86400")
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

// byCity answers the ZIPs of a city from the same table, with the representative one (a place known only by city and
// state, e.g. detected from the edge, gets a real ZIP from real data; 404 when the table has no such city).
func (h *zipHandlersImpl) byCity(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	city, state := strings.TrimSpace(q.Get("city")), strings.TrimSpace(q.Get("state"))
	if city == "" || len(city) > 120 || len(state) != 2 {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-query", "city and a two-letter state are required"))
		return
	}
	out, ok := h.zips.ByCity(city, state)
	if !ok {
		problem.Write(w, r, problem.New(http.StatusNotFound, "geo.not-found", "No ZIP codes known for that city"))
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=86400")
	writeJSON(w, http.StatusOK, out)
}
