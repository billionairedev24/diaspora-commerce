// Package httpapi is the inbound HTTP adapter for storefront (contract: contracts/openapi/storefront.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
)

// Handlers exposes storefront use cases over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, public, authenticated func(http.Handler) http.Handler)
}

type handlersImpl struct{ svc app.StorefrontService }

var _ Handlers = (*handlersImpl)(nil)

// New returns the handlers.
func New(svc app.StorefrontService) Handlers { return &handlersImpl{svc: svc} }

func (h *handlersImpl) Register(mux *http.ServeMux, public, authenticated func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/themes/vet", public(http.HandlerFunc(h.vet)))
	mux.Handle("GET /v1/storefronts/{slug}", public(http.HandlerFunc(h.public)))
	mux.Handle("GET /v1/storefronts/me/draft", authenticated(http.HandlerFunc(h.draft)))
	mux.Handle("PUT /v1/storefronts/me/draft", authenticated(http.HandlerFunc(h.save)))
	mux.Handle("POST /v1/storefronts/me/publish", authenticated(http.HandlerFunc(h.publish)))
	mux.Handle("GET /v1/moderation/social-links", authenticated(http.HandlerFunc(h.pending)))
	mux.Handle("POST /v1/moderation/social-links/{orgId}/{network}", authenticated(http.HandlerFunc(h.decide)))
}

func (h *handlersImpl) vet(w http.ResponseWriter, r *http.Request) {
	var in domain.ThemeInput
	if !decode(w, r, &in) {
		return
	}
	v, err := h.svc.VetTheme(in)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, v)
}

func (h *handlersImpl) public(w http.ResponseWriter, r *http.Request) {
	s, err := h.svc.Public(r.Context(), r.PathValue("slug"))
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=60, stale-while-revalidate=600")
	writeJSON(w, s)
}

func (h *handlersImpl) draft(w http.ResponseWriter, r *http.Request) {
	a, _ := auth.FromContext(r.Context())
	s, err := h.svc.Draft(r.Context(), a)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, s)
}

func (h *handlersImpl) save(w http.ResponseWriter, r *http.Request) {
	a, _ := auth.FromContext(r.Context())
	var in domain.Storefront
	if !decode(w, r, &in) {
		return
	}
	s, err := h.svc.SaveDraft(r.Context(), a, in)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, s)
}

func (h *handlersImpl) publish(w http.ResponseWriter, r *http.Request) {
	a, _ := auth.FromContext(r.Context())
	s, vet, err := h.svc.Publish(r.Context(), a)
	if errors.Is(err, domain.ErrThemeFails) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"type": problem.TypeURI("storefront.theme-contrast"), "status": 422,
			"title": "Unprocessable Entity", "code": "storefront.theme-contrast",
			"detail": "Button labels or body text would be unreadable; adjust the colours.", "checks": vet.Checks,
		})
		return
	}
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, map[string]any{"storefront": s, "vetting": vet})
}

func (h *handlersImpl) pending(w http.ResponseWriter, r *http.Request) {
	a, _ := auth.FromContext(r.Context())
	out, err := h.svc.PendingLinks(r.Context(), a)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, map[string]any{"storefronts": out})
}

func (h *handlersImpl) decide(w http.ResponseWriter, r *http.Request) {
	a, _ := auth.FromContext(r.Context())
	var b struct {
		Approve bool `json:"approve"`
	}
	if !decode(w, r, &b) {
		return
	}
	s, err := h.svc.DecideLink(r.Context(), a, r.PathValue("orgId"), r.PathValue("network"), b.Approve)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	writeJSON(w, s)
}

func decode(w http.ResponseWriter, r *http.Request, dst any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10)).Decode(dst); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "storefront.invalid-json", "Malformed request body"))
		return false
	}
	return true
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalid), errors.Is(err, domain.ErrInvalidColor):
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "storefront.invalid", Detail: err.Error()})
	case errors.Is(err, domain.ErrSlugTaken):
		problem.Write(w, r, problem.New(http.StatusConflict, "storefront.slug-taken", "That store address is taken"))
	case errors.Is(err, domain.ErrConflict):
		problem.Write(w, r, problem.New(http.StatusConflict, "storefront.concurrent-update", "Reload and try again"))
	case errors.Is(err, domain.ErrNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "storefront.not-found", "Storefront not found"))
	case errors.Is(err, app.ErrForbidden):
		problem.Write(w, r, problem.New(http.StatusForbidden, "storefront.forbidden", "Not allowed"))
	default:
		problem.Write(w, r, problem.New(http.StatusInternalServerError, "storefront.error", "Unexpected error"))
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
