package twilio_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/twilio"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestSendRequestShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/2010-04-01/Accounts/AC123/Messages.json" {
			t.Errorf("path %s", r.URL.Path)
		}
		if u, p, ok := r.BasicAuth(); !ok || u != "AC123" || p != "secret" {
			t.Error("basic auth")
		}
		if r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
			t.Error("content type")
		}
		_ = r.ParseForm()
		if r.PostForm.Get("To") != "+15551234567" || r.PostForm.Get("Body") != "hello" ||
			r.PostForm.Get("MessagingServiceSid") != "MG1" || r.PostForm.Get("From") != "" ||
			r.PostForm.Get("StatusCallback") != "https://api.at.com/notifications/v1/webhooks/twilio/status" {
			t.Errorf("form %v", r.PostForm)
		}
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"sid":"SM42","status":"queued"}`))
	}))
	defer srv.Close()
	s := twilio.New(twilio.Config{AccountSID: "AC123", AuthToken: "secret", MessagingServiceSID: "MG1",
		StatusCallbackURL: "https://api.at.com/notifications/v1/webhooks/twilio/status", BaseURL: srv.URL}, srv.Client())
	rec, err := s.Send(context.Background(), domain.SmsMessage{To: "+15551234567", Body: "hello"})
	if err != nil || rec.ProviderMessageID != "SM42" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrors(t *testing.T) {
	cases := []struct {
		status    int
		body      string
		retryable bool
	}{
		{400, `{"code":21211,"message":"invalid To"}`, false},
		{400, `{"code":21610,"message":"unsubscribed recipient"}`, false},
		{429, `{"code":20429,"message":"too many requests"}`, true},
		{503, ``, true},
		{401, `{"code":20003,"message":"auth"}`, true},
	}
	for _, tc := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		_, err := twilio.New(twilio.Config{AccountSID: "AC", From: "+1", BaseURL: srv.URL}, srv.Client()).Send(context.Background(), domain.SmsMessage{})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != tc.retryable {
			t.Errorf("%d %s: %v", tc.status, tc.body, err)
		}
	}
}

func TestMapStatus(t *testing.T) {
	for in, want := range map[string]domain.Status{"sent": domain.StatusSent, "delivered": domain.StatusDelivered,
		"undelivered": domain.StatusFailed, "failed": domain.StatusFailed} {
		if got, ok := twilio.MapStatus(in); !ok || got != want {
			t.Errorf("%s → %s", in, got)
		}
	}
	if _, ok := twilio.MapStatus("queued"); ok {
		t.Error("queued is intermediate")
	}
}
