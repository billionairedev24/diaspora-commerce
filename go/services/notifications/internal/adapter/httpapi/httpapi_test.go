package httpapi_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/webhooksig"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

type fakeLog struct {
	app.NotificationLog
	filter domain.ListFilter
}

func (f *fakeLog) List(_ context.Context, fl domain.ListFilter) ([]domain.Notification, error) {
	f.filter = fl
	return []domain.Notification{{ID: "n1", Channel: domain.ChannelSMS, Provider: "twilio", Status: domain.StatusDelivered, TemplateKey: "order.confirmed", TemplateVersion: 2}}, nil
}

type fakeCompliance struct{ from, body string }

func (f *fakeCompliance) HandleInbound(_ context.Context, from, body string) (string, error) {
	f.from, f.body = from, body
	if strings.EqualFold(body, "STOP") {
		return "You're unsubscribed & won't get more texts.", nil
	}
	return "", nil
}

type fakeReceipts struct{ got []domain.DeliveryEvent }

func (f *fakeReceipts) Apply(_ context.Context, evs []domain.DeliveryEvent) (int, error) {
	f.got = append(f.got, evs...)
	return len(evs), nil
}

// actorMW authenticates every request as the given actor (stands in for auth.Middleware).
func actorMW(a auth.Actor) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			next.ServeHTTP(w, r.WithContext(auth.WithActor(r.Context(), a)))
		})
	}
}

func mux(d httpapi.Deps, a auth.Actor) *http.ServeMux {
	d.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	m := http.NewServeMux()
	httpapi.New(d).Register(m, actorMW(a))
	return m
}

func TestLogRequiresReadPermission(t *testing.T) {
	log := &fakeLog{}
	staff := auth.Actor{Subject: "s1", Type: "STAFF", Permissions: []string{httpapi.PermRead}}
	rec := httptest.NewRecorder()
	mux(httpapi.Deps{Log: log}, staff).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "GET", "/v1/notifications?recipient=%2B15551234567&channel=sms&limit=10", nil))
	if rec.Code != http.StatusOK || log.filter.Recipient != "+15551234567" || log.filter.Limit != 10 || log.filter.Channel != domain.ChannelSMS {
		t.Fatalf("%d %+v %s", rec.Code, log.filter, rec.Body)
	}
	var body struct {
		Items []map[string]any `json:"items"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	if body.Items[0]["provider"] != "twilio" || body.Items[0]["templateVersion"].(float64) != 2 {
		t.Fatal(body)
	}

	rec = httptest.NewRecorder()
	mux(httpapi.Deps{Log: log}, auth.Actor{Subject: "c1", Type: "CUSTOMER"}).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "GET", "/v1/notifications", nil))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("customer must not read the log: %d", rec.Code)
	}
}

func TestSubmitNeedsIdempotencyKey(t *testing.T) {
	rec := httptest.NewRecorder()
	svc := auth.Actor{Subject: "orders", Type: "SERVICE", Permissions: []string{httpapi.PermSend}}
	mux(httpapi.Deps{}, svc).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "POST", "/v1/notifications", strings.NewReader(`{"templateKey":"x"}`)))
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "idempotency-key-required") {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
}

func TestPreferencesOnlyForSelfOrManager(t *testing.T) {
	rec := httptest.NewRecorder()
	mux(httpapi.Deps{}, auth.Actor{Subject: "user-1"}).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "GET", "/v1/preferences/user-2", nil))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("%d", rec.Code)
	}
}

func TestTwilioInboundStopReturnsTwiML(t *testing.T) {
	const token = "tw-secret"
	comp := &fakeCompliance{}
	m := mux(httpapi.Deps{Compliance: comp, Twilio: webhooksig.NewTwilio(token, "https://api.at.com/notifications")}, auth.Actor{})
	form := url.Values{"From": {"+15551234567"}, "To": {"+18885550100"}, "Body": {"STOP"}}
	req := httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/twilio/inbound", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("X-Twilio-Signature", webhooksig.TwilioSignature(token, "https://api.at.com/notifications/v1/webhooks/twilio/inbound", form))
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || comp.from != "+15551234567" {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	want := `<Response><Message>You&#39;re unsubscribed &amp; won&#39;t get more texts.</Message></Response>`
	if !strings.Contains(rec.Body.String(), want) || !strings.HasPrefix(rec.Header().Get("Content-Type"), "text/xml") {
		t.Fatalf("twiml: %s", rec.Body)
	}

	bad := httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/twilio/inbound", strings.NewReader(form.Encode()))
	bad.Header.Set("X-Twilio-Signature", "forged")
	rec = httptest.NewRecorder()
	m.ServeHTTP(rec, bad)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("forged webhook: %d", rec.Code)
	}
}

func TestTwilioStatusCallback(t *testing.T) {
	rc := &fakeReceipts{}
	m := mux(httpapi.Deps{Receipts: rc, Twilio: webhooksig.NewAllowAll()}, auth.Actor{})
	for _, st := range []string{"sending", "delivered"} {
		form := url.Values{"MessageSid": {"SM1"}, "MessageStatus": {st}}
		rec := httptest.NewRecorder()
		m.ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/twilio/status", strings.NewReader(form.Encode())))
		if rec.Code != http.StatusNoContent {
			t.Fatal(rec.Code)
		}
	}
	if len(rc.got) != 1 || rc.got[0].Status != domain.StatusDelivered || rc.got[0].ProviderMessageID != "SM1" {
		t.Fatalf("%+v", rc.got)
	}
}

func TestWebhookDisabledWithoutSecret(t *testing.T) {
	rec := httptest.NewRecorder()
	mux(httpapi.Deps{}, auth.Actor{}).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "POST", "/v1/webhooks/resend", strings.NewReader(`{}`)))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatal(rec.Code)
	}
}

func TestResendEvents(t *testing.T) {
	evs, err := httpapi.ResendEvents([]byte(`{"type":"email.bounced","created_at":"2026-10-10T10:00:00Z",
		"data":{"email_id":"e1","to":["bad@example.com"],"bounce":{"type":"Permanent","message":"mailbox does not exist"}}}`))
	if err != nil || len(evs) != 1 || evs[0].Status != domain.StatusBounced || evs[0].Address != "bad@example.com" || !evs[0].Suppresses() {
		t.Fatalf("%+v %v", evs, err)
	}
	evs, _ = httpapi.ResendEvents([]byte(`{"type":"email.bounced","data":{"email_id":"e1","bounce":{"type":"Transient"}}}`))
	if len(evs) != 0 {
		t.Fatal("soft bounce must not suppress")
	}
	evs, _ = httpapi.ResendEvents([]byte(`{"type":"email.complained","data":{"email_id":"e2","to":["x@y.z"]}}`))
	if evs[0].Status != domain.StatusComplained {
		t.Fatal(evs)
	}
}

func TestSendGridEvents(t *testing.T) {
	evs, err := httpapi.SendGridEvents([]byte(`[
		{"email":"a@b.c","timestamp":1760000000,"event":"delivered","sg_message_id":"abc.filterdrecv-1","notification_id":"n1"},
		{"email":"a@b.c","timestamp":1760000000,"event":"open","sg_message_id":"abc.x"},
		{"email":"bad@b.c","timestamp":1760000000,"event":"bounce","type":"bounce","sg_message_id":"def.x"},
		{"email":"blk@b.c","timestamp":1760000000,"event":"bounce","type":"blocked","sg_message_id":"ghi.x"},
		{"email":"spam@b.c","timestamp":1760000000,"event":"spamreport","sg_message_id":"jkl.x"}]`))
	if err != nil || len(evs) != 4 {
		t.Fatalf("%+v %v", evs, err)
	}
	if evs[0].ProviderMessageID != "abc" || evs[0].NotificationID != "n1" || evs[0].Status != domain.StatusDelivered {
		t.Fatalf("%+v", evs[0])
	}
	if evs[1].Status != domain.StatusBounced || evs[2].Status != domain.StatusFailed || evs[2].Suppresses() || evs[3].Status != domain.StatusComplained {
		t.Fatalf("%+v", evs)
	}
}

type fakeTemplates struct {
	app.Templates
	stored map[string][]domain.Template
}

func (f fakeTemplates) List(_ context.Context, key string) ([]domain.Template, error) { return f.stored[key], nil }

func TestTemplateSample(t *testing.T) {
	samples, err := app.NewTemplateSamples(context.Background(), app.Links{Storefront: "https://afrotransact.com"})
	if err != nil {
		t.Fatal(err)
	}
	tpls := fakeTemplates{stored: map[string][]domain.Template{"brand.new": {{Key: "brand.new", Locale: "en", Channel: domain.ChannelEmail}}}}
	reader := auth.Actor{Subject: "s1", Type: "STAFF", Permissions: []string{httpapi.PermRead}}
	get := func(a auth.Actor, key string) (*httptest.ResponseRecorder, app.TemplateSample) {
		rec := httptest.NewRecorder()
		mux(httpapi.Deps{Templates: tpls, Samples: samples}, a).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "GET", "/v1/templates/"+key+"/sample", nil))
		var s app.TemplateSample
		_ = json.Unmarshal(rec.Body.Bytes(), &s)
		return rec, s
	}
	rec, s := get(reader, "order.confirmed")
	if rec.Code != http.StatusOK || s.Source != app.SampleFromEvent || s.Data["total"] != "$48.00" || s.EventTypes[0] != "orders.order.placed" || s.Area != app.AreaOrders {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	if rec, s := get(reader, "brand.new"); rec.Code != http.StatusOK || s.Source != app.SampleNone || len(s.Data) != 0 {
		t.Fatalf("stored key without sample: %d %s", rec.Code, rec.Body)
	}
	if rec, _ := get(reader, "no.such"); rec.Code != http.StatusNotFound {
		t.Fatalf("unknown key: %d", rec.Code)
	}
	if rec, _ := get(auth.Actor{Subject: "c1", Type: "CUSTOMER"}, "order.confirmed"); rec.Code != http.StatusForbidden {
		t.Fatalf("needs notifications:read: %d", rec.Code)
	}
}
