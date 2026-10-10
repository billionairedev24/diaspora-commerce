package whatsapp_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/whatsapp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestTemplateMessageShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v21.0/1055/messages" || r.Header.Get("Authorization") != "Bearer EAAG" {
			t.Errorf("%s %v", r.URL.Path, r.Header)
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		tpl := b["template"].(map[string]any)
		params := tpl["components"].([]any)[0].(map[string]any)["parameters"].([]any)
		if b["messaging_product"] != "whatsapp" || b["to"] != "15551234567" || b["type"] != "template" ||
			tpl["name"] != "order_update" || tpl["language"].(map[string]any)["code"] != "en" ||
			params[1].(map[string]any)["text"] != "4821" {
			t.Errorf("body %v", b)
		}
		_, _ = w.Write([]byte(`{"messaging_product":"whatsapp","messages":[{"id":"wamid.X"}]}`))
	}))
	defer srv.Close()
	s := whatsapp.New(whatsapp.Config{PhoneNumberID: "1055", Token: "EAAG", BaseURL: srv.URL}, srv.Client())
	rec, err := s.Send(context.Background(), domain.ChatMessage{To: "+15551234567", TemplateName: "order_update", Language: "en", Params: []string{"AT-1", "4821"}})
	if err != nil || rec.ProviderMessageID != "wamid.X" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestTextAndErrors(t *testing.T) {
	cases := []struct {
		status    int
		body      string
		retryable bool
	}{
		{400, `{"error":{"message":"rate","code":130429}}`, true},
		{400, `{"error":{"message":"bad param","code":100}}`, false},
		{400, `{"error":{"message":"re-engagement","code":131047}}`, false},
		{500, `{"error":{"message":"oops","code":1}}`, true},
	}
	for _, tc := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var b map[string]any
			_ = json.NewDecoder(r.Body).Decode(&b)
			if b["type"] != "text" || b["text"].(map[string]any)["body"] != "hello" {
				t.Errorf("text body %v", b)
			}
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		_, err := whatsapp.New(whatsapp.Config{BaseURL: srv.URL}, srv.Client()).Send(context.Background(), domain.ChatMessage{To: "+1", Body: "hello"})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != tc.retryable {
			t.Errorf("%s: %v", tc.body, err)
		}
	}
}
