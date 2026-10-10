// Package httpapi is the inbound HTTP adapter for geo (contract: contracts/openapi/geo.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
)

// Handlers exposes the geo use cases over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, public, authenticated func(http.Handler) http.Handler)
}

type handlersImpl struct {
	addresses app.AddressService
	reverse   app.ReverseService
	zones     app.ZoneService
	distances app.DistanceService
}

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(addresses app.AddressService, reverse app.ReverseService, zones app.ZoneService,
	distances app.DistanceService) Handlers {
	return &handlersImpl{addresses: addresses, reverse: reverse, zones: zones, distances: distances}
}

// ZonesWrite is the permission needed to create or change zones.
const ZonesWrite = "geo:zones:write"

func (h *handlersImpl) Register(mux *http.ServeMux, public, authenticated func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/addresses/autocomplete", public(http.HandlerFunc(h.autocomplete)))
	mux.Handle("GET /v1/addresses/resolve/{id}", public(http.HandlerFunc(h.resolve)))
	mux.Handle("POST /v1/addresses/validate", public(http.HandlerFunc(h.validate)))
	mux.Handle("GET /v1/zip/{zip}", public(http.HandlerFunc(h.zip)))
	mux.Handle("GET /v1/reverse", public(http.HandlerFunc(h.reverseGeocode)))
	mux.Handle("GET /v1/zones/covering", public(http.HandlerFunc(h.covering)))
	mux.Handle("POST /v1/distance", public(http.HandlerFunc(h.distance)))
	mux.Handle("POST /v1/distance/batch", public(http.HandlerFunc(h.distanceBatch)))
	mux.Handle("POST /v1/geocode", public(http.HandlerFunc(h.geocode)))
	mux.Handle("GET /v1/zones", authenticated(http.HandlerFunc(h.listZones)))
	mux.Handle("POST /v1/zones", authenticated(auth.Require(ZonesWrite, h.saveZone)))
}

func (h *handlersImpl) autocomplete(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	out, err := h.addresses.Autocomplete(r.Context(), q.Get("q"), q.Get("session"), q.Get("country"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"suggestions": out})
}

func (h *handlersImpl) resolve(w http.ResponseWriter, r *http.Request) {
	a, err := h.addresses.Resolve(r.Context(), r.PathValue("id"), r.URL.Query().Get("session"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
}

func (h *handlersImpl) validate(w http.ResponseWriter, r *http.Request) {
	var a domain.Address
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&a); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-json", "Body must be an address object"))
		return
	}
	v, err := h.addresses.Validate(r.Context(), a)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

func (h *handlersImpl) zip(w http.ResponseWriter, r *http.Request) {
	a, err := h.addresses.LookupZip(r.Context(), r.PathValue("zip"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=86400")
	writeJSON(w, http.StatusOK, a)
}

// reverseGeocode answers GET /v1/reverse?lat=&lng= (lon is accepted as an alias of lng).
func (h *handlersImpl) reverseGeocode(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	lng := q.Get("lng")
	if lng == "" {
		lng = q.Get("lon")
	}
	lat, err1 := strconv.ParseFloat(q.Get("lat"), 64)
	lon, err2 := strconv.ParseFloat(lng, 64)
	if err1 != nil || err2 != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-point", "lat and lng are required numbers"))
		return
	}
	a, err := h.reverse.Reverse(r.Context(), lat, lon)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=3600")
	writeJSON(w, http.StatusOK, a)
}

func (h *handlersImpl) covering(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	lat, err1 := strconv.ParseFloat(q.Get("lat"), 64)
	lon, err2 := strconv.ParseFloat(q.Get("lon"), 64)
	if err1 != nil || err2 != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-point", "lat and lon are required numbers"))
		return
	}
	if raw := q.Get("withinMiles"); raw != "" {
		within, err := strconv.ParseFloat(raw, 64)
		if err != nil {
			problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-radius", "withinMiles must be a number"))
			return
		}
		near, err := h.zones.Near(r.Context(), lat, lon, domain.ZoneType(q.Get("type")), within)
		if err != nil {
			writeErr(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"zones": near})
		return
	}
	zs, err := h.zones.Covering(r.Context(), lat, lon, domain.ZoneType(q.Get("type")))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"zones": zs})
}

type distanceRequest struct {
	From domain.Place `json:"from"`
	To   domain.Place `json:"to"`
}

type batchRequest struct {
	From domain.Place   `json:"from"`
	To   []domain.Place `json:"to"`
}

type batchItem struct {
	Index int `json:"index"`
	*domain.Distance
	Error *problemBody `json:"error,omitempty"`
}

type problemBody struct {
	Code   string `json:"code"`
	Detail string `json:"detail"`
}

func (h *handlersImpl) distance(w http.ResponseWriter, r *http.Request) {
	var body distanceRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&body); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-json", "Body must be {from, to}"))
		return
	}
	d, err := h.distances.Distance(r.Context(), body.From, body.To)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

func (h *handlersImpl) distanceBatch(w http.ResponseWriter, r *http.Request) {
	var body batchRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10)).Decode(&body); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-json", "Body must be {from, to: [...]}"))
		return
	}
	results, err := h.distances.Batch(r.Context(), body.From, body.To)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	items := make([]batchItem, len(results))
	for i, res := range results {
		items[i] = batchItem{Index: res.Index, Distance: res.Distance}
		if res.Err != nil {
			code, detail := placeError(res.Err)
			items[i].Error = &problemBody{Code: code, Detail: detail}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": items})
}

func (h *handlersImpl) geocode(w http.ResponseWriter, r *http.Request) {
	var a domain.Address
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&a); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-json", "Body must be an address object"))
		return
	}
	l, err := h.distances.Geocode(r.Context(), a)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, l)
}

// placeError maps a per-destination batch failure to a problem code.
func placeError(err error) (string, string) {
	switch {
	case errors.Is(err, domain.ErrInvalidPlace), errors.Is(err, domain.ErrInvalidPoint),
		errors.Is(err, domain.ErrInvalidAddress):
		return "geo.invalid-place", err.Error()
	case errors.Is(err, app.ErrNotFound):
		return "geo.place-not-found", "The place could not be located"
	default:
		return "geo.provider-unavailable", "Address lookup is temporarily unavailable"
	}
}

func (h *handlersImpl) listZones(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	zs, err := h.zones.List(r.Context(), domain.ZoneType(q.Get("type")), q.Get("ownerId"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"zones": zs})
}

func (h *handlersImpl) saveZone(w http.ResponseWriter, r *http.Request) {
	var z domain.Zone
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&z); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-json", "Body must be a zone object"))
		return
	}
	saved, err := h.zones.Save(r.Context(), z)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, saved)
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalidPlace):
		problem.Write(w, r, &problem.Error{Status: http.StatusBadRequest, Code: "geo.invalid-place", Detail: err.Error()})
	case errors.Is(err, app.ErrTooManyDestinations):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.too-many-destinations",
			"At most 100 destinations per batch"))
	case errors.Is(err, domain.ErrInvalidPoint):
		problem.Write(w, r, &problem.Error{Status: http.StatusBadRequest, Code: "geo.invalid-point", Detail: "lat must be within ±90 and lng within ±180"})
	case errors.Is(err, domain.ErrInvalidAddress), errors.Is(err, domain.ErrInvalidZone):
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "geo.invalid", Detail: err.Error()})
	case errors.Is(err, app.ErrNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "geo.not-found", "No matching address"))
	default:
		problem.Write(w, r, problem.New(http.StatusBadGateway, "geo.provider-unavailable", "Address lookup is temporarily unavailable"))
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
