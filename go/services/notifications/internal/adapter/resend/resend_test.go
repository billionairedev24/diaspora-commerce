package resend_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/resend"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

func TestSendRequestShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/emails" {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer re_test" || r.Header.Get("Idempotency-Key") != "idem-1" {
			t.Errorf("headers: %v", r.Header)
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["from"] != "AT <no-reply@at.com>" || body["subject"] != "Hi" || body["html"] != "<p>x</p>" ||
			body["to"].([]any)[0] != "ada@example.com" || body["headers"].(map[string]any)["List-Unsubscribe-Post"] != "List-Unsubscribe=One-Click" {
			t.Errorf("body: %v", body)
		}
		_, _ = w.Write([]byte(`{"id":"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794"}`))
	}))
	defer srv.Close()
	s := resend.New(resend.Config{APIKey: "re_test", BaseURL: srv.URL}, srv.Client())
	rec, err := s.Send(context.Background(), domain.EmailMessage{NotificationID: "n1", From: "AT <no-reply@at.com>", To: "ada@example.com",
		Subject: "Hi", HTML: "<p>x</p>", Text: "x", IdempotencyKey: "idem-1",
		Headers: map[string]string{"List-Unsubscribe-Post": "List-Unsubscribe=One-Click"}})
	if err != nil || rec.ProviderMessageID != "49a3999c-0ce1-4ea6-ab68-afcd6dc2e794" || rec.Provider != "resend" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrorClassification(t *testing.T) {
	cases := []struct {
		status    int
		body      string
		retryable bool
	}{
		{422, `{"name":"validation_error","message":"Invalid to"}`, false},
		{429, `{"name":"rate_limit_exceeded"}`, true},
		{500, `{"name":"internal_server_error"}`, true},
		{403, `{"name":"invalid_api_key"}`, true},
		{409, `{"name":"concurrent_idempotent_requests"}`, true},
		{409, `{"name":"invalid_idempotent_request"}`, false},
	}
	for _, tc := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		_, err := resend.New(resend.Config{BaseURL: srv.URL}, srv.Client()).Send(context.Background(), domain.EmailMessage{To: "a@b.c"})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != tc.retryable {
			t.Errorf("%d %s: got %v", tc.status, tc.body, err)
		}
	}
}

func TestTransportErrorIsRetryable(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close()
	_, err := resend.New(resend.Config{BaseURL: url}, http.DefaultClient).Send(context.Background(), domain.EmailMessage{})
	if !domain.IsRetryable(err) {
		t.Fatal(err)
	}
}

func TestSendsAttachments(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Attachments []struct {
				Filename    string `json:"filename"`
				Content     string `json:"content"`
				ContentType string `json:"content_type"`
			} `json:"attachments"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if len(body.Attachments) != 1 || body.Attachments[0].Filename != "PO-1P-323.pdf" ||
			body.Attachments[0].Content != "JVBERi0=" || body.Attachments[0].ContentType != "application/pdf" {
			t.Errorf("attachments: %+v", body.Attachments)
		}
		_, _ = w.Write([]byte(`{"id":"re-1"}`))
	}))
	defer srv.Close()
	s := resend.New(resend.Config{APIKey: "re_test", BaseURL: srv.URL}, srv.Client())
	if c, ok := s.(port.AttachmentCapable); !ok || !c.SupportsAttachments() {
		t.Fatal("resend attaches files")
	}
	_, err := s.Send(context.Background(), domain.EmailMessage{From: "a@b.c", To: "s@supplier.example", Subject: "PO",
		Attachments: []domain.Attachment{{FileName: "PO-1P-323.pdf", ContentType: "application/pdf", Content: []byte("%PDF-")}}})
	if err != nil {
		t.Fatal(err)
	}
}
