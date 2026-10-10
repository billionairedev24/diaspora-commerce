package aimoderation_test

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/aimoderation"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// fakeGateway plays ai-gateway: a token endpoint and POST /v1/ai/moderate-image (never a real model).
func fakeGateway(t *testing.T, decision string, status int) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		if id, secret, ok := r.BasicAuth(); !ok || id != "media-service" || secret != "s3cret" {
			http.Error(w, "bad client", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"svc-token","token_type":"Bearer","expires_in":300}`))
	})
	mux.HandleFunc("POST /v1/ai/moderate-image", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer svc-token" {
			http.Error(w, "unauthenticated", http.StatusUnauthorized)
			return
		}
		var body struct {
			Image, ContentType, Purpose string
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		img, err := base64.StdEncoding.DecodeString(body.Image)
		if err != nil || string(img) != "jpeg-preview" || body.ContentType != "image/jpeg" || body.Purpose != "product-image" {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		if status != http.StatusOK {
			http.Error(w, "upstream down", status)
			return
		}
		_, _ = w.Write([]byte(`{"decision":"` + decision + `","labels":[{"code":"watermark","description":"Shop logo"}],"model":"fake/vision"}`))
	})
	return httptest.NewServer(mux)
}

func moderator(srv *httptest.Server) func() (domain.AIVerdict, error) {
	m := aimoderation.New(aimoderation.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token",
		ClientID: "media-service", ClientSecret: "s3cret", Scopes: []string{"ai:use"}}, &http.Client{Timeout: time.Second})
	return func() (domain.AIVerdict, error) {
		return m.Moderate(context.Background(), []byte("jpeg-preview"), "image/jpeg", domain.PurposeProductImage)
	}
}

func TestModerateSendsPreviewWithServiceToken(t *testing.T) {
	srv := fakeGateway(t, "review", http.StatusOK)
	defer srv.Close()
	v, err := moderator(srv)()
	if err != nil {
		t.Fatal(err)
	}
	if v.Decision != domain.AIReview || len(v.Labels) != 1 || v.Labels[0] != "WATERMARK" || v.Model != "fake/vision" {
		t.Fatalf("verdict %+v", v)
	}
}

func TestGatewayErrorsAndUnknownDecisionsAreErrors(t *testing.T) {
	down := fakeGateway(t, "ALLOW", http.StatusServiceUnavailable)
	defer down.Close()
	if _, err := moderator(down)(); err == nil {
		t.Fatal("503 must be an error (the fail mode decides)")
	}
	odd := fakeGateway(t, "MAYBE", http.StatusOK)
	defer odd.Close()
	if _, err := moderator(odd)(); err == nil {
		t.Fatal("an unknown decision must be an error")
	}
}
