package embedder_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/adapter/embedder"
)

func TestEmbedsDocumentsWithTheIndexerTokenAndCaches(t *testing.T) {
	var calls atomic.Int32
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if id, _, _ := r.BasicAuth(); id != "indexer-service" || r.Form.Get("scope") != "ai:use" {
			http.Error(w, "invalid_scope", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"idx","token_type":"Bearer","expires_in":300}`))
	})
	mux.HandleFunc("POST /v1/embeddings", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		if r.Header.Get("Authorization") != "Bearer idx" || body["purpose"] != "search-document" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		calls.Add(1)
		v := make([]float32, searchindex.EmbeddingDimensions)
		v[len(body["input"])%len(v)] = 1
		_ = json.NewEncoder(w).Encode(map[string]any{"embedding": v})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	e := embedder.New(embedder.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token", ClientID: "indexer-service",
		ClientSecret: "s", Scopes: []string{"ai:use"}}, &http.Client{Timeout: time.Second})
	ctx := context.Background()
	for range 3 {
		if v, err := e.Embed(ctx, "Gari Ijebu. Groceries / Pantry"); err != nil || len(v) != searchindex.EmbeddingDimensions {
			t.Fatalf("embed: %d %v", len(v), err)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("the same text must be embedded once, got %d calls", calls.Load())
	}
	if _, err := e.Embed(ctx, "Kente Stole"); err != nil || calls.Load() != 2 {
		t.Fatalf("new text: %v, calls %d", err, calls.Load())
	}

	// Without ai:use on the client the token request fails: an error, never a stale vector.
	bad := embedder.New(embedder.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token", ClientID: "indexer-service",
		ClientSecret: "s", Scopes: []string{"sellers:directory"}}, &http.Client{Timeout: time.Second})
	if _, err := bad.Embed(ctx, "Shea butter"); err == nil {
		t.Fatal("a token without ai:use must fail")
	}
}
