// Package httpclient is the resilient HTTP client every upstream adapter uses: per-upstream timeout, retries for
// idempotent GETs only, a consecutive-failure circuit breaker, Prometheus metrics (latency, outcomes, breaker state)
// and OpenTelemetry propagation (otelhttp transport). Unavailability (network errors, timeouts, 5xx, open breaker,
// or a 404/405 without a problem code — a route the upstream does not serve yet) is reported as
// domain.ErrUpstreamUnavailable; other 4xx answers become *port.UpstreamProblem.
package httpclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

const maxBody = 4 << 20

// Request is one upstream call.
type Request struct {
	Method string
	Path   string
	Query  url.Values
	Header http.Header
	// JSON is marshalled as the request body (Content-Type: application/json).
	JSON any
	// Form is sent as application/x-www-form-urlencoded (OAuth token endpoint).
	Form url.Values
	// Bearer is relayed as Authorization: Bearer (never logged).
	Bearer string
	// BasicUser/BasicPassword authenticate a confidential OAuth client.
	BasicUser, BasicPassword string
	// RetryUnsafe allows retries for a non-GET that is idempotent by design (e.g. carries an Idempotency-Key).
	RetryUnsafe bool
	// Slow uses the upstream's SlowTimeout instead of Timeout: calls that wait for a downstream saga or chain (POST
	// /v1/checkout waits up to orders' checkout-response-timeout; a late pre-order add waits for delivery-runs →
	// orders → payments → Stripe). Giving up earlier would report a failure for work that still completes, and
	// the client would retry it.
	Slow bool
}

// Response is a successful (2xx/3xx) upstream answer.
type Response struct {
	Status int
	Header http.Header
	Body   []byte
}

// Decode unmarshals the JSON body into v.
func (r *Response) Decode(v any) error {
	if len(r.Body) == 0 {
		return nil
	}
	if err := json.Unmarshal(r.Body, v); err != nil {
		return fmt.Errorf("decode upstream body: %w", err)
	}
	return nil
}

// StreamResponse is a successful upstream answer whose body is relayed without buffering (CSV exports). The caller
// must close Body.
type StreamResponse struct {
	Status int
	Header http.Header
	Body   io.ReadCloser
}

// Client calls one upstream.
type Client interface {
	Name() string
	Do(ctx context.Context, req Request) (*Response, error)
	// Stream performs one attempt (no retries) and hands back the open body of a 2xx answer; errors follow Do.
	// accept is the Accept header (e.g. "text/csv"); timeout bounds the whole transfer.
	Stream(ctx context.Context, req Request, accept string, timeout time.Duration) (*StreamResponse, error)
	BreakerState() BreakerState
}

// Options tune one upstream client.
type Options struct {
	Name    string
	BaseURL string
	Timeout time.Duration
	// SlowTimeout bounds Request.Slow calls (defaults to Timeout).
	SlowTimeout     time.Duration
	Retries         int
	BreakerFailures int
	BreakerCooldown time.Duration
	Transport       http.RoundTripper // defaults to otelhttp over http.DefaultTransport
	Metrics         *Metrics
	RetryBackoff    time.Duration
}

type clientImpl struct {
	o       Options
	http    *http.Client
	breaker Breaker
}

var _ Client = (*clientImpl)(nil)

// Metrics are the per-upstream Prometheus collectors (shared by every client).
type Metrics struct {
	duration *prometheus.HistogramVec
	requests *prometheus.CounterVec
	breaker  *prometheus.GaugeVec
}

// NewMetrics registers the upstream collectors on reg.
func NewMetrics(reg prometheus.Registerer) *Metrics {
	m := &Metrics{
		duration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "bff_upstream_request_duration_seconds", Help: "Upstream call latency by upstream, method and outcome.",
			Buckets: []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5},
		}, []string{"upstream", "method", "outcome"}),
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "bff_upstream_requests_total", Help: "Upstream calls by upstream, method and outcome (ok, client_error, unavailable, breaker_open).",
		}, []string{"upstream", "method", "outcome"}),
		breaker: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "bff_upstream_breaker_state", Help: "Circuit breaker state per upstream: 0 closed, 1 half-open, 2 open.",
		}, []string{"upstream"}),
	}
	if reg != nil {
		reg.MustRegister(m.duration, m.requests, m.breaker)
	}
	return m
}

// New returns a Client for one upstream.
func New(o Options) Client {
	if o.Transport == nil {
		o.Transport = otelhttp.NewTransport(http.DefaultTransport)
	}
	if o.Metrics == nil {
		o.Metrics = NewMetrics(nil)
	}
	if o.Timeout <= 0 {
		o.Timeout = 3 * time.Second
	}
	if o.SlowTimeout < o.Timeout {
		o.SlowTimeout = o.Timeout
	}
	if o.RetryBackoff <= 0 {
		o.RetryBackoff = 50 * time.Millisecond
	}
	o.BaseURL = strings.TrimSuffix(o.BaseURL, "/")
	gauge := o.Metrics.breaker.WithLabelValues(o.Name)
	gauge.Set(float64(StateClosed))
	c := &clientImpl{o: o, http: &http.Client{Transport: o.Transport, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}}
	c.breaker = NewBreaker(o.BreakerFailures, o.BreakerCooldown, nil, func(s BreakerState) { gauge.Set(float64(s)) })
	return c
}

func (c *clientImpl) Name() string { return c.o.Name }

func (c *clientImpl) BreakerState() BreakerState { return c.breaker.State() }

func unavailable(name string, cause error) error {
	return fmt.Errorf("%s: %w: %w", name, domain.ErrUpstreamUnavailable, cause)
}

func (c *clientImpl) Do(ctx context.Context, req Request) (*Response, error) {
	method := req.Method
	if method == "" {
		method = http.MethodGet
	}
	attempts := 1
	if method == http.MethodGet || method == http.MethodHead || req.RetryUnsafe {
		attempts += max(0, c.o.Retries)
	}
	var lastErr error
	for i := range attempts {
		if i > 0 {
			select {
			case <-ctx.Done():
				return nil, unavailable(c.o.Name, ctx.Err())
			case <-time.After(c.o.RetryBackoff * time.Duration(1<<(i-1))):
			}
		}
		if !c.breaker.Allow() {
			c.o.Metrics.requests.WithLabelValues(c.o.Name, method, "breaker_open").Inc()
			return nil, unavailable(c.o.Name, errors.New("circuit breaker open"))
		}
		res, err, retryable := c.once(ctx, method, req)
		if err == nil {
			return res, nil
		}
		lastErr = err
		if !retryable || ctx.Err() != nil {
			break
		}
	}
	return nil, lastErr
}

// once performs one attempt. retryable reports whether another attempt could help.
func (c *clientImpl) once(ctx context.Context, method string, req Request) (*Response, error, bool) {
	start := time.Now()
	outcome := "ok"
	defer func() {
		c.o.Metrics.duration.WithLabelValues(c.o.Name, method, outcome).Observe(time.Since(start).Seconds())
		c.o.Metrics.requests.WithLabelValues(c.o.Name, method, outcome).Inc()
	}()
	timeout := c.o.Timeout
	if req.Slow {
		timeout = c.o.SlowTimeout
	}
	actx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	hreq, err := c.build(actx, method, req)
	if err != nil {
		outcome = "client_error"
		return nil, err, false
	}
	res, err := c.http.Do(hreq)
	if err != nil {
		outcome = "unavailable"
		c.breaker.Failure()
		return nil, unavailable(c.o.Name, err), true
	}
	defer func() { _ = res.Body.Close() }()
	body, err := io.ReadAll(io.LimitReader(res.Body, maxBody))
	if err != nil {
		outcome = "unavailable"
		c.breaker.Failure()
		return nil, unavailable(c.o.Name, err), true
	}
	switch {
	case res.StatusCode >= 500:
		outcome = "unavailable"
		c.breaker.Failure()
		return nil, unavailable(c.o.Name, fmt.Errorf("status %d", res.StatusCode)), true
	case res.StatusCode >= 400:
		p := decodeProblem(c.o.Name, res, body)
		if (res.StatusCode == http.StatusNotFound || res.StatusCode == http.StatusMethodNotAllowed) && p.Code == "" {
			// The route itself is missing: the upstream (or this version of it) does not serve it yet.
			outcome = "unavailable"
			c.breaker.Success() // the service answered; do not trip the breaker for a missing route
			return nil, unavailable(c.o.Name, fmt.Errorf("route %s %s not served (%d)", method, req.Path, res.StatusCode)), false
		}
		outcome = "client_error"
		c.breaker.Success()
		return nil, p, false
	default:
		c.breaker.Success()
		return &Response{Status: res.StatusCode, Header: res.Header, Body: body}, nil, false
	}
}

// cancelOnClose cancels the transfer's context when the relayed body is closed.
type cancelOnClose struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (b cancelOnClose) Close() error {
	defer b.cancel()
	if err := b.ReadCloser.Close(); err != nil {
		return fmt.Errorf("close upstream body: %w", err)
	}
	return nil
}

func (c *clientImpl) Stream(ctx context.Context, req Request, accept string, timeout time.Duration) (*StreamResponse, error) {
	method := req.Method
	if method == "" {
		method = http.MethodGet
	}
	if !c.breaker.Allow() {
		c.o.Metrics.requests.WithLabelValues(c.o.Name, method, "breaker_open").Inc()
		return nil, unavailable(c.o.Name, errors.New("circuit breaker open"))
	}
	start := time.Now()
	outcome := "ok"
	defer func() {
		c.o.Metrics.duration.WithLabelValues(c.o.Name, method, outcome).Observe(time.Since(start).Seconds())
		c.o.Metrics.requests.WithLabelValues(c.o.Name, method, outcome).Inc()
	}()
	if timeout <= 0 {
		timeout = c.o.Timeout
	}
	sctx, cancel := context.WithTimeout(ctx, timeout)
	hreq, err := c.build(sctx, method, req)
	if err != nil {
		cancel()
		outcome = "client_error"
		return nil, err
	}
	if accept != "" {
		hreq.Header.Set("Accept", accept+", application/problem+json")
	}
	res, err := c.http.Do(hreq)
	if err != nil {
		cancel()
		outcome = "unavailable"
		c.breaker.Failure()
		return nil, unavailable(c.o.Name, err)
	}
	if res.StatusCode < 400 {
		c.breaker.Success()
		return &StreamResponse{Status: res.StatusCode, Header: res.Header, Body: cancelOnClose{ReadCloser: res.Body, cancel: cancel}}, nil
	}
	defer cancel()
	defer func() { _ = res.Body.Close() }()
	body, _ := io.ReadAll(io.LimitReader(res.Body, maxBody))
	if res.StatusCode >= 500 {
		outcome = "unavailable"
		c.breaker.Failure()
		return nil, unavailable(c.o.Name, fmt.Errorf("status %d", res.StatusCode))
	}
	c.breaker.Success()
	p := decodeProblem(c.o.Name, res, body)
	if (res.StatusCode == http.StatusNotFound || res.StatusCode == http.StatusMethodNotAllowed) && p.Code == "" {
		outcome = "unavailable"
		return nil, unavailable(c.o.Name, fmt.Errorf("route %s %s not served (%d)", method, req.Path, res.StatusCode))
	}
	outcome = "client_error"
	return nil, p
}

func (c *clientImpl) build(ctx context.Context, method string, req Request) (*http.Request, error) {
	u := c.o.BaseURL + req.Path
	if len(req.Query) > 0 {
		u += "?" + req.Query.Encode()
	}
	var body io.Reader
	contentType := ""
	switch {
	case req.Form != nil:
		body = strings.NewReader(req.Form.Encode())
		contentType = "application/x-www-form-urlencoded"
	case req.JSON != nil:
		b, err := json.Marshal(req.JSON)
		if err != nil {
			return nil, fmt.Errorf("%s: encode body: %w", c.o.Name, err)
		}
		body = bytes.NewReader(b)
		contentType = "application/json"
	}
	hreq, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return nil, fmt.Errorf("%s: build request: %w", c.o.Name, err)
	}
	for k, vs := range req.Header {
		for _, v := range vs {
			hreq.Header.Add(k, v)
		}
	}
	hreq.Header.Set("Accept", "application/json, application/problem+json")
	if contentType != "" {
		hreq.Header.Set("Content-Type", contentType)
	}
	if req.Bearer != "" {
		hreq.Header.Set("Authorization", "Bearer "+req.Bearer)
		if port.IsShopper(ctx) {
			// Storefront and customer app: the person shops as themselves, whatever organisations they belong to.
			hreq.Header.Set(auth.ActingAsHeader, auth.ActingAsCustomer)
		}
	}
	if req.BasicUser != "" {
		hreq.SetBasicAuth(url.QueryEscape(req.BasicUser), url.QueryEscape(req.BasicPassword))
	}
	return hreq, nil
}

func decodeProblem(name string, res *http.Response, body []byte) *port.UpstreamProblem {
	p := &port.UpstreamProblem{Upstream: name, Status: res.StatusCode}
	if ra, err := strconv.Atoi(res.Header.Get("Retry-After")); err == nil {
		p.RetryAfter = ra
	}
	var doc map[string]any
	if json.Unmarshal(body, &doc) != nil {
		return p
	}
	str := func(k string) string {
		s, _ := doc[k].(string)
		return s
	}
	p.Code, p.Detail = str("code"), str("detail")
	if p.Code == "" && str("error") != "" && strings.Contains(res.Header.Get("Content-Type"), "json") && str("path") == "" {
		// OAuth 2 error responses: {"error":"invalid_grant","error_description":"…"}
		p.Code, p.Detail = str("error"), str("error_description")
	}
	for _, key := range []string{"errors", "fieldErrors"} {
		if m, ok := doc[key].(map[string]any); ok {
			p.Fields = map[string]string{}
			for k, v := range m {
				if s, ok := v.(string); ok {
					p.Fields[k] = s
				}
			}
		}
	}
	if p.RetryAfter == 0 {
		if n, ok := doc["retryAfterSeconds"].(float64); ok {
			p.RetryAfter = int(n)
		}
	}
	return p
}
