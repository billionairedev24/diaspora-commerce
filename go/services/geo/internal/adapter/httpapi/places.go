package httpapi

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

type placeHandlersImpl struct {
	places app.PlaceService
}

var _ Handlers = (*placeHandlersImpl)(nil)

// NewPlaces returns the gazetteer handlers (GET /v1/places/...): reference data, cacheable for a day.
func NewPlaces(places app.PlaceService) Handlers {
	return &placeHandlersImpl{places: places}
}

func (h *placeHandlersImpl) Register(mux *http.ServeMux, public, _ func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/places/countries", public(http.HandlerFunc(h.countries)))
	mux.Handle("GET /v1/places/countries/{country}/regions", public(http.HandlerFunc(h.regions)))
	mux.Handle("GET /v1/places/countries/{country}/regions/{region}/cities", public(http.HandlerFunc(h.cities)))
	mux.Handle("GET /v1/places/search", public(http.HandlerFunc(h.search)))
}

func (h *placeHandlersImpl) countries(w http.ResponseWriter, r *http.Request) {
	cacheable(w)
	writeJSON(w, http.StatusOK, map[string]any{"items": h.places.Countries(r.Context())})
}

func (h *placeHandlersImpl) regions(w http.ResponseWriter, r *http.Request) {
	out, err := h.places.Regions(r.Context(), r.PathValue("country"))
	if err != nil {
		writePlaceErr(w, r, err)
		return
	}
	cacheable(w)
	writeJSON(w, http.StatusOK, out)
}

func (h *placeHandlersImpl) cities(w http.ResponseWriter, r *http.Request) {
	limit, err := intParam(r, "limit", app.MaxCities, app.MaxCities)
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-query", err.Error()))
		return
	}
	out, err := h.places.Cities(r.Context(), r.PathValue("country"), r.PathValue("region"), r.URL.Query().Get("q"), limit)
	if err != nil {
		writePlaceErr(w, r, err)
		return
	}
	cacheable(w)
	writeJSON(w, http.StatusOK, out)
}

func (h *placeHandlersImpl) search(w http.ResponseWriter, r *http.Request) {
	limit, err := intParam(r, "limit", 8, 50)
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-query", err.Error()))
		return
	}
	q := r.URL.Query()
	items, err := h.places.Search(r.Context(), q.Get("q"), q.Get("country"), limit)
	if err != nil {
		writePlaceErr(w, r, err)
		return
	}
	cacheable(w)
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func cacheable(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "public, max-age=86400")
}

var errBadLimit = errors.New("limit must be a positive whole number")

func intParam(r *http.Request, key string, def, maxValue int) (int, error) {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return def, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 1 {
		return 0, errBadLimit
	}
	return min(n, maxValue), nil
}

func writePlaceErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrUnknownPlace):
		problem.Write(w, r, &problem.Error{Status: http.StatusNotFound, Code: "geo.unknown-place", Detail: err.Error()})
	default:
		writeErr(w, r, err)
	}
}
