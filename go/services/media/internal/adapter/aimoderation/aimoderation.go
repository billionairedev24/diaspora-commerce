// Package aimoderation implements port.ImageModerator against ai-gateway's POST /v1/ai/moderate-image, called with
// media's own client-credentials token (client media-service, scope ai:use). Only a small JPEG preview is sent,
// never the original file.
package aimoderation

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// Config configures the ai-gateway client.
type Config struct {
	BaseURL      string // http://ai-gateway
	TokenURL     string // <issuer>/oauth2/token; empty = no token (local profile, ai-gateway security off)
	ClientID     string // media-service
	ClientSecret string
	Scopes       []string // ai:use
}

type moderatorImpl struct {
	base   string
	client *http.Client
}

var _ port.ImageModerator = (*moderatorImpl)(nil)

// New returns the moderator. httpClient's timeout bounds each call; the token is cached until it expires.
func New(cfg Config, httpClient *http.Client) port.ImageModerator {
	client := httpClient
	if cfg.TokenURL != "" && cfg.ClientSecret != "" {
		cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
			Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
		client = cc.Client(context.WithValue(context.Background(), oauth2.HTTPClient, httpClient))
		client.Timeout = httpClient.Timeout
	}
	return &moderatorImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: client}
}

type request struct {
	Image       string `json:"image"`
	ContentType string `json:"contentType"`
	Purpose     string `json:"purpose"`
}

type response struct {
	Decision string `json:"decision"`
	Labels   []struct {
		Code string `json:"code"`
	} `json:"labels"`
	Model string `json:"model"`
}

func (m *moderatorImpl) Moderate(ctx context.Context, preview []byte, contentType string, purpose domain.Purpose) (domain.AIVerdict, error) {
	body, err := json.Marshal(request{Image: base64.StdEncoding.EncodeToString(preview), ContentType: contentType,
		Purpose: string(purpose)})
	if err != nil {
		return domain.AIVerdict{}, fmt.Errorf("encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, m.base+"/v1/ai/moderate-image", bytes.NewReader(body))
	if err != nil {
		return domain.AIVerdict{}, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	res, err := m.client.Do(req)
	if err != nil {
		return domain.AIVerdict{}, fmt.Errorf("ai-gateway: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return domain.AIVerdict{}, fmt.Errorf("ai-gateway moderate-image: status %d: %s", res.StatusCode,
			strings.TrimSpace(string(msg)))
	}
	var out response
	if err := json.NewDecoder(io.LimitReader(res.Body, 64<<10)).Decode(&out); err != nil {
		return domain.AIVerdict{}, fmt.Errorf("ai-gateway decode: %w", err)
	}
	v := domain.AIVerdict{Model: out.Model, Labels: []string{}}
	switch d := domain.AIDecision(strings.ToUpper(out.Decision)); d {
	case domain.AIAllow, domain.AIReview, domain.AIBlock:
		v.Decision = d
	default:
		return domain.AIVerdict{}, fmt.Errorf("ai-gateway: unknown decision %q", out.Decision)
	}
	for _, l := range out.Labels {
		if c := strings.ToUpper(strings.TrimSpace(l.Code)); c != "" {
			v.Labels = append(v.Labels, c)
		}
	}
	return v, nil
}
