// Package embedder implements port.Embedder against ai-gateway's POST /v1/embeddings (Spring AI, OpenRouter-backed),
// called with search's own client-credentials token (client search-service, scope ai:use).
package embedder

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// Config configures the ai-gateway client.
type Config struct {
	BaseURL      string // http://ai-gateway:8080
	TokenURL     string // <issuer>/oauth2/token; empty = no token (local profile, ai-gateway security off)
	ClientID     string // search-service
	ClientSecret string
	Scopes       []string // ai:use
	// Purpose is sent with every request (search-query for shoppers' queries).
	Purpose string
}

type gatewayEmbedder struct {
	baseURL string
	purpose string
	client  *http.Client
}

var _ port.Embedder = (*gatewayEmbedder)(nil)

// New returns the embedder. The token is cached and refreshed before it expires, so the 150 ms budget of a search
// is spent on the embedding, not on the token endpoint (except the very first call).
func New(cfg Config, httpClient *http.Client) port.Embedder {
	client := httpClient
	if cfg.TokenURL != "" && cfg.ClientSecret != "" {
		cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
			Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
		client = cc.Client(context.WithValue(context.Background(), oauth2.HTTPClient, httpClient))
		client.Timeout = httpClient.Timeout
	}
	purpose := cfg.Purpose
	if purpose == "" {
		purpose = "search-query"
	}
	return &gatewayEmbedder{baseURL: strings.TrimRight(cfg.BaseURL, "/"), purpose: purpose, client: client}
}

// NewGatewayEmbedder calls POST {baseURL}/v1/embeddings without a token (local profile).
func NewGatewayEmbedder(baseURL string, client *http.Client) port.Embedder {
	return New(Config{BaseURL: baseURL}, client)
}

func (g *gatewayEmbedder) Embed(ctx context.Context, text string) ([]float32, error) {
	body, err := json.Marshal(map[string]string{"input": text, "purpose": g.purpose})
	if err != nil {
		return nil, fmt.Errorf("encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.baseURL+"/v1/embeddings", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := g.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ai-gateway: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("ai-gateway: status %d", resp.StatusCode)
	}
	var out struct {
		Embedding []float32 `json:"embedding"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode: %w", err)
	}
	if len(out.Embedding) != searchindex.EmbeddingDimensions {
		return nil, fmt.Errorf("ai-gateway: embedding has %d dimensions, the index expects %d", len(out.Embedding),
			searchindex.EmbeddingDimensions)
	}
	return out.Embedding, nil
}
