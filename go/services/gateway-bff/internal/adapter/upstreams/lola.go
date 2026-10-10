package upstreams

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type lolaImpl struct {
	c       httpclient.Client
	base    string
	stream  *http.Client
	maxWait time.Duration
}

var _ port.LolaClient = (*lolaImpl)(nil)

// LolaOptions configure the ai-gateway client. The stream bypasses the buffered httpclient (it must relay events as
// they arrive); HeaderTimeout bounds the wait for the first byte, MaxDuration the whole turn.
type LolaOptions struct {
	BaseURL       string
	HeaderTimeout time.Duration
	MaxDuration   time.Duration
	Transport     http.RoundTripper
}

// NewLola returns the ai-gateway client: POST /v1/lola/chat (SSE, streamed) and GET /v1/lola/sessions/{id}/messages.
func NewLola(c httpclient.Client, o LolaOptions) port.LolaClient {
	if o.Transport == nil {
		o.Transport = otelhttp.NewTransport(http.DefaultTransport)
	}
	if o.HeaderTimeout <= 0 {
		o.HeaderTimeout = 15 * time.Second
	}
	if o.MaxDuration <= 0 {
		o.MaxDuration = 2 * time.Minute
	}
	if t, ok := o.Transport.(*http.Transport); ok {
		t = t.Clone()
		t.ResponseHeaderTimeout = o.HeaderTimeout
		o.Transport = t
	}
	return &lolaImpl{c: c, base: strings.TrimSuffix(o.BaseURL, "/"), stream: &http.Client{Transport: o.Transport}, maxWait: o.MaxDuration}
}

type cancelOnClose struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (c cancelOnClose) Close() error {
	defer c.cancel()
	if err := c.ReadCloser.Close(); err != nil {
		return fmt.Errorf("close lola stream: %w", err)
	}
	return nil
}

func (l *lolaImpl) Chat(ctx context.Context, bearer string, req domain.LolaChatRequest) (io.ReadCloser, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("ai-gateway: encode: %w", err)
	}
	sctx, cancel := context.WithTimeout(ctx, l.maxWait)
	hreq, err := http.NewRequestWithContext(sctx, http.MethodPost, l.base+"/v1/lola/chat", bytes.NewReader(body))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("ai-gateway: build request: %w", err)
	}
	hreq.Header.Set("Content-Type", "application/json")
	hreq.Header.Set("Accept", "text/event-stream, application/problem+json")
	hreq.Header.Set("Authorization", "Bearer "+bearer)
	res, err := l.stream.Do(hreq)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("ai-gateway: %w: %w", domain.ErrUpstreamUnavailable, err)
	}
	if res.StatusCode >= 300 {
		defer cancel()
		defer func() { _ = res.Body.Close() }()
		b, _ := io.ReadAll(io.LimitReader(res.Body, 64<<10))
		if res.StatusCode >= 500 {
			return nil, fmt.Errorf("ai-gateway: %w: status %d", domain.ErrUpstreamUnavailable, res.StatusCode)
		}
		p := &port.UpstreamProblem{Upstream: "ai-gateway", Status: res.StatusCode}
		var doc struct {
			Code   string `json:"code"`
			Detail string `json:"detail"`
		}
		if json.Unmarshal(b, &doc) == nil {
			p.Code, p.Detail = doc.Code, doc.Detail
		}
		if (res.StatusCode == http.StatusNotFound || res.StatusCode == http.StatusMethodNotAllowed) && p.Code == "" {
			return nil, fmt.Errorf("ai-gateway: %w: route not served", domain.ErrUpstreamUnavailable)
		}
		if ra := res.Header.Get("Retry-After"); ra != "" {
			_, _ = fmt.Sscan(ra, &p.RetryAfter)
		}
		return nil, p
	}
	return cancelOnClose{ReadCloser: res.Body, cancel: cancel}, nil
}

func (l *lolaImpl) History(ctx context.Context, bearer, sessionID string) ([]domain.LolaTurn, error) {
	var out []domain.LolaTurn
	_, err := call(ctx, l.c, httpclient.Request{Path: "/v1/lola/sessions/" + esc(sessionID) + "/messages", Bearer: bearer}, &out)
	return out, err
}
