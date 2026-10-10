package fcm_test

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/oauth2"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/fcm"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestSendRequestShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/projects/afrotransact-dev/messages:send" || r.Header.Get("Authorization") != "Bearer ya29.token" {
			t.Errorf("%s %v", r.URL.Path, r.Header)
		}
		var b struct {
			Message struct {
				Token        string            `json:"token"`
				Notification map[string]string `json:"notification"`
				Data         map[string]string `json:"data"`
			} `json:"message"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.Message.Token != "device-1" || b.Message.Notification["title"] != "Out for delivery" || b.Message.Data["notificationId"] != "n1" {
			t.Errorf("%+v", b)
		}
		_, _ = w.Write([]byte(`{"name":"projects/afrotransact-dev/messages/0:123"}`))
	}))
	defer srv.Close()
	s := fcm.New(fcm.Config{ProjectID: "afrotransact-dev", BaseURL: srv.URL}, oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "ya29.token"}), srv.Client())
	if !s.Accepts(domain.PushFCM) || s.Accepts(domain.PushAPNs) {
		t.Fatal("accepts")
	}
	rec, err := s.Send(context.Background(), domain.PushMessage{Token: domain.PushToken{Token: "device-1", Platform: domain.PushFCM},
		Title: "Out for delivery", Body: "b", Data: map[string]string{"notificationId": "n1"}})
	if err != nil || rec.ProviderMessageID != "projects/afrotransact-dev/messages/0:123" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrors(t *testing.T) {
	cases := []struct {
		status    int
		body      string
		retryable bool
	}{
		{404, `{"error":{"code":404,"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`, false},
		{400, `{"error":{"code":400,"status":"INVALID_ARGUMENT"}}`, false},
		{429, `{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}`, true},
		{503, `{"error":{"code":503,"status":"UNAVAILABLE"}}`, true},
	}
	for _, tc := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		_, err := fcm.New(fcm.Config{ProjectID: "p", BaseURL: srv.URL}, oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "x"}), srv.Client()).
			Send(context.Background(), domain.PushMessage{})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != tc.retryable {
			t.Errorf("%d: %v", tc.status, err)
		}
	}
}

func TestTokenSourceFromServiceAccountJSON(t *testing.T) {
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	sa, _ := json.Marshal(map[string]string{
		"type": "service_account", "project_id": "afrotransact-prod", "private_key_id": "kid",
		"private_key":  string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})),
		"client_email": "fcm@afrotransact-prod.iam.gserviceaccount.com", "token_uri": "https://oauth2.googleapis.com/token",
	})
	ts, project, err := fcm.TokenSourceFromJSON(context.Background(), sa)
	if err != nil || ts == nil || project != "afrotransact-prod" {
		t.Fatalf("%v %s", err, project)
	}
	if _, _, err := fcm.TokenSourceFromJSON(context.Background(), []byte(`{"type":"authorized_user"}`)); err == nil {
		t.Fatal("non service-account credentials accepted")
	}
}
