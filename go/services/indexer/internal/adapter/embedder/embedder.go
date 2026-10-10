// Package embedder implements port.Embedder against ai-gateway's POST /v1/embeddings (purpose search-document),
// called with the indexer-service client's client-credentials token (scope ai:use). Vectors are cached by text, so the
// status events of one product (created, approved, paused …) embed its text once.
package embedder

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// Config configures the ai-gateway client.
type Config struct {
	BaseURL      string // http://ai-gateway:8080
	TokenURL     string // <issuer>/oauth2/token; empty = no token (local profile, ai-gateway security off)
	ClientID     string // indexer-service
	ClientSecret string
	Scopes       []string // ai:use
	// CacheSize bounds the text → vector cache (0 = 10 000 entries).
	CacheSize int
}

type embedderImpl struct {
	base   string
	client *http.Client

	mu      sync.Mutex
	cache   map[[32]byte][]float32
	maxSize int
}

var _ port.Embedder = (*embedderImpl)(nil)

// New returns the embedder; httpClient's timeout bounds each call.
func New(cfg Config, httpClient *http.Client) port.Embedder {
	client := httpClient
	if cfg.TokenURL != "" && cfg.ClientSecret != "" {
		cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
			Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
		client = cc.Client(context.WithValue(context.Background(), oauth2.HTTPClient, httpClient))
		client.Timeout = httpClient.Timeout
	}
	size := cfg.CacheSize
	if size <= 0 {
		size = 10_000
	}
	return &embedderImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: client, cache: map[[32]byte][]float32{},
		maxSize: size}
}

func (e *embedderImpl) Embed(ctx context.Context, text string) ([]float32, error) {
	key := sha256.Sum256([]byte(text))
	e.mu.Lock()
	if v, ok := e.cache[key]; ok {
		e.mu.Unlock()
		return v, nil
	}
	e.mu.Unlock()
	body, err := json.Marshal(map[string]string{"input": text, "purpose": "search-document"})
	if err != nil {
		return nil, fmt.Errorf("encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.base+"/v1/embeddings", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := e.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ai-gateway: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return nil, fmt.Errorf("ai-gateway embeddings: status %d: %s", res.StatusCode, strings.TrimSpace(string(msg)))
	}
	var out struct {
		Embedding []float32 `json:"embedding"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&out); err != nil {
		return nil, fmt.Errorf("ai-gateway decode: %w", err)
	}
	if len(out.Embedding) != searchindex.EmbeddingDimensions {
		return nil, fmt.Errorf("ai-gateway: embedding has %d dimensions, the index expects %d", len(out.Embedding),
			searchindex.EmbeddingDimensions)
	}
	e.mu.Lock()
	if len(e.cache) >= e.maxSize {
		e.cache = map[[32]byte][]float32{} // simple bound: start over rather than grow without limit
	}
	e.cache[key] = out.Embedding
	e.mu.Unlock()
	return out.Embedding, nil
}
