package httpapi

import (
	"encoding/json"
	"net/http"
)

// Seller selling approvals: contracts/openapi/gateway-bff-seller.yaml, tag Selling approvals. The answers are
// seller-service's JSON.

func (h *handlersImpl) registerSellerSelling(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/seller/selling-status", h.sellingStatus)
	route("GET /v1/seller/selling-approvals", h.sellingApprovals)
	route("GET /v1/seller/selling-capabilities", h.sellingCapabilities)
	route("GET /v1/seller/selling-applications", h.sellingApplications)
	route("POST /v1/seller/selling-applications", h.applyToSell)
	route("GET /v1/seller/selling-applications/{id}", h.sellingApplication)
	route("POST /v1/seller/selling-applications/{id}/resubmit", h.resubmitSellingApplication)
}

func (h *handlersImpl) sellingStatus(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSelling.Status(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellingApprovals(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSelling.Approvals(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellingCapabilities(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSelling.Capabilities(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellingApplications(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSelling.Applications(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellingApplication(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerSelling.Application(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) applyToSell(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerSelling.Apply(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) resubmitSellingApplication(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerSelling.Resubmit(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b)
	h.reply(w, r, http.StatusOK, v, err)
}
