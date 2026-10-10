package httpapi

import (
	"encoding/json"
	"encoding/xml"
	"html/template"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/resend"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/sendgrid"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/twilio"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/webhooksig"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// readVerified reads the body and checks the signature. It writes the error response and returns false when
// the webhook is disabled or not authentic.
func (h *handlersImpl) readVerified(w http.ResponseWriter, r *http.Request, v webhooksig.Verifier) ([]byte, bool) {
	if v == nil {
		problem.Write(w, r, problem.New(http.StatusServiceUnavailable, "notifications.webhook-disabled", "Webhook is not configured"))
		return nil, false
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBody))
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-body", "Body too large or unreadable"))
		return nil, false
	}
	if err := v.Verify(r, body); err != nil {
		h.d.Logger.WarnContext(r.Context(), "webhook signature rejected", "path", r.URL.Path, "error", err)
		problem.Write(w, r, problem.New(http.StatusUnauthorized, "notifications.invalid-signature", "Signature verification failed"))
		return nil, false
	}
	return body, true
}

func (h *handlersImpl) apply(w http.ResponseWriter, r *http.Request, evs []domain.DeliveryEvent) {
	if _, err := h.d.Receipts.Apply(r.Context(), evs); err != nil {
		h.d.Logger.ErrorContext(r.Context(), "apply receipts", "error", err)
		problem.Write(w, r, problem.New(http.StatusInternalServerError, "notifications.receipt-failed", "Could not record receipts"))
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- Resend (Svix-signed) ----

type resendEvent struct {
	Type      string    `json:"type"`
	CreatedAt time.Time `json:"created_at"`
	Data      struct {
		EmailID string   `json:"email_id"`
		To      []string `json:"to"`
		Bounce  *struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"bounce"`
	} `json:"data"`
}

// ResendEvents maps a Resend webhook payload to delivery events.
func ResendEvents(body []byte) ([]domain.DeliveryEvent, error) {
	var e resendEvent
	if err := json.Unmarshal(body, &e); err != nil {
		return nil, err //nolint:wrapcheck // decoded at the edge, reported as 400
	}
	ev := domain.DeliveryEvent{Provider: resend.Name, ProviderMessageID: e.Data.EmailID, OccurredAt: e.CreatedAt}
	if len(e.Data.To) > 0 {
		ev.Address = e.Data.To[0]
	}
	switch e.Type {
	case "email.sent":
		ev.Status = domain.StatusSent
	case "email.delivered":
		ev.Status = domain.StatusDelivered
	case "email.bounced":
		if e.Data.Bounce != nil && !strings.EqualFold(e.Data.Bounce.Type, "Permanent") {
			return nil, nil // soft bounce: Resend keeps retrying
		}
		ev.Status = domain.StatusBounced
		if e.Data.Bounce != nil {
			ev.Detail = e.Data.Bounce.Message
		}
	case "email.complained":
		ev.Status = domain.StatusComplained
	case "email.failed":
		ev.Status = domain.StatusFailed
	default: // email.delivery_delayed, email.opened, email.clicked …
		return nil, nil
	}
	return []domain.DeliveryEvent{ev}, nil
}

func (h *handlersImpl) resendWebhook(w http.ResponseWriter, r *http.Request) {
	body, ok := h.readVerified(w, r, h.d.Resend)
	if !ok {
		return
	}
	evs, err := ResendEvents(body)
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-body", "Invalid Resend payload"))
		return
	}
	h.apply(w, r, evs)
}

// ---- SendGrid Event Webhook ----

type sendGridEvent struct {
	Email          string `json:"email"`
	Timestamp      int64  `json:"timestamp"`
	Event          string `json:"event"`
	SgMessageID    string `json:"sg_message_id"`
	NotificationID string `json:"notification_id"` // custom_args echo
	Reason         string `json:"reason"`
	Type           string `json:"type"` // bounce | blocked
}

// SendGridEvents maps a SendGrid Event Webhook batch to delivery events.
func SendGridEvents(body []byte) ([]domain.DeliveryEvent, error) {
	var in []sendGridEvent
	if err := json.Unmarshal(body, &in); err != nil {
		return nil, err //nolint:wrapcheck // decoded at the edge, reported as 400
	}
	out := make([]domain.DeliveryEvent, 0, len(in))
	for _, e := range in {
		msgID, _, _ := strings.Cut(e.SgMessageID, ".") // "<X-Message-Id>.filter…"
		ev := domain.DeliveryEvent{Provider: sendgrid.Name, ProviderMessageID: msgID, NotificationID: e.NotificationID,
			Address: e.Email, Detail: e.Reason, OccurredAt: time.Unix(e.Timestamp, 0).UTC()}
		switch e.Event {
		case "processed":
			ev.Status = domain.StatusSent
		case "delivered":
			ev.Status = domain.StatusDelivered
		case "bounce":
			ev.Status = domain.StatusBounced
			if e.Type == "blocked" { // reputation block, not a bad address: do not suppress
				ev.Status = domain.StatusFailed
			}
		case "dropped":
			ev.Status = domain.StatusFailed
		case "spamreport":
			ev.Status = domain.StatusComplained
		default: // deferred, open, click, unsubscribe …
			continue
		}
		out = append(out, ev)
	}
	return out, nil
}

func (h *handlersImpl) sendGridWebhook(w http.ResponseWriter, r *http.Request) {
	body, ok := h.readVerified(w, r, h.d.SendGrid)
	if !ok {
		return
	}
	evs, err := SendGridEvents(body)
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-body", "Invalid SendGrid payload"))
		return
	}
	h.apply(w, r, evs)
}

// ---- Twilio ----

func (h *handlersImpl) twilioStatus(w http.ResponseWriter, r *http.Request) {
	body, ok := h.readVerified(w, r, h.d.Twilio)
	if !ok {
		return
	}
	form, err := url.ParseQuery(string(body))
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-body", "Invalid form body"))
		return
	}
	status, known := twilio.MapStatus(form.Get("MessageStatus"))
	if !known {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	detail := ""
	if c := form.Get("ErrorCode"); c != "" {
		detail = "twilio error " + c
	}
	h.apply(w, r, []domain.DeliveryEvent{{Provider: twilio.Name, ProviderMessageID: form.Get("MessageSid"),
		Status: status, Detail: detail, OccurredAt: time.Now().UTC()}})
}

type twiml struct {
	XMLName xml.Name `xml:"Response"`
	Message string   `xml:"Message,omitempty"`
}

func (h *handlersImpl) twilioInbound(w http.ResponseWriter, r *http.Request) {
	body, ok := h.readVerified(w, r, h.d.Twilio)
	if !ok {
		return
	}
	form, err := url.ParseQuery(string(body))
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "notifications.invalid-body", "Invalid form body"))
		return
	}
	reply, err := h.d.Compliance.HandleInbound(r.Context(), form.Get("From"), form.Get("Body"))
	if err != nil {
		h.d.Logger.ErrorContext(r.Context(), "inbound sms", "error", err)
		problem.Write(w, r, problem.New(http.StatusInternalServerError, "notifications.inbound-failed", "Could not process message"))
		return
	}
	out, _ := xml.Marshal(twiml{Message: reply})
	w.Header().Set("Content-Type", "text/xml; charset=utf-8")
	_, _ = w.Write([]byte(xml.Header))
	_, _ = w.Write(out)
}

// ---- One-click unsubscribe (RFC 8058) ----

var pageTpl = template.Must(template.New("page").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AfroTransact email preferences</title>
<style>body{font-family:Figtree,Arial,sans-serif;background:#fff;color:#111;max-width:480px;margin:64px auto;padding:0 16px}
button{font:inherit;padding:10px 18px;border-radius:8px;border:1px solid #111;background:#111;color:#fff;cursor:pointer}</style>
</head><body>
{{if .Done}}<h1>You're unsubscribed</h1><p>{{.Address}} will no longer receive {{.Category}} email from AfroTransact.
Order, delivery and security emails are not affected.</p>
{{else}}<h1>Unsubscribe?</h1><p>Stop {{.Category}} email to {{.Address}}.</p>
<form method="post"><input type="hidden" name="List-Unsubscribe" value="One-Click"><button type="submit">Unsubscribe</button></form>{{end}}
</body></html>`))

type pageData struct {
	Done     bool
	Address  string
	Category string
}

// unsubscribePage shows a confirmation; GET must not unsubscribe because link scanners prefetch URLs.
func (h *handlersImpl) unsubscribePage(w http.ResponseWriter, r *http.Request) {
	c, err := h.d.Unsubscriber.Verify(r.URL.Query().Get("t"))
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = pageTpl.Execute(w, pageData{Address: c.Address, Category: string(c.Category)})
}

// unsubscribe handles the RFC 8058 one-click POST (body List-Unsubscribe=One-Click) and the page's form.
func (h *handlersImpl) unsubscribe(w http.ResponseWriter, r *http.Request) {
	c, err := h.d.Preferences.Unsubscribe(r.Context(), r.URL.Query().Get("t"))
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	if strings.Contains(r.Header.Get("Accept"), "text/html") {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = pageTpl.Execute(w, pageData{Done: true, Address: c.Address, Category: string(c.Category)})
		return
	}
	w.WriteHeader(http.StatusOK)
}
