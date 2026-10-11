package httpapi

import (
	"io"
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

// Pre-orders (S270–S275): storefront routes (contracts/openapi/gateway-bff.yaml, tag Pre-orders) and the staff
// console's Pre-orders module (gateway-bff-staff.yaml, tag Pre-orders), both backed by delivery-runs.

func (h *handlersImpl) registerPreorders(route func(pattern string, fn http.HandlerFunc), lookup func(http.HandlerFunc) http.HandlerFunc) {
	if h.s.Preorders == nil {
		return
	}
	route("GET /v1/preorders/resolve", h.resolvePreorder)
	route("GET /v1/preorders/open", h.openPreorders)
	route("POST /v1/preorders/waitlist", lookup(h.joinPreorderWaitlist))
	route("POST /v1/preorders/waitlist/unsubscribe", lookup(h.leavePreorderWaitlist))
}

func (h *handlersImpl) resolvePreorder(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.Preorders.Resolve(r.Context(), callerFrom(r.Context()), q.Get("country"), q.Get("region"), q.Get("city"))
	if err == nil {
		w.Header().Set("Cache-Control", "private, max-age=30")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) openPreorders(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Preorders.Open(r.Context())
	if err == nil {
		w.Header().Set("Cache-Control", "public, max-age=30")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) joinPreorderWaitlist(w http.ResponseWriter, r *http.Request) {
	var b app.PreorderWaitlistRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Preorders.Waitlist(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusAccepted, v, err)
}

func (h *handlersImpl) leavePreorderWaitlist(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Token string `json:"token"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Preorders.LeaveWaitlist(r.Context(), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) setPreorderFulfilment(w http.ResponseWriter, r *http.Request) {
	var b struct {
		WindowID   string `json:"windowId"`
		Fulfilment string `json:"fulfilment"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Cart.SetPreorderFulfilment(r.Context(), callerFrom(r.Context()), b.WindowID, b.Fulfilment)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- staff

func (h *handlersImpl) registerStaffPreorders(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/preorders", h.staffPreorders)
	route("POST /v1/staff/preorders", h.staffCreatePreorder)
	route("GET /v1/staff/preorders/settings", h.staffPreorderSettings)
	route("PUT /v1/staff/preorders/settings", h.staffUpdatePreorderSettings)
	route("GET /v1/staff/preorders/areas", h.staffPreorderAreas)
	route("POST /v1/staff/preorders/areas", h.staffAddPreorderArea)
	route("PATCH /v1/staff/preorders/areas", h.staffTogglePreorderAreas)
	route("DELETE /v1/staff/preorders/areas/{key}", h.staffDeletePreorderArea)
	route("GET /v1/staff/preorders/areas/regions/{country}/{region}", h.staffPreorderRegion)
	route("PUT /v1/staff/preorders/areas/regions/{country}/{region}", h.staffUpdatePreorderRegion)
	route("PATCH /v1/staff/preorders", h.staffSwitchPreorders)
	route("GET /v1/staff/preorders/places/search", h.staffPreorderPlaces)
	route("POST /v1/staff/preorders/address-verify", h.staffVerifyPreorderAddress)
	route("POST /v1/staff/preorders/uploads", h.staffPreorderUpload)
	route("GET /v1/staff/preorders/{id}/products", h.staffPreorderProducts)
	route("GET /v1/staff/preorders/{id}", h.staffPreorder)
	route("PUT /v1/staff/preorders/{id}", h.staffUpdatePreorder)
	route("PATCH /v1/staff/preorders/{id}", h.staffSwitchPreorder)
	route("POST /v1/staff/preorders/{id}/{action}", h.staffPreorderAction)
	route("PUT /v1/staff/preorders/{id}/creatives/{slot}", h.staffPutPreorderCreative)
	route("DELETE /v1/staff/preorders/{id}/creatives/{slot}", h.staffDeletePreorderCreative)
	route("GET /v1/staff/preorders/{id}/orders", h.staffPreorderOrders)
	route("GET /v1/staff/preorders/{id}/summary", h.staffPreorderSummary)
	route("GET /v1/staff/preorders/{id}/exports/{file}", h.staffPreorderExport)
	route("POST /v1/staff/preorders/{id}/late-orders", h.staffPreorderLateOrder)
	route("GET /v1/staff/customers/search", h.staffCustomerSearch)
}

func (h *handlersImpl) staffPreorders(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.List(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

// staffBody decodes a JSON object body.
func staffBody(r *http.Request) (map[string]any, error) {
	b := map[string]any{}
	if err := decode(r, &b, false); err != nil {
		return nil, err
	}
	return b, nil
}

func (h *handlersImpl) staffCreatePreorder(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.Create(r.Context(), callerFrom(r.Context()), b, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffPreorder(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Get(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdatePreorder(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.Update(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSwitchPreorder(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.SetActive(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.Header.Get("If-Match"), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffSwitchPreorders(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.SetActiveMany(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderRegion(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Region(r.Context(), callerFrom(r.Context()), r.PathValue("country"), r.PathValue("region"))
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdatePreorderRegion(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.UpdateRegion(r.Context(), callerFrom(r.Context()), r.PathValue("country"), r.PathValue("region"), r.Header.Get("If-Match"), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderAction(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Act(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.PathValue("action"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPutPreorderCreative(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.PutCreative(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.PathValue("slot"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeletePreorderCreative(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.DeleteCreative(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.PathValue("slot"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderOrders(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Orders(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderSummary(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Summary(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

// staffPreorderExport streams the CSV from delivery-runs without buffering it.
func (h *handlersImpl) staffPreorderExport(w http.ResponseWriter, r *http.Request) {
	f, err := h.s.Staff.Preorders.Export(r.Context(), callerFrom(r.Context()), r.PathValue("id"), r.PathValue("file"), r.URL.Query())
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	defer func() { _ = f.Body.Close() }()
	h.finish(w, callerFrom(r.Context()))
	w.Header().Set("Content-Type", f.ContentType)
	w.Header().Set("Content-Disposition", f.ContentDisposition)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	if _, err := io.Copy(w, f.Body); err != nil {
		h.log.WarnContext(r.Context(), "pre-order export relay interrupted", "error", err)
	}
}

func (h *handlersImpl) staffPreorderLateOrder(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.LateOrder(r.Context(), callerFrom(r.Context()), r.PathValue("id"), b, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffPreorderSettings(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Settings(r.Context(), callerFrom(r.Context()))
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdatePreorderSettings(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.UpdateSettings(r.Context(), callerFrom(r.Context()), r.Header.Get("If-Match"), b)
	setVersionETag(w, v)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderAreas(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Areas(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAddPreorderArea(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.AddArea(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffTogglePreorderAreas(w http.ResponseWriter, r *http.Request) {
	b, err := staffBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.ToggleAreas(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeletePreorderArea(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.DeleteArea(r.Context(), callerFrom(r.Context()), r.PathValue("key"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderPlaces(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Places(r.Context(), callerFrom(r.Context()), r.URL.Query())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCustomerSearch(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Customers(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"), r.URL.Query().Get("windowId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderProducts(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Preorders.Products(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffVerifyPreorderAddress(w http.ResponseWriter, r *http.Request) {
	var b app.PreorderAddressInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.VerifyAddress(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPreorderUpload(w http.ResponseWriter, r *http.Request) {
	var b app.PreorderUploadInput
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Preorders.RequestUpload(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

// setVersionETag answers the optimistic-lock version of a window or the settings as a strong ETag ("<version>");
// the console sends it back as If-Match.
func setVersionETag(w http.ResponseWriter, v any) {
	m, ok := v.(map[string]any)
	if !ok {
		return
	}
	switch n := m["version"].(type) {
	case float64:
		w.Header().Set("ETag", `"`+strconv.FormatInt(int64(n), 10)+`"`)
	case string:
		if n != "" {
			w.Header().Set("ETag", `"`+n+`"`)
		}
	}
}
