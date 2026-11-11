package httpapi

import (
	"net/http"
	"strconv"

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
