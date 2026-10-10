// Package carrierhttp is the HTTP plumbing shared by carrier adapters: retries with exponential backoff and
// jitter on transport errors, 429 and 5xx (Retry-After honoured, never beyond the context deadline), a cached
// OAuth client-credentials token source that refreshes on expiry or on a 401, and mapping of carrier failures to
// domain errors (retryable vs permanent). Each adapter supplies its own token exchange and error-body parser.
package carrierhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

// maxBody caps response bodies (labels are base64 images of a few hundred KB).
const maxBody = 16 << 20

// Response is a fully read carrier response.
type Response struct {
	Status int
	Header http.Header
	Body   []byte
}

// ErrorParser extracts the carrier's error code and message from an error body.
type ErrorParser func(status int, body []byte) (code, message string)

// Authorizer decorates each attempt with credentials; Invalidate drops cached credentials after a 401.
type Authorizer interface {
	Authorize(ctx context.Context, req *http.Request) error
	Invalidate()
}

// Options configure a Client.
type Options struct {
	Carrier     domain.CarrierCode
	HTTP        *http.Client
	Auth        Authorizer
	MaxRetries  int           // retries after the first attempt (default 2)
	BaseBackoff time.Duration // default 200ms
	MaxBackoff  time.Duration // default 2s
	ParseError  ErrorParser
}

// Client sends carrier requests with retries and error mapping.
type Client interface {
	// Do sends the request produced by build (called once per attempt so bodies can be re-sent).
	// 2xx responses are returned; anything else is a *domain.CarrierError.
	Do(ctx context.Context, build func(ctx context.Context) (*http.Request, error)) (Response, error)
}

type clientImpl struct {
	opts  Options
	sleep func(ctx context.Context, d time.Duration) error
}

var _ Client = (*clientImpl)(nil)

// New returns a Client.
func New(o Options) Client {
	if o.HTTP == nil {
		o.HTTP = &http.Client{Timeout: 10 * time.Second}
	}
	if o.MaxRetries < 0 {
		o.MaxRetries = 0
	} else if o.MaxRetries == 0 {
		o.MaxRetries = 2
	}
	if o.BaseBackoff <= 0 {
		o.BaseBackoff = 200 * time.Millisecond
	}
	if o.MaxBackoff <= 0 {
		o.MaxBackoff = 2 * time.Second
	}
	if o.ParseError == nil {
		o.ParseError = func(int, []byte) (string, string) { return "", "" }
	}
	return &clientImpl{opts: o, sleep: sleepCtx}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	if dl, ok := ctx.Deadline(); ok && time.Until(dl) < d {
		return context.DeadlineExceeded
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

func (c *clientImpl) Do(ctx context.Context, build func(ctx context.Context) (*http.Request, error)) (Response, error) {
	reauthed := false
	var lastErr error
	for attempt := 0; ; attempt++ {
		res, err := c.once(ctx, build)
		if err == nil && res.Status >= 200 && res.Status < 300 {
			return res, nil
		}
		if err == nil && res.Status == http.StatusUnauthorized && c.opts.Auth != nil && !reauthed {
			// Token revoked or expired early: refresh once without counting it as a retry.
			c.opts.Auth.Invalidate()
			reauthed = true
			attempt--
			continue
		}
		var wait time.Duration
		switch {
		case err != nil:
			lastErr = c.transportError(ctx, err)
			var ce *domain.CarrierError
			if errors.As(err, &ce) {
				return Response{}, err // token exchange already mapped
			}
		case res.Status == http.StatusTooManyRequests || res.Status >= 500:
			lastErr = c.statusError(res)
			wait = retryAfter(res.Header)
		default:
			return Response{}, c.statusError(res)
		}
		if attempt >= c.opts.MaxRetries || ctx.Err() != nil {
			return Response{}, lastErr
		}
		if wait == 0 {
			wait = c.backoff(attempt)
		}
		if err := c.sleep(ctx, wait); err != nil {
			return Response{}, lastErr
		}
	}
}

func (c *clientImpl) once(ctx context.Context, build func(ctx context.Context) (*http.Request, error)) (Response, error) {
	req, err := build(ctx)
	if err != nil {
		return Response{}, &domain.CarrierError{Carrier: c.opts.Carrier, Message: "build request: " + err.Error(), Kind: domain.ErrInvalid}
	}
	if c.opts.Auth != nil {
		if err := c.opts.Auth.Authorize(ctx, req); err != nil {
			return Response{}, fmt.Errorf("authorize: %w", err)
		}
	}
	res, err := c.opts.HTTP.Do(req)
	if err != nil {
		return Response{}, fmt.Errorf("%s request: %w", c.opts.Carrier, err)
	}
	defer func() { _ = res.Body.Close() }()
	body, err := io.ReadAll(io.LimitReader(res.Body, maxBody))
	if err != nil {
		return Response{}, fmt.Errorf("%s read body: %w", c.opts.Carrier, err)
	}
	return Response{Status: res.StatusCode, Header: res.Header, Body: body}, nil
}

func (c *clientImpl) backoff(attempt int) time.Duration {
	d := c.opts.BaseBackoff << attempt
	if d > c.opts.MaxBackoff || d <= 0 {
		d = c.opts.MaxBackoff
	}
	// Full jitter in [d/2, d).
	half := int64(d / 2)
	return time.Duration(half + rand.Int64N(half+1)) // #nosec G404 -- jitter, not security sensitive
}

func retryAfter(h http.Header) time.Duration {
	v := h.Get("Retry-After")
	if v == "" {
		return 0
	}
	if secs, err := strconv.Atoi(v); err == nil && secs >= 0 {
		return time.Duration(secs) * time.Second
	}
	if t, err := http.ParseTime(v); err == nil {
		if d := time.Until(t); d > 0 {
			return d
		}
	}
	return 0
}

func (c *clientImpl) transportError(ctx context.Context, err error) error {
	msg := err.Error()
	if ctx.Err() != nil {
		msg = "timed out: " + ctx.Err().Error()
	}
	return &domain.CarrierError{Carrier: c.opts.Carrier, Message: msg, Kind: domain.ErrCarrierUnavailable}
}

func (c *clientImpl) statusError(res Response) error {
	code, msg := c.opts.ParseError(res.Status, res.Body)
	if msg == "" {
		msg = http.StatusText(res.Status)
	}
	return &domain.CarrierError{Carrier: c.opts.Carrier, Status: res.Status, Code: code, Message: msg, Kind: KindFor(res.Status)}
}

// KindFor maps an HTTP status to the domain error kind.
func KindFor(status int) error {
	switch {
	case status == http.StatusTooManyRequests || status >= 500 || status == http.StatusRequestTimeout:
		return domain.ErrCarrierUnavailable
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		return domain.ErrCarrierAuth
	case status == http.StatusNotFound:
		return domain.ErrNotFound
	default:
		return domain.ErrCarrierRejected
	}
}

// JSONRequest builds a JSON request.
func JSONRequest(ctx context.Context, method, target string, body any) (*http.Request, error) {
	var rd io.Reader
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("encode body: %w", err)
		}
		rd = bytes.NewReader(buf)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, rd)
	if err != nil {
		return nil, fmt.Errorf("new request: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	return req, nil
}

// Path expands {name} placeholders in a configured endpoint path with path-escaped values.
func Path(tpl string, params map[string]string) string {
	for k, v := range params {
		tpl = strings.ReplaceAll(tpl, "{"+k+"}", url.PathEscape(v))
	}
	return tpl
}

// Decode unmarshals a response body, mapping malformed bodies to a retryable carrier error.
func Decode(carrier domain.CarrierCode, body []byte, dst any) error {
	if err := json.Unmarshal(body, dst); err != nil {
		return &domain.CarrierError{Carrier: carrier, Message: "malformed response: " + err.Error(), Kind: domain.ErrCarrierUnavailable}
	}
	return nil
}

// Rejected builds a permanent carrier error for semantic failures inside 2xx responses.
func Rejected(carrier domain.CarrierCode, code, msg string) error {
	return &domain.CarrierError{Carrier: carrier, Code: code, Message: msg, Kind: domain.ErrCarrierRejected}
}

// OneOrMany decodes a JSON value that carriers send as an object when there is one element and as an array
// otherwise (UPS does this for RatedShipment, PackageResults, Candidate…).
func OneOrMany[T any](raw json.RawMessage) ([]T, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	if raw[0] == '[' {
		var many []T
		if err := json.Unmarshal(raw, &many); err != nil {
			return nil, fmt.Errorf("decode list: %w", err)
		}
		return many, nil
	}
	var one T
	if err := json.Unmarshal(raw, &one); err != nil {
		return nil, fmt.Errorf("decode object: %w", err)
	}
	return []T{one}, nil
}
