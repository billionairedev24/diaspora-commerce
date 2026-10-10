package vonage_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/vonage"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func server(t *testing.T, body string, check func(r *http.Request)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if check != nil {
			check(r)
		}
		_, _ = w.Write([]byte(body))
	}))
}

func TestSendRequestShape(t *testing.T) {
	srv := server(t, `{"message-count":"1","messages":[{"to":"15551234567","message-id":"0A0000","status":"0"}]}`, func(r *http.Request) {
		f := r.PostForm
		if r.URL.Path != "/sms/json" || f.Get("api_key") != "k" || f.Get("api_secret") != "s" || f.Get("to") != "15551234567" ||
			f.Get("from") != "AfroTransact" || f.Get("text") != "Olá" || f.Get("type") != "unicode" || f.Get("client-ref") != "n1" {
			t.Errorf("form %v", f)
		}
	})
	defer srv.Close()
	rec, err := vonage.New(vonage.Config{APIKey: "k", APISecret: "s", From: "AfroTransact", BaseURL: srv.URL}, srv.Client()).
		Send(context.Background(), domain.SmsMessage{NotificationID: "n1", To: "+15551234567", Body: "Olá"})
	if err != nil || rec.ProviderMessageID != "0A0000" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestStatusClassification(t *testing.T) {
	for status, retry := range map[string]bool{"1": true, "5": true, "3": false, "6": false, "15": false} {
		srv := server(t, `{"messages":[{"status":"`+status+`","error-text":"x"}]}`, nil)
		_, err := vonage.New(vonage.Config{BaseURL: srv.URL}, srv.Client()).Send(context.Background(), domain.SmsMessage{To: "+1"})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != retry {
			t.Errorf("status %s: %v", status, err)
		}
	}
}
