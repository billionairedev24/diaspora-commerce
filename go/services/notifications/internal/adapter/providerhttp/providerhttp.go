// Package providerhttp is the shared HTTP plumbing for provider adapters: it executes a request, reads a bounded
// body and classifies failures as retryable (transport errors, timeouts, 408/425/429/5xx, and 401/403 because a
// provider-account problem is fixed by failing over) or permanent (other 4xx).
package providerhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

const maxBody = 1 << 20

// Result is a completed HTTP exchange.
type Result struct {
	Status int
	Header http.Header
	Body   []byte
}

// OK reports a 2xx status.
func (r Result) OK() bool { return r.Status >= 200 && r.Status < 300 }

// Do executes req and returns the result. It only returns an error for transport failures (always retryable).
func Do(client *http.Client, req *http.Request, provider string) (Result, error) {
	resp, err := client.Do(req) //nolint:gosec // G704: provider endpoints come from service config, never from callers
	if err != nil {
		return Result{}, domain.Transport(provider).Wrap(err)
	}
	defer func() { _ = resp.Body.Close() }()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if err != nil {
		return Result{}, domain.Transport(provider).Wrap(err)
	}
	return Result{Status: resp.StatusCode, Header: resp.Header, Body: body}, nil
}

// NewJSONRequest builds a POST with a JSON body.
func NewJSONRequest(ctx context.Context, url string, body any) (*http.Request, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("encode body: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	return req, nil
}

// Classify classifies a non-2xx result; code is the provider's own error code, if parsed. Callers attach the
// response as the cause: Classify(name, res, code).Wrap(res.Detail()).
func Classify(provider string, r Result, code string) domain.Failure {
	if domain.HTTPRetryable(r.Status) || r.Status == http.StatusUnauthorized || r.Status == http.StatusForbidden {
		return domain.Retry(provider, r.Status, code)
	}
	return domain.Permanent(provider, r.Status, code)
}

// Detail is the (truncated) response body as an error, for use as a failure's cause.
func (r Result) Detail() error { return errors.New(snippet(r.Body)) }

func snippet(b []byte) string {
	if len(b) > 300 {
		return string(b[:300]) + "…"
	}
	return string(b)
}
