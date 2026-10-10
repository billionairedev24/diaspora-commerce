package sendgrid_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/sendgrid"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

func TestSendRequestShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v3/mail/send" || r.Header.Get("Authorization") != "Bearer SG.key" {
			t.Errorf("%s %v", r.URL.Path, r.Header)
		}
		var body struct {
			Personalizations []struct {
				To         []map[string]string `json:"to"`
				CustomArgs map[string]string   `json:"custom_args"`
			} `json:"personalizations"`
			From    map[string]string   `json:"from"`
			ReplyTo map[string]string   `json:"reply_to"`
			Content []map[string]string `json:"content"`
			Headers map[string]string   `json:"headers"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		p := body.Personalizations[0]
		if p.To[0]["email"] != "ada@example.com" || p.CustomArgs["notification_id"] != "n1" {
			t.Errorf("personalization %+v", p)
		}
		if body.From["email"] != "no-reply@at.com" || body.From["name"] != "AfroTransact" || body.ReplyTo["email"] != "help@at.com" {
			t.Errorf("from %+v reply %+v", body.From, body.ReplyTo)
		}
		if len(body.Content) != 2 || body.Content[0]["type"] != "text/plain" || body.Content[1]["type"] != "text/html" {
			t.Errorf("content order %+v", body.Content)
		}
		if body.Headers["List-Unsubscribe"] == "" {
			t.Error("headers missing")
		}
		w.Header().Set("X-Message-Id", "sg-123")
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()
	rec, err := sendgrid.New(sendgrid.Config{APIKey: "SG.key", BaseURL: srv.URL}, srv.Client()).Send(context.Background(),
		domain.EmailMessage{NotificationID: "n1", From: "AfroTransact <no-reply@at.com>", ReplyTo: "help@at.com", To: "ada@example.com",
			Subject: "s", Text: "t", HTML: "<p>h</p>", Headers: map[string]string{"List-Unsubscribe": "<https://x>"}})
	if err != nil || rec.ProviderMessageID != "sg-123" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrors(t *testing.T) {
	for status, retry := range map[int]bool{400: false, 413: false, 429: true, 503: true, 401: true} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(status)
			_, _ = w.Write([]byte(`{"errors":[{"field":"personalizations","message":"bad"}]}`))
		}))
		_, err := sendgrid.New(sendgrid.Config{BaseURL: srv.URL}, srv.Client()).Send(context.Background(), domain.EmailMessage{})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != retry {
			t.Errorf("%d: %v", status, err)
		}
	}
}

func TestSendsAttachments(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Attachments []map[string]string `json:"attachments"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if len(body.Attachments) != 1 || body.Attachments[0]["filename"] != "PO-1P-323.pdf" || body.Attachments[0]["content"] != "JVBERi0=" ||
			body.Attachments[0]["type"] != "application/pdf" || body.Attachments[0]["disposition"] != "attachment" {
			t.Errorf("attachments: %+v", body.Attachments)
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()
	s := sendgrid.New(sendgrid.Config{APIKey: "SG.key", BaseURL: srv.URL}, srv.Client())
	if c, ok := s.(port.AttachmentCapable); !ok || !c.SupportsAttachments() {
		t.Fatal("sendgrid attaches files")
	}
	_, err := s.Send(context.Background(), domain.EmailMessage{From: "a@b.c", To: "s@supplier.example", Subject: "PO", Text: "t",
		Attachments: []domain.Attachment{{FileName: "PO-1P-323.pdf", ContentType: "application/pdf", Content: []byte("%PDF-")}}})
	if err != nil {
		t.Fatal(err)
	}
}
