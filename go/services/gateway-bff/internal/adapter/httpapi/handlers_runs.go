package httpapi

import (
	"net/http"
)

// Run economics (S265) and location advertorials (S263) in the staff console's Pre-orders module
// (gateway-bff-staff.yaml, tags Run economics and Advertorials), backed by delivery-runs.

func (h *handlersImpl) registerStaffRuns(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/economics-settings", h.staffEconomicsSettings)
	route("PUT /v1/staff/economics-settings", h.staffUpdateEconomicsSettings)
	route("GET /v1/staff/economics-settings/changes", h.staffEconomicsChanges)
	route("POST /v1/staff/preorders/economics-preview", h.staffPlanWindow)
	route("GET /v1/staff/preorders/{id}/economics", h.staffWindowEconomics)
	route("GET /v1/staff/delivery-runs/locations", h.staffRunLocations)
	route("GET /v1/staff/delivery-runs/locations/{id}/history", h.staffRunLocationHistory)
	route("GET /v1/staff/delivery-runs/runs", h.staffPreorderRuns)
	route("POST /v1/staff/delivery-runs/runs", h.staffCreateRun)
	route("GET /v1/staff/delivery-runs/runs/{id}", h.staffDeliveryRun)
	route("PUT /v1/staff/delivery-runs/runs/{id}", h.staffUpdateRun)
	route("DELETE /v1/staff/delivery-runs/runs/{id}", h.staffDeleteRun)
	route("POST /v1/staff/delivery-runs/runs/{id}/announce", h.staffAnnounceRun)
	route("POST /v1/staff/delivery-runs/runs/{id}/open", h.staffOpenRun)
	route("POST /v1/staff/delivery-runs/runs/{id}/extend", h.staffExtendRun)
	route("POST /v1/staff/delivery-runs/runs/{id}/confirm", h.staffConfirmRun)
	route("POST /v1/staff/delivery-runs/runs/{id}/cancel", h.staffCancelRun)
	route("GET /v1/staff/delivery-runs/runs/{id}/log", h.staffRunLog)
	route("GET /v1/staff/delivery-runs/runs/{id}/orders", h.staffRunOrders)
	route("GET /v1/staff/delivery-runs/runs/{id}/pnl", h.staffRunPnl)
	route("POST /v1/staff/delivery-runs/economics-preview", h.staffPreorderPlanRun)
	route("GET /v1/staff/advertorials", h.staffAdvertorials)
	route("POST /v1/staff/advertorials", h.staffCreateAdvertorial)
	route("POST /v1/staff/advertorials/generate", h.staffGenerateAdvertorial)
	route("GET /v1/staff/advertorials/{id}", h.staffAdvertorial)
	route("PUT /v1/staff/advertorials/{id}", h.staffUpdateAdvertorial)
	route("POST /v1/staff/advertorials/{id}/{action}", h.staffAdvertorialAction)
}

func (h *handlersImpl) staffEconomicsSettings(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.EconomicsSettings(r.Context(), callerFrom(r.Context()))
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdateEconomicsSettings(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.UpdateEconomicsSettings(r.Context(), callerFrom(r.Context()), r.Header.Get("If-Match"), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffEconomicsChanges(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.EconomicsChanges(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPlanWindow(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.PlanWindow(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffWindowEconomics(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.WindowEconomics(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRunLocations(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.RunLocations(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRunLocationHistory(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.RunLocationHistory(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderRuns(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Runs(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryRun(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Run(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCreateRun(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.CreateRun(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffUpdateRun(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.UpdateRun(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeleteRun(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Staff.Preorders.DeleteRun(r.Context(), callerFrom(r.Context()), r.PathValue("id")))
}

func (h *handlersImpl) staffAnnounceRun(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.ActRun(r.Context(), callerFrom(r.Context()), r.PathValue("id"), "announce")
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffOpenRun(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.ActRun(r.Context(), callerFrom(r.Context()), r.PathValue("id"), "open")
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffExtendRun(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.ExtendRun(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffConfirmRun(w http.ResponseWriter, r *http.Request) {
	// The body is optional: a run at its minimum needs no reason.
	b := map[string]any{}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.ConfirmRun(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCancelRun(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.CancelRun(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRunLog(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.RunLog(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRunOrders(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.RunOrders(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRunPnl(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.RunPnl(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderPlanRun(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.PlanRun(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAdvertorials(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Advertorials(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAdvertorial(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Advertorial(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffGenerateAdvertorial(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.GenerateAdvertorial(r.Context(), callerFrom(r.Context()), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCreateAdvertorial(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.CreateAdvertorial(r.Context(), callerFrom(r.Context()), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffUpdateAdvertorial(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.UpdateAdvertorial(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAdvertorialAction(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.ActAdvertorial(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.PathValue("action"), r.Header.Get("If-Match"))
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}
