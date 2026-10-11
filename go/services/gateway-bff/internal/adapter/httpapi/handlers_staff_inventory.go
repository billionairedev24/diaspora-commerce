package httpapi

import (
	"io"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

// Inventory hub and AfroTransact Retail (1P) (S281; gateway-bff-staff.yaml tags Inventory hub and Retail (1P)):
// /v1/staff/inventory/** mirrors inventory-service and /v1/staff/retail/** mirrors retail-service through the route
// table of app.StaffInventoryService; flow and trace compose the pipeline to the storefront.

func (h *handlersImpl) registerStaffInventory(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/staff/inventory/flow", h.staffInventoryFlow)
	route("GET /v1/staff/inventory/trace", h.staffInventoryTrace)
	for _, area := range []string{"inventory", "retail"} {
		for _, m := range []string{http.MethodGet, http.MethodPost, http.MethodPut} {
			route(m+" /v1/staff/"+area+"/{rest...}", h.staffOps(area))
		}
	}
}

func (h *handlersImpl) staffInventoryFlow(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Inventory.Flow(r.Context(), callerFrom(r.Context()))
	if err == nil {
		w.Header().Set("Cache-Control", "private, max-age=15")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffInventoryTrace(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.Staff.Inventory.Trace(r.Context(), callerFrom(r.Context()), q.Get("atin"), q.Get("sku"))
	h.reply(w, r, http.StatusOK, v, err)
}

// streamed are the documents relayed as files (inbound labels, a PO's PDF and EDI 850).
func streamed(path string) bool {
	return strings.HasSuffix(path, "/labels") || strings.HasSuffix(path, "/pdf") || strings.HasSuffix(path, "/edi850")
}

func (h *handlersImpl) staffOps(area string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		req := app.OpsRequest{Area: area, Method: r.Method, Path: "/" + r.PathValue("rest"), Query: r.URL.Query(),
			IfMatch: r.Header.Get("If-Match"), Idem: r.Header.Get("Idempotency-Key")}
		if r.Method == http.MethodGet && streamed(req.Path) {
			h.staffOpsStream(w, r, req)
			return
		}
		if r.Method != http.MethodGet {
			b := map[string]any{}
			if err := decode(r, &b, true); err != nil {
				h.reply(w, r, 0, nil, err)
				return
			}
			req.Body = b
		}
		v, err := h.s.Staff.Inventory.Relay(r.Context(), callerFrom(r.Context()), req)
		status := http.StatusOK
		if err == nil && r.Method == http.MethodPost && !strings.Contains(strings.TrimPrefix(req.Path, "/"), "/") {
			status = http.StatusCreated // POST on a collection creates
		}
		if err == nil {
			setVersionETag(w, v)
		}
		h.reply(w, r, status, v, err)
	}
}

func (h *handlersImpl) staffOpsStream(w http.ResponseWriter, r *http.Request, req app.OpsRequest) {
	f, err := h.s.Staff.Inventory.Stream(r.Context(), callerFrom(r.Context()), req)
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
		h.log.WarnContext(r.Context(), "inventory document relay interrupted", "error", err)
	}
}

// sellerFba mirrors Seller Center's Send to AfroTransact routes (/v1/seller/fba/**) to inventory-service.
func (h *handlersImpl) sellerFba(w http.ResponseWriter, r *http.Request) {
	req := app.OpsRequest{Method: r.Method, Path: "/" + r.PathValue("rest"), Query: r.URL.Query(),
		IfMatch: r.Header.Get("If-Match"), Idem: r.Header.Get("Idempotency-Key")}
	if r.Method == http.MethodGet && streamed(req.Path) {
		f, err := h.s.SellerFba.Stream(r.Context(), callerFrom(r.Context()), req)
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
			h.log.WarnContext(r.Context(), "FBA label relay interrupted", "error", err)
		}
		return
	}
	if r.Method != http.MethodGet {
		b := map[string]any{}
		if err := decode(r, &b, true); err != nil {
			h.reply(w, r, 0, nil, err)
			return
		}
		req.Body = b
	}
	v, err := h.s.SellerFba.Relay(r.Context(), callerFrom(r.Context()), req)
	status := http.StatusOK
	if err == nil && r.Method == http.MethodPost && req.Path == "/inbound" {
		status = http.StatusCreated
	}
	if err == nil {
		setVersionETag(w, v)
	}
	h.reply(w, r, status, v, err)
}
