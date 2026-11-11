package httpapi

import (
	"net/http"
)

func (h *handlersImpl) registerStaffTax(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/tax/profiles", h.staffTaxProfiles)
	route("GET /v1/staff/tax/profiles/{code}", h.staffTaxProfile)
	route("POST /v1/staff/tax/profiles/{code}/status", h.staffTaxProfileStatus)
	route("GET /v1/staff/tax/reviews", h.staffTaxReviews)
	route("GET /v1/staff/tax/reviews/{atin}", h.staffTaxReview)
	route("POST /v1/staff/tax/reviews/{atin}/approve", h.staffTaxApprove)
	route("POST /v1/staff/tax/reviews/{atin}/reclassify", h.staffTaxReclassify)
	route("POST /v1/staff/tax/reviews/{atin}/request-information", h.staffTaxRequestInformation)
	route("GET /v1/staff/tax/code-map", h.staffTaxCodeMap)
	route("GET /v1/staff/tax/code-map/export", h.staffTaxCodeMapExport)
	route("POST /v1/staff/tax/code-map/import", h.staffTaxCodeMapImport)
	route("PUT /v1/staff/tax/code-map/{internalTaxCode}", h.staffTaxCodeUpdate)
	route("GET /v1/staff/tax/method", h.staffTaxMethod)
	route("PUT /v1/staff/tax/method", h.staffTaxChangeMethod)
	route("GET /v1/staff/tax/review-settings", h.staffTaxReviewSettings)
	route("PUT /v1/staff/tax/review-settings", h.staffTaxChangeReviewSettings)
	route("GET /v1/staff/tax/shadow", h.staffTaxShadow)
}

func (h *handlersImpl) staffTaxProfiles(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.Profiles(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxProfile(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.Profile(r.Context(), callerFrom(r.Context()), r.PathValue("code"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxProfileStatus(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.ChangeProfileStatus(r.Context(), callerFrom(r.Context()), r.PathValue("code"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxReviews(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.Reviews(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxReview(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.Review(r.Context(), callerFrom(r.Context()), r.PathValue("atin"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxApprove(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.ApproveReview(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxReclassify(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.ReclassifyReview(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxRequestInformation(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.RequestInformation(r.Context(), callerFrom(r.Context()), r.PathValue("atin"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxCodeMap(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.CodeMap(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxCodeMapExport(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.ExportCodeMap(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("provider"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxCodeMapImport(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.ImportCodeMap(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxCodeUpdate(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.UpdateCode(r.Context(), callerFrom(r.Context()), r.PathValue("internalTaxCode"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxMethod(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.Method(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxChangeMethod(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.ChangeMethod(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxShadow(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.Shadow(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxReviewSettings(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Tax.ReviewSettings(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTaxChangeReviewSettings(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Tax.ChangeReviewSettings(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}
