package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Seller Center "Add a product" (S382) and Improve your image (S391): contracts/openapi/gateway-bff-seller.yaml, tag Matching.

func (h *handlersImpl) registerSellerMatch(route func(pattern string, fn http.HandlerFunc)) {
	route("POST /v1/seller/catalog/matches", h.matchCatalog)
	route("POST /v1/seller/offers", h.createOffer)
	route("POST /v1/seller/products/{atin}/change-requests", h.fileChangeRequest)
	route("GET /v1/seller/change-requests", h.changeRequests)
	route("POST /v1/seller/change-requests/{requestId}/withdraw", h.withdrawChangeRequest)
	route("GET /v1/seller/image-tasks", h.imageTasks)
}

func (h *handlersImpl) matchCatalog(w http.ResponseWriter, r *http.Request) {
	var b domain.MatchInput
	r.Body = http.MaxBytesReader(w, r.Body, app.MaxMatchBodyBytes)
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMatch.Match(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) createOffer(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	r.Body = http.MaxBytesReader(w, r.Body, app.MaxMatchBodyBytes)
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMatch.CreateOffer(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) fileChangeRequest(w http.ResponseWriter, r *http.Request) {
	var b domain.ChangeRequestInput
	r.Body = http.MaxBytesReader(w, r.Body, app.MaxMatchBodyBytes)
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerMatch.FileChangeRequest(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) changeRequests(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	page, size := 0, 0
	if v := q.Get("page"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"page": "Must be a number"}))
			return
		}
		page = n
	}
	if v := q.Get("size"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"size": "Must be a number"}))
			return
		}
		size = n
	}
	v, err := h.s.SellerMatch.ChangeRequests(r.Context(), callerFrom(r.Context()), q.Get("status"), page, size)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) withdrawChangeRequest(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMatch.WithdrawChangeRequest(r.Context(), callerFrom(r.Context()), r.PathValue("requestId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) imageTasks(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerMatch.ImageTasks(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}
