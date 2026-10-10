// Package httpapi is the inbound HTTP adapter for media (contract: contracts/openapi/media.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/pgrepo"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// Handlers exposes media use cases over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, authenticated func(http.Handler) http.Handler)
}

type handlersImpl struct{ media app.MediaService }

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(media app.MediaService) Handlers { return &handlersImpl{media: media} }

func (h *handlersImpl) Register(mux *http.ServeMux, authenticated func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/uploads", authenticated(http.HandlerFunc(h.request)))
	mux.Handle("POST /v1/assets/{id}/complete", authenticated(http.HandlerFunc(h.complete)))
	mux.Handle("GET /v1/assets/{id}", authenticated(http.HandlerFunc(h.get)))
	mux.Handle("GET /v1/assets", authenticated(http.HandlerFunc(h.list)))
}

type uploadRequest struct {
	Purpose     domain.Purpose `json:"purpose"`
	FileName    string         `json:"fileName"`
	ContentType string         `json:"contentType"`
	Size        int64          `json:"size"`
}

func (h *handlersImpl) request(w http.ResponseWriter, r *http.Request) {
	actor, _ := auth.FromContext(r.Context())
	var req uploadRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "media.invalid-json", "Body must be an upload request"))
		return
	}
	a, up, err := h.media.RequestUpload(r.Context(), actor, req.Purpose, req.FileName, req.ContentType, req.Size)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"asset": a, "upload": up})
}

func (h *handlersImpl) complete(w http.ResponseWriter, r *http.Request) {
	actor, _ := auth.FromContext(r.Context())
	a, err := h.media.Complete(r.Context(), actor, r.PathValue("id"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
}

func (h *handlersImpl) get(w http.ResponseWriter, r *http.Request) {
	actor, _ := auth.FromContext(r.Context())
	a, err := h.media.Get(r.Context(), actor, r.PathValue("id"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
}

func (h *handlersImpl) list(w http.ResponseWriter, r *http.Request) {
	actor, _ := auth.FromContext(r.Context())
	as, err := h.media.List(r.Context(), actor, domain.Purpose(r.URL.Query().Get("purpose")))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"assets": as})
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrRejected):
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "media.rejected", Detail: err.Error()})
	case errors.Is(err, app.ErrForbidden):
		problem.Write(w, r, problem.New(http.StatusForbidden, "media.forbidden", "Not your asset"))
	case errors.Is(err, pgrepo.ErrNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "media.not-found", "Asset not found"))
	case errors.Is(err, app.ErrState):
		problem.Write(w, r, &problem.Error{Status: http.StatusConflict, Code: "media.not-uploaded", Detail: err.Error()})
	default:
		problem.Write(w, r, problem.New(http.StatusBadGateway, "media.storage-unavailable", "Storage is temporarily unavailable"))
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
