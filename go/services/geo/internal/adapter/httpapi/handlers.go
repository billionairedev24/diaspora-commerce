// Package httpapi is the inbound HTTP adapter for geo (contract: contracts/openapi/geo.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"

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

// autocomplete answers GET /v1/addresses/autocomplete?q=&session=&country=&kind=&near=lat,lng&radiusMiles=&lang=
// (lat & lng / lon are accepted instead of near; Accept-Language when lang is absent).
func (h *handlersImpl) autocomplete(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	aq := domain.AutocompleteQuery{Input: q.Get("q"), SessionToken: q.Get("session"), Country: q.Get("country"),
		Kind: domain.AddressKind(strings.ToLower(q.Get("kind"))), Language: q.Get("lang")}
	if aq.Language == "" {
		aq.Language = primaryLanguage(r.Header.Get("Accept-Language"))
	}
	near, err := parseNear(q)
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-point", err.Error()))
		return
	}
	aq.Near = near
	if raw := q.Get("radiusMiles"); raw != "" {
		miles, err := strconv.ParseFloat(raw, 64)
		if err != nil || miles <= 0 || miles > 100 {
			problem.Write(w, r, problem.New(http.StatusBadRequest, "geo.invalid-radius", "radiusMiles must be a number from 0 to 100"))
			return
		}
		aq.RadiusMeters = miles * metersPerMile
	}
	out, err := h.addresses.Autocomplete(r.Context(), aq)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	// Session-specific: never cached by browsers or the edge.
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, out)
}

const metersPerMile = 1609.344

// parseNear reads the bias point from near=lat,lng or lat & lng (lon) parameters.
func parseNear(q url.Values) (*domain.Point, error) {
	latRaw, lngRaw := q.Get("lat"), q.Get("lng")
	if lngRaw == "" {
		lngRaw = q.Get("lon")
	}
	if near := q.Get("near"); near != "" {
		var ok bool
		latRaw, lngRaw, ok = strings.Cut(near, ",")
		if !ok {
			return nil, errors.New("near must be lat,lng")
		}
	}
	if latRaw == "" && lngRaw == "" {
		return nil, nil
	}
	lat, err1 := strconv.ParseFloat(strings.TrimSpace(latRaw), 64)
	lng, err2 := strconv.ParseFloat(strings.TrimSpace(lngRaw), 64)
	if err1 != nil || err2 != nil || domain.CheckPoint(lat, lng) != nil {
		return nil, errors.New("near must be a valid lat,lng")
	}
	return &domain.Point{Lat: lat, Lng: lng}, nil
}

// primaryLanguage is the first tag of an Accept-Language header ("fr-CA,fr;q=0.8" → "fr-CA").
func primaryLanguage(header string) string {
	tag, _, _ := strings.Cut(header, ",")
	tag, _, _ = strings.Cut(tag, ";")
	tag = strings.TrimSpace(tag)
	if tag == "*" {
		return ""
	}
	return tag
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
	case errors.Is(err, domain.ErrInvalidQuery):
		problem.Write(w, r, &problem.Error{Status: http.StatusBadRequest, Code: "geo.invalid-query", Detail: err.Error()})
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
