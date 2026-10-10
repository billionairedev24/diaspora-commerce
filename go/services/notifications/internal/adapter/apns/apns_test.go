package apns_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/apns"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func p8(t *testing.T) (*ecdsa.PrivateKey, []byte) {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	return k, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

func TestSendOverHTTP2WithES256Token(t *testing.T) {
	key, pemBytes := p8(t)
	parsed, err := apns.ParseKey(pemBytes)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 2 {
			t.Errorf("APNs requires HTTP/2, got %s", r.Proto)
		}
		if r.URL.Path != "/3/device/abc123" || r.Header.Get("apns-topic") != "com.afrotransact.customer" ||
			r.Header.Get("apns-push-type") != "alert" || r.Header.Get("apns-id") != "6f1c1b0e-6a52-4c1e-9c8e-6f4f5e2b9a10" {
			t.Errorf("%s %v", r.URL.Path, r.Header)
		}
		raw := strings.TrimPrefix(r.Header.Get("Authorization"), "bearer ")
		tok, err := jwt.Parse(raw, func(*jwt.Token) (any, error) { return &key.PublicKey, nil }, jwt.WithValidMethods([]string{"ES256"}))
		if err != nil || tok.Header["kid"] != "KEY123" || tok.Claims.(jwt.MapClaims)["iss"] != "TEAM456" {
			t.Errorf("provider token: %v %v", err, tok)
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		alert := body["aps"].(map[string]any)["alert"].(map[string]any)
		if alert["title"] != "Order confirmed" || body["notificationId"] != "6f1c1b0e-6a52-4c1e-9c8e-6f4f5e2b9a10" {
			t.Errorf("payload %v", body)
		}
		w.Header().Set("apns-id", r.Header.Get("apns-id"))
	}))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	defer srv.Close()

	s := apns.New(apns.Config{KeyID: "KEY123", TeamID: "TEAM456", BundleID: "com.afrotransact.customer", BaseURL: srv.URL}, parsed, srv.Client())
	if !s.Accepts(domain.PushAPNs) || s.Accepts(domain.PushFCM) {
		t.Fatal("accepts")
	}
	id := "6f1c1b0e-6a52-4c1e-9c8e-6f4f5e2b9a10"
	rec, err := s.Send(context.Background(), domain.PushMessage{NotificationID: id, Token: domain.PushToken{Token: "abc123", Platform: domain.PushAPNs},
		Title: "Order confirmed", Body: "b", Data: map[string]string{"notificationId": id}})
	if err != nil || rec.ProviderMessageID != id {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrors(t *testing.T) {
	_, pemBytes := p8(t)
	key, _ := apns.ParseKey(pemBytes)
	cases := []struct {
		status    int
		reason    string
		retryable bool
	}{
		{410, "Unregistered", false},
		{400, "BadDeviceToken", false},
		{403, "ExpiredProviderToken", true},
		{429, "TooManyRequests", true},
		{503, "ServiceUnavailable", true},
		{400, "PayloadTooLarge", false},
	}
	for _, tc := range cases {
		srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(`{"reason":"` + tc.reason + `"}`))
		}))
		_, err := apns.New(apns.Config{BaseURL: srv.URL}, key, srv.Client()).Send(context.Background(), domain.PushMessage{Token: domain.PushToken{Token: "t"}})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != tc.retryable || pe.Code != tc.reason {
			t.Errorf("%s: %v", tc.reason, err)
		}
	}
	if _, err := apns.ParseKey([]byte("nope")); err == nil {
		t.Fatal("bad key accepted")
	}
}
