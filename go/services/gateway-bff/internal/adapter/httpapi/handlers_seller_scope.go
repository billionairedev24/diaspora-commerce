package httpapi

import (
	"encoding/json"
	"net/http"
)

// Seller catalog scope: contracts/openapi/gateway-bff-seller.yaml, tag Catalog scope. The answers are catalog's and
// seller-service's JSON.

func (h *handlersImpl) registerSellerScope(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/seller/catalog-scope", h.sellerScope)
	route("GET /v1/seller/departments", h.sellerDepartments)
	route("POST /v1/seller/department-requests", h.requestDepartment)
}

func (h *handlersImpl) sellerScope(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerScope.Scope(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) sellerDepartments(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.SellerScope.Departments(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) requestDepartment(w http.ResponseWriter, r *http.Request) {
	var b json.RawMessage
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.SellerScope.RequestDepartment(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}
