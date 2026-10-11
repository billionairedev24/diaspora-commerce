package httpapi

import (
	"errors"
	"io"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Staff console operations (contracts/openapi/gateway-bff-staff.yaml). They sit behind the same chain as the storefront
// and Seller Center routes: CORS, CSRF Origin check, body limit and session resolution (token relay). The use cases
// check the STAFF principal, the module level and the upstream's permission.

// StaffServices are the staff console use cases; routes are registered when Shell is set.
type StaffServices struct {
	Shell     app.StaffShellService
	Records   app.StaffRecordsService
	Dashboard app.StaffDashboardService
	Exports   app.StaffExportsService
	Support   app.StaffSupportService
	Fees      app.StaffFeesService
	// Dispatch serves the seller pickups of AfroTransact Delivery (S255); routes are registered when it is set.
	Dispatch app.StaffDispatchService
	// Notifications serves Notifications → Templates (S268); routes are registered when it is set.
	Notifications app.StaffNotificationsService
	// Preorders serves the Pre-orders module (S270–S275); routes are registered when it is set.
	Preorders app.StaffPreordersService
	// Markets serves Settings → Markets & locations as a location tree and the shared place pickers; routes are
	// registered when it is set.
	Markets app.StaffMarketsService
	// Inventory serves the Inventory hub and AfroTransact Retail (1P) workspace (S281); routes are registered when it is set.
	Inventory app.StaffInventoryService
	// Plans serves Plans & billing (S292–S297); routes are registered when it is set.
	Plans app.StaffPlansService
	// Files serves exports kept by the BFF itself (nil when exports live in media).
	Files port.LocalExportFiles
}

func (h *handlersImpl) registerStaff(route func(pattern string, fn http.HandlerFunc)) {
	s := h.s.Staff
	if s.Shell == nil {
		return
	}
	route("GET /v1/staff/session", h.staffSession)
	h.registerStaffPlans(route)
	route("GET /v1/staff/dashboard", h.staffDashboard)
	route("GET /v1/staff/resources/{resource}", h.staffList)
	route("POST /v1/staff/resources/{resource}", h.staffCreate)
	route("GET /v1/staff/resources/{resource}/{id}", h.staffRecord)
	route("PATCH /v1/staff/resources/{resource}/{id}", h.staffUpdate)
	route("POST /v1/staff/resources/{resource}/{id}/actions/{action}", h.staffAction)
	route("POST /v1/staff/resources/{resource}/{id}/notes", h.staffNote)
	route("GET /v1/staff/exports", h.staffExports)
	route("POST /v1/staff/exports", h.staffCreateExport)
	route("GET /v1/staff/exports/{exportId}", h.staffExport)
	route("GET /v1/staff/exports/{exportId}/download", h.staffDownload)
	route("GET /v1/staff/export-files/{exportId}", h.staffExportFile)
	route("GET /v1/staff/support/customers", h.staffCustomers)
	route("GET /v1/staff/support/customers/{customerId}", h.staffCustomer)
	route("POST /v1/staff/support/refunds", h.staffRefund)
	route("POST /v1/staff/support/notifications/{notificationId}/resend", h.staffResend)
	route("POST /v1/staff/support/shipments/{shipmentId}/cancel", h.staffCancelShipment)
	if s.Fees != nil {
		route("GET /v1/staff/fees", h.staffFees)
		route("GET /v1/staff/fees/orgs", h.staffFeeOrgs)
		route("POST /v1/staff/fees/schedules", h.staffCreateFeeSchedule)
		route("POST /v1/staff/fees/schedules/{scheduleId}/cancel", h.staffCancelFeeSchedule)
		route("POST /v1/staff/fees/delivery-quote", h.staffDeliveryQuote)
	}
	if s.Dispatch != nil {
		route("GET /v1/staff/dispatch/pickups", h.staffPickups)
		route("GET /v1/staff/dispatch/pickups/{shipmentId}/driver-suggestions", h.staffPickupSuggestions)
		route("POST /v1/staff/dispatch/pickups/{shipmentId}/assign", h.staffAssignPickup)
		route("GET /v1/staff/dispatch/pickups/{shipmentId}/assignments", h.staffPickupAssignments)
		route("GET /v1/staff/ship-from-locations/{locationId}", h.staffShipFromLocation)
	}
	if s.Notifications != nil {
		route("GET /v1/staff/notifications/templates", h.staffTemplates)
		route("GET /v1/staff/notifications/templates/{key}", h.staffTemplate)
		route("POST /v1/staff/notifications/templates/{key}/preview", h.staffTemplatePreview)
		route("POST /v1/staff/notifications/templates/{key}/versions", h.staffTemplateVersion)
		route("POST /v1/staff/notifications/templates/{key}/test-send", h.staffTemplateTestSend)
	}
	if s.Preorders != nil {
		h.registerStaffPreorders(route)
	}
	if s.Inventory != nil {
		h.registerStaffInventory(route)
	}
	if s.Markets != nil {
		h.registerStaffMarkets(route)
	}
}

func (h *handlersImpl) staffSession(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Shell.Session(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDashboard(w http.ResponseWriter, r *http.Request) {
	days := 30
	if s := r.URL.Query().Get("rangeDays"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"rangeDays": "Choose 7, 30 or 90 days"}))
			return
		}
		days = n
	}
	v, err := h.s.Staff.Dashboard.Dashboard(r.Context(), callerFrom(r.Context()), days)
	h.reply(w, r, http.StatusOK, v, err)
}

// staffResource validates the {resource} path segment (unknown tables are 404).
func staffResource(r *http.Request) (string, error) {
	name := r.PathValue("resource")
	if !slices.Contains(domain.ResourceKeys, name) {
		return "", domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such table.")
	}
	return name, nil
}

func queryInt(r *http.Request, key string, def, lo, hi int) (int, error) {
	s := r.URL.Query().Get(key)
	if s == "" {
		return def, nil
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < lo || n > hi {
		return 0, domain.ValidationProblem(map[string]string{key: "Out of range"})
	}
	return n, nil
}

func (h *handlersImpl) staffList(w http.ResponseWriter, r *http.Request) {
	name, err := staffResource(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	page, err := queryInt(r, "page", 0, 0, 100_000)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	size, err := queryInt(r, "size", 25, 1, 100)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	q := r.URL.Query()
	if len(q.Get("q")) > domain.MaxSearchLength || len(q.Get("status")) > 60 || len(q.Get("cursor")) > 200 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"q": "Too long"}))
		return
	}
	v, err := h.s.Staff.Records.List(r.Context(), callerFrom(r.Context()), name,
		app.StaffQuery{Q: strings.TrimSpace(q.Get("q")), Status: q.Get("status"), Cursor: q.Get("cursor"), Sort: q["sort"], Page: page, Size: size})
	h.reply(w, r, http.StatusOK, v, err)
}

type recordValues struct {
	Values  map[string]any `json:"values"`
	Version *string        `json:"version"`
}

func (h *handlersImpl) staffCreate(w http.ResponseWriter, r *http.Request) {
	name, err := staffResource(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	var b recordValues
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Records.Create(r.Context(), callerFrom(r.Context()), name, b.Values, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffRecord(w http.ResponseWriter, r *http.Request) {
	name, err := staffResource(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Records.Get(r.Context(), callerFrom(r.Context()), name, r.PathValue("id"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffUpdate(w http.ResponseWriter, r *http.Request) {
	name, err := staffResource(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	var b recordValues
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	version := ""
	if b.Version != nil {
		version = *b.Version
	}
	v, err := h.s.Staff.Records.Update(r.Context(), callerFrom(r.Context()), name, r.PathValue("id"), b.Values, version)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAction(w http.ResponseWriter, r *http.Request) {
	name, err := staffResource(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	var b recordValues
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Records.Act(r.Context(), callerFrom(r.Context()), name, r.PathValue("id"), r.PathValue("action"), b.Values, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffNote(w http.ResponseWriter, r *http.Request) {
	name, err := staffResource(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	var b struct {
		Text string `json:"text"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Records.AddNote(r.Context(), callerFrom(r.Context()), name, r.PathValue("id"), b.Text)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- exports

func (h *handlersImpl) staffExports(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Exports.List(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) staffCreateExport(w http.ResponseWriter, r *http.Request) {
	var b app.ExportRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, ready, err := h.s.Staff.Exports.Create(r.Context(), callerFrom(r.Context()), b, r.Header.Get("Idempotency-Key"))
	status := http.StatusAccepted
	if ready {
		status = http.StatusCreated
	}
	h.reply(w, r, status, v, err)
}

func (h *handlersImpl) staffExport(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Exports.Get(r.Context(), callerFrom(r.Context()), r.PathValue("exportId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDownload(w http.ResponseWriter, r *http.Request) {
	u, err := h.s.Staff.Exports.Download(r.Context(), callerFrom(r.Context()), r.PathValue("exportId"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	h.finish(w, callerFrom(r.Context()))
	w.Header().Set("Cache-Control", "no-store")
	// The location is a link signed by the BFF's export store or media-service, never request input.
	w.Header().Set("Location", u)
	w.WriteHeader(http.StatusFound)
}

func (h *handlersImpl) staffExportFile(w http.ResponseWriter, r *http.Request) {
	h.serveExportFile(w, r, h.s.Staff.Files)
}

// serveExportFile serves an export file behind a signed, expiring link (no session needed).
func (h *handlersImpl) serveExportFile(w http.ResponseWriter, r *http.Request, files port.LocalExportFiles) {
	if files == nil {
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "Exports are served by the media store."))
		return
	}
	q := r.URL.Query()
	expires, err := strconv.ParseInt(q.Get("expires"), 10, 64)
	if err != nil {
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "This download link is not valid."))
		return
	}
	f, contentType, err := files.Open(r.Context(), r.PathValue("exportId"), expires, q.Get("sig"))
	switch {
	case errors.Is(err, exportfiles.ErrExpired):
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusGone, domain.CodeExportExpired, "Link expired", "Download links last 24 hours. Export the table again."))
		return
	case errors.Is(err, exportfiles.ErrBadSignature):
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "This download link is not valid."))
		return
	case err != nil:
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That export is no longer available."))
		return
	}
	defer func() { _ = f.Close() }()
	name := "export"
	if n, ok := f.(interface{ FileName() string }); ok {
		name = n.FileName()
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Disposition", `attachment; filename="`+strings.ReplaceAll(name, `"`, "")+`"`)
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, f)
}

// ---------------------------------------------------------------- support

func (h *handlersImpl) staffCustomers(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Support.Search(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"))
	h.reply(w, r, http.StatusOK, map[string]any{"items": v}, err)
}

func (h *handlersImpl) staffCustomer(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Support.Customer(r.Context(), callerFrom(r.Context()), r.PathValue("customerId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffRefund(w http.ResponseWriter, r *http.Request) {
	var b app.SupportRefund
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Support.Refund(r.Context(), callerFrom(r.Context()), b, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffResend(w http.ResponseWriter, r *http.Request) {
	var b struct {
		CustomerID string `json:"customerId"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Support.Resend(r.Context(), callerFrom(r.Context()), r.PathValue("notificationId"), b.CustomerID, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCancelShipment(w http.ResponseWriter, r *http.Request) {
	var b app.SupportCancel
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Support.CancelShipment(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"), b, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- fees & commissions

func (h *handlersImpl) staffFees(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Fees.Overview(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffFeeOrgs(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Fees.SearchOrgs(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffCreateFeeSchedule(w http.ResponseWriter, r *http.Request) {
	var b domain.FeeScheduleDraft
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Fees.Create(r.Context(), callerFrom(r.Context()), b, r.Header.Get("Idempotency-Key"))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffCancelFeeSchedule(w http.ResponseWriter, r *http.Request) {
	var b domain.FeeCancelRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Fees.Cancel(r.Context(), callerFrom(r.Context()), r.PathValue("scheduleId"), b.Reason)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffDeliveryQuote(w http.ResponseWriter, r *http.Request) {
	var b domain.DeliveryQuoteRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Fees.Quote(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- dispatch: seller pickups (S255)

func (h *handlersImpl) staffPickups(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Dispatch.Pickups(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("nodeId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPickupSuggestions(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Dispatch.Suggestions(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffAssignPickup(w http.ResponseWriter, r *http.Request) {
	var b domain.PickupAssignRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Dispatch.Assign(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffPickupAssignments(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Dispatch.Assignments(r.Context(), callerFrom(r.Context()), r.PathValue("shipmentId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffShipFromLocation(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Dispatch.ShipFromLocation(r.Context(), callerFrom(r.Context()), r.PathValue("locationId"))
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- notifications: templates (S268)

func (h *handlersImpl) staffTemplates(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Notifications.Templates(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("q"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTemplate(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Staff.Notifications.Template(r.Context(), callerFrom(r.Context()), r.PathValue("key"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTemplatePreview(w http.ResponseWriter, r *http.Request) {
	var b domain.NotificationPreviewRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Notifications.Preview(r.Context(), callerFrom(r.Context()), r.PathValue("key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) staffTemplateVersion(w http.ResponseWriter, r *http.Request) {
	var b domain.NotificationVersionDraft
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Notifications.CreateVersion(r.Context(), callerFrom(r.Context()), r.PathValue("key"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) staffTemplateTestSend(w http.ResponseWriter, r *http.Request) {
	var b domain.NotificationPreviewRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Staff.Notifications.TestSend(r.Context(), callerFrom(r.Context()), r.PathValue("key"), b)
	h.reply(w, r, http.StatusOK, v, err)
}
