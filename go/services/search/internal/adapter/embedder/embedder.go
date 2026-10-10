// Package embedder implements port.Embedder against the Spring AI gateway (OpenRouter-backed embeddings).
package embedder

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

type gatewayEmbedder struct {
	baseURL string
	client  *http.Client
}

var _ port.Embedder = (*gatewayEmbedder)(nil)

// NewGatewayEmbedder calls POST {baseURL}/v1/embeddings on the ai-gateway service.
func NewGatewayEmbedder(baseURL string, client *http.Client) port.Embedder {
	return &gatewayEmbedder{baseURL: baseURL, client: client}
}

func (g *gatewayEmbedder) Embed(ctx context.Context, text string) ([]float32, error) {
	body, err := json.Marshal(map[string]string{"input": text, "purpose": "search-query"})
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
		return nil, fmt.Errorf("ai-gateway: status %d", resp.StatusCode)
	}
	var out struct {
		Embedding []float32 `json:"embedding"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode: %w", err)
	}
	return out.Embedding, nil
}
