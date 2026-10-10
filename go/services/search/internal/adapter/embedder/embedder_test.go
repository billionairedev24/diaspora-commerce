package embedder_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/adapter/embedder"
)

// fakeGateway plays identity's token endpoint and ai-gateway's /v1/embeddings (never a real model).
func fakeGateway(t *testing.T, dims int) (*httptest.Server, *int) {
	t.Helper()
	tokens := 0
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if id, _, _ := r.BasicAuth(); id != "search-service" || r.Form.Get("scope") != "ai:use" {
			http.Error(w, "bad client", http.StatusUnauthorized)
			return
		}
		tokens++
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"svc","token_type":"Bearer","expires_in":300}`))
	})
	mux.HandleFunc("POST /v1/embeddings", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		if r.Header.Get("Authorization") != "Bearer svc" || body["purpose"] != "search-query" || body["input"] == "" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"embedding": make([]float32, dims), "model": "fake", "dimensions": dims})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, &tokens
}

func TestEmbedUsesTheServiceTokenAndChecksDimensions(t *testing.T) {
	srv, tokens := fakeGateway(t, searchindex.EmbeddingDimensions)
	e := embedder.New(embedder.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token", ClientID: "search-service",
		ClientSecret: "s", Scopes: []string{"ai:use"}}, &http.Client{Timeout: time.Second})
	for range 3 {
		v, err := e.Embed(context.Background(), "pepper soup for a cold night")
		if err != nil || len(v) != searchindex.EmbeddingDimensions {
			t.Fatalf("embed: %d %v", len(v), err)
		}
	}
	if *tokens != 1 {
		t.Fatalf("the token must be cached, fetched %d times", *tokens)
	}

	wrong, _ := fakeGateway(t, 3)
	e = embedder.New(embedder.Config{BaseURL: wrong.URL, TokenURL: wrong.URL + "/oauth2/token", ClientID: "search-service",
		ClientSecret: "s", Scopes: []string{"ai:use"}}, &http.Client{Timeout: time.Second})
	if _, err := e.Embed(context.Background(), "x y z"); err == nil || !strings.Contains(err.Error(), "dimensions") {
		t.Fatalf("a vector of the wrong size must be refused, got %v", err)
	}
	unauth := embedder.NewGatewayEmbedder(srv.URL, &http.Client{Timeout: time.Second})
	if _, err := unauth.Embed(context.Background(), "x y z"); err == nil {
		t.Fatal("a call without a token is refused by the gateway")
	}
}
