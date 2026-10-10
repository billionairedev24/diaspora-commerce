// Package httpapi is the inbound HTTP adapter for media (contract: contracts/openapi/media.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

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
	mux.Handle("POST /v1/assets/{id}/review", authenticated(http.HandlerFunc(h.review)))
	mux.Handle("GET /v1/assets/{id}", authenticated(http.HandlerFunc(h.get)))
	mux.Handle("GET /v1/assets", authenticated(http.HandlerFunc(h.list)))
	// Media-hosted long-lived links: the signed token is the credential (e-mailed to suppliers).
	mux.HandleFunc("GET /v1/l/{token}", h.link)
}

// link redirects a media-hosted long-lived link to a fresh short-lived storage URL.
func (h *handlersImpl) link(w http.ResponseWriter, r *http.Request) {
	u, err := h.media.ResolveLink(r.Context(), r.PathValue("token"))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	switch {
	case errors.Is(err, app.ErrLinkExpired):
		problem.Write(w, r, problem.New(http.StatusGone, "media.link-expired",
			"This link has expired. Ask the sender for a new one."))
	case errors.Is(err, app.ErrLinkInvalid):
		problem.Write(w, r, problem.New(http.StatusNotFound, "media.link-not-found", "This link is not valid"))
	case err != nil:
		writeErr(w, r, err)
	default:
		// u is a storage URL media signed itself for the asset the verified token names, not request input.
		http.Redirect(w, r, u, http.StatusFound) //nolint:gosec // G710: target signed by media, not user-controlled

	}
}

type reviewRequest struct {
	Decision domain.ReviewDecision `json:"decision"`
	Note     string                `json:"note"`
}

func (h *handlersImpl) review(w http.ResponseWriter, r *http.Request) {
	actor, _ := auth.FromContext(r.Context())
	var req reviewRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "media.invalid-json", "Body must be a review decision"))
		return
	}
	a, err := h.media.Review(r.Context(), actor, r.PathValue("id"), req.Decision, req.Note)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
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

// maxURLTTLSeconds bounds urlTtlSeconds before the purpose rules apply (a year; media caps it far lower).
const maxURLTTLSeconds = 366 * 24 * 3600

func (h *handlersImpl) get(w http.ResponseWriter, r *http.Request) {
	actor, _ := auth.FromContext(r.Context())
	var ttl time.Duration
	if raw := r.URL.Query().Get("urlTtlSeconds"); raw != "" {
		secs, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || secs < 1 || secs > maxURLTTLSeconds {
			problem.Write(w, r, problem.New(http.StatusBadRequest, "media.invalid-url-ttl",
				"urlTtlSeconds must be a whole number of seconds from 1 to "+strconv.Itoa(maxURLTTLSeconds)))
			return
		}
		ttl = time.Duration(secs) * time.Second
	}
	a, err := h.media.Get(r.Context(), actor, r.PathValue("id"), ttl)
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
		problem.Write(w, r, problem.New(http.StatusForbidden, "media.forbidden", "Not your asset or not allowed for this purpose"))
	case errors.Is(err, pgrepo.ErrNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "media.not-found", "Asset not found"))
	case errors.Is(err, app.ErrState), errors.Is(err, domain.ErrConflict):
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
