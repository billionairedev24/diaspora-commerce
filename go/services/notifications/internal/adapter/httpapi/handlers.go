// Package httpapi is the inbound HTTP adapter for notifications (contract: contracts/openapi/notifications.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/webhooksig"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// Permissions checked by this service.
const (
	PermRead           = "notifications:read"
	PermSend           = "notifications:send"
	PermManage         = "notifications:manage"
	PermTemplatesWrite = "notifications:templates:write"
)

// Deps are the use cases and webhook verifiers the handlers need. A nil verifier disables its webhook.
type Deps struct {
	Notifier     app.Notifier
	Log          app.NotificationLog
	Templates    app.Templates
	Preferences  app.Preferences
	Receipts     app.Receipts
	Compliance   app.SmsCompliance
	Unsubscriber app.Unsubscriber
	StockAlerts  app.StockAlerts     // nil disables /v1/stock-alerts
	Samples      app.TemplateSamples // sample data per template key (nil: every key has an empty sample)
	Twilio       webhooksig.Verifier
	Resend       webhooksig.Verifier
	SendGrid     webhooksig.Verifier
	Logger       *slog.Logger
}

// Handlers exposes the notification use cases over HTTP.
type Handlers interface {
	// Register mounts the routes. authn authenticates API routes; webhooks and unsubscribe are public and
	// authenticated by signatures instead.
	Register(mux *http.ServeMux, authn func(http.Handler) http.Handler)
}

type handlersImpl struct{ d Deps }

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(d Deps) Handlers { return &handlersImpl{d: d} }

func (h *handlersImpl) Register(mux *http.ServeMux, authn func(http.Handler) http.Handler) {
	secured := func(pattern, perm string, fn http.HandlerFunc) {
		mux.Handle(pattern, authn(auth.Require(perm, fn)))
	}
	secured("POST /v1/notifications", PermSend, h.submit)
	secured("GET /v1/notifications", PermRead, h.list)
	secured("GET /v1/notifications/{id}", PermRead, h.get)
	secured("GET /v1/dead-letters", PermRead, h.deadLetters)
	secured("POST /v1/dead-letters/{id}/replay", PermManage, h.replay)
	secured("GET /v1/templates", PermRead, h.listTemplates)
	secured("POST /v1/templates", PermTemplatesWrite, h.createTemplate)
	secured("GET /v1/templates/{key}/sample", PermRead, h.templateSample)
	secured("POST /v1/templates/{key}/preview", PermRead, h.preview)
	secured("POST /v1/templates/{key}/test-send", PermTemplatesWrite, h.testSend)
	secured("POST /v1/consents/sms", PermSend, h.recordConsent)
	secured("GET /v1/consents/sms/{phone}", PermRead, h.getConsent)
	secured("GET /v1/suppressions", PermRead, h.suppressions)
	secured("DELETE /v1/suppressions/{address}", PermManage, h.unsuppress)
	mux.Handle("GET /v1/preferences/{recipientId}", authn(http.HandlerFunc(h.getPreferences)))
	mux.Handle("PUT /v1/preferences/{recipientId}", authn(http.HandlerFunc(h.setPreferences)))
	if h.d.StockAlerts != nil {
		mux.Handle("POST /v1/stock-alerts", authn(http.HandlerFunc(h.subscribeStockAlert)))
		mux.Handle("GET /v1/stock-alerts", authn(http.HandlerFunc(h.listStockAlerts)))
		mux.Handle("DELETE /v1/stock-alerts/{atin}", authn(http.HandlerFunc(h.unsubscribeStockAlert)))
	}

	mux.HandleFunc("POST /v1/webhooks/resend", h.resendWebhook)
	mux.HandleFunc("POST /v1/webhooks/sendgrid", h.sendGridWebhook)
	mux.HandleFunc("POST /v1/webhooks/twilio/status", h.twilioStatus)
	mux.HandleFunc("POST /v1/webhooks/twilio/inbound", h.twilioInbound)
	mux.HandleFunc("GET /v1/unsubscribe", h.unsubscribePage)
	mux.HandleFunc("POST /v1/unsubscribe", h.unsubscribe)
}

// ---- notifications ----

func (h *handlersImpl) submit(w http.ResponseWriter, r *http.Request) {
	var req domain.Request
	if !decode(w, r, &req) {
		return
	}
	req.IdempotencyKey = r.Header.Get("Idempotency-Key")
	if req.IdempotencyKey == "" {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.idempotency-key-required", "Idempotency-Key header is required"))
		return
	}
	out, err := h.d.Notifier.Submit(r.Context(), req)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"notifications": out})
}

func (h *handlersImpl) list(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := domain.ListFilter{Recipient: q.Get("recipient"), Channel: domain.Channel(q.Get("channel")), Status: domain.Status(q.Get("status"))}
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-parameter", "limit is not a number"))
			return
		}
		f.Limit = n
	}
	if v := q.Get("before"); v != "" {
		t, err := time.Parse(time.RFC3339Nano, v)
		if err != nil {
			problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-parameter", "before must be RFC 3339"))
			return
		}
		f.Before = t
	}
	out, err := h.d.Log.List(r.Context(), f)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (h *handlersImpl) get(w http.ResponseWriter, r *http.Request) {
	n, err := h.d.Log.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, n)
}

func (h *handlersImpl) deadLetters(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	out, err := h.d.Log.DeadLetters(r.Context(), limit)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (h *handlersImpl) replay(w http.ResponseWriter, r *http.Request) {
	n, err := h.d.Log.Replay(r.Context(), r.PathValue("id"))
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusAccepted, n)
}

// ---- templates ----

func (h *handlersImpl) listTemplates(w http.ResponseWriter, r *http.Request) {
	out, err := h.d.Templates.List(r.Context(), r.URL.Query().Get("key"))
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (h *handlersImpl) createTemplate(w http.ResponseWriter, r *http.Request) {
	var t domain.Template
	if !decode(w, r, &t) {
		return
	}
	a, _ := auth.FromContext(r.Context())
	out, err := h.d.Templates.Create(r.Context(), t, a.Subject)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, out)
}

// templateSample answers the sample data a key renders with in previews (the data its event carries in production).
// A stored key without a sample (e.g. a new key created by staff) gets an empty sample; an unknown key is 404.
func (h *handlersImpl) templateSample(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	var s app.TemplateSample
	ok := false
	if h.d.Samples != nil {
		s, ok = h.d.Samples.Sample(key)
	}
	if !ok {
		stored, err := h.d.Templates.List(r.Context(), key)
		if err != nil {
			h.writeErr(w, r, err)
			return
		}
		if len(stored) == 0 {
			h.writeErr(w, r, domain.ErrTemplateNotFound)
			return
		}
		s = app.TemplateSample{Key: key, Area: app.TemplateArea(key), Source: app.SampleNone, EventTypes: []string{}, Data: map[string]any{}}
	}
	writeJSON(w, http.StatusOK, s)
}

func (h *handlersImpl) preview(w http.ResponseWriter, r *http.Request) {
	var req app.PreviewRequest
	if !decode(w, r, &req) {
		return
	}
	req.Key = r.PathValue("key")
	out, err := h.d.Templates.Preview(r.Context(), req)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (h *handlersImpl) testSend(w http.ResponseWriter, r *http.Request) {
	var req app.PreviewRequest
	if !decode(w, r, &req) {
		return
	}
	req.Key = r.PathValue("key")
	out, err := h.d.Templates.TestSend(r.Context(), req)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

// ---- preferences, consent, suppression ----

func canManageRecipient(r *http.Request, recipientID string) bool {
	a, ok := auth.FromContext(r.Context())
	return ok && (a.Subject == recipientID || a.Can(PermManage))
}

func (h *handlersImpl) getPreferences(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("recipientId")
	if !canManageRecipient(r, id) {
		problem.Write(w, r, problem.New(http.StatusForbidden, "auth.missing-permission", "Only the recipient or staff with "+PermManage+" may read preferences"))
		return
	}
	out, err := h.d.Preferences.Get(r.Context(), id)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"recipientId": id, "preferences": out})
}

func (h *handlersImpl) setPreferences(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("recipientId")
	if !canManageRecipient(r, id) {
		problem.Write(w, r, problem.New(http.StatusForbidden, "auth.missing-permission", "Only the recipient or staff with "+PermManage+" may change preferences"))
		return
	}
	var body struct {
		Preferences []domain.Preference `json:"preferences"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := h.d.Preferences.Set(r.Context(), id, body.Preferences); err != nil {
		h.writeErr(w, r, err)
		return
	}
	h.getPreferences(w, r)
}

func (h *handlersImpl) recordConsent(w http.ResponseWriter, r *http.Request) {
	var c domain.SmsConsent
	if !decode(w, r, &c) {
		return
	}
	if err := h.d.Preferences.RecordSmsConsent(r.Context(), c); err != nil {
		h.writeErr(w, r, err)
		return
	}
	out, err := h.d.Preferences.SmsConsent(r.Context(), c.Phone)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, out)
}

func (h *handlersImpl) getConsent(w http.ResponseWriter, r *http.Request) {
	out, err := h.d.Preferences.SmsConsent(r.Context(), r.PathValue("phone"))
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (h *handlersImpl) suppressions(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	out, err := h.d.Preferences.Suppressions(r.Context(), domain.NormalizeEmail(r.URL.Query().Get("address")), limit)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (h *handlersImpl) unsuppress(w http.ResponseWriter, r *http.Request) {
	if err := h.d.Preferences.Unsuppress(r.Context(), r.PathValue("address")); err != nil {
		h.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- helpers ----

const maxBody = 1 << 20

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	if err := dec.Decode(v); err != nil && !errors.Is(err, io.EOF) {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-body", "Request body is not valid JSON: "+err.Error()))
		return false
	}
	return true
}

func (h *handlersImpl) writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalidRequest), errors.Is(err, app.ErrRender):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-request", err.Error()))
	case errors.Is(err, domain.ErrTemplateNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "notifications.template-not-found", "No template matches"))
	case errors.Is(err, domain.ErrNotFound):
		problem.Write(w, r, problem.New(http.StatusNotFound, "notifications.not-found", "Not found"))
	case errors.Is(err, app.ErrInvalidToken):
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-token", "Unsubscribe link is invalid or expired"))
	case errors.Is(err, app.ErrNoOwnContact):
		problem.Write(w, r, problem.New(http.StatusUnprocessableEntity, "notifications.no-own-contact", "Test-send goes only to your own email or phone, and none is on your account"))
	case errors.Is(err, domain.ErrNoProvider), isProviderErr(err):
		problem.Write(w, r, problem.New(http.StatusBadGateway, "notifications.provider-failed", err.Error()))
	default:
		h.d.Logger.ErrorContext(r.Context(), "request failed", "path", r.URL.Path, "error", err)
		problem.Write(w, r, err)
	}
}

func isProviderErr(err error) bool {
	var pe *domain.ProviderError
	return errors.As(err, &pe)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
