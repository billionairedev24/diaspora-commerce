// Package httpapi is the inbound HTTP adapter for promise (contract: contracts/openapi/promise.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
)

var promiseID = regexp.MustCompile(`^prm_[a-z2-7]{24}$`)

// Handlers exposes promises over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, optionalAuth func(http.Handler) http.Handler)
}

type handlersImpl struct{ svc app.PromiseService }

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(svc app.PromiseService) Handlers { return &handlersImpl{svc: svc} }

// Register mounts the routes. Promises are public reads (search cards and PDP for anonymous shoppers).
func (h *handlersImpl) Register(mux *http.ServeMux, optionalAuth func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/promises", optionalAuth(http.HandlerFunc(h.batch)))
	mux.Handle("GET /v1/promises/{id}", optionalAuth(http.HandlerFunc(h.get)))
}

func (h *handlersImpl) batch(w http.ResponseWriter, r *http.Request) {
	var req app.BatchRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 512<<10)).Decode(&req); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "promise.invalid-json", "Malformed request body"))
		return
	}
	res, err := h.svc.Promise(r.Context(), req)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=60")
	writeJSON(w, res)
}

func (h *handlersImpl) get(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !promiseID.MatchString(id) {
		problem.Write(w, r, problem.New(http.StatusNotFound, "promise.not-found", "Unknown or expired promise"))
		return
	}
	p, err := h.svc.Get(r.Context(), id)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, p)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalid):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "promise.invalid-request", err.Error()))
	case errors.Is(err, domain.ErrNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "promise.not-found", "Unknown or expired promise"))
	default:
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "promise.unavailable", "Promises are temporarily unavailable"))
	}
}
