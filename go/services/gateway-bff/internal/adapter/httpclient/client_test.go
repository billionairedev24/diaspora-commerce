package httpclient_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func newClient(t *testing.T, h http.HandlerFunc, o httpclient.Options) (httpclient.Client, *prometheus.Registry) {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	reg := prometheus.NewRegistry()
	m := httpclient.NewMetrics(reg)
	o.Name, o.BaseURL, o.Metrics, o.Transport, o.RetryBackoff = "svc", srv.URL, m, http.DefaultTransport, time.Millisecond
	return httpclient.New(o), reg
}

func TestRetriesOnlyIdempotentGets(t *testing.T) {
	var calls atomic.Int32
	c, _ := newClient(t, func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(503)
	}, httpclient.Options{Retries: 2, BreakerFailures: 100})
	_, err := c.Do(context.Background(), httpclient.Request{Path: "/x"})
	if !errors.Is(err, domain.ErrUpstreamUnavailable) || calls.Load() != 3 {
		t.Fatalf("GET: err=%v calls=%d, want unavailable after 3 attempts", err, calls.Load())
	}
	calls.Store(0)
	_, err = c.Do(context.Background(), httpclient.Request{Method: "POST", Path: "/x", JSON: map[string]int{"a": 1}})
	if !errors.Is(err, domain.ErrUpstreamUnavailable) || calls.Load() != 1 {
		t.Fatalf("POST: err=%v calls=%d, want a single attempt", err, calls.Load())
	}
}

func TestProblemsAndMissingRoutes(t *testing.T) {
	c, _ := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/problem":
			w.Header().Set("Content-Type", "application/problem+json")
			w.Header().Set("Retry-After", "7")
			w.WriteHeader(409)
			_, _ = w.Write([]byte(`{"type":"t","title":"Conflict","status":409,"code":"cart.insufficient-stock","detail":"only 3 available","errors":{"quantity":"too many"}}`))
		case "/missing":
			http.NotFound(w, r) // Go mux: 404 text/plain, no problem code → route not served
		case "/oauth":
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(400)
			_, _ = w.Write([]byte(`{"error":"invalid_grant","error_description":"expired"}`))
		}
	}, httpclient.Options{BreakerFailures: 1, BreakerCooldown: time.Hour})
	_, err := c.Do(context.Background(), httpclient.Request{Path: "/problem"})
	p, ok := port.AsUpstreamProblem(err)
	if !ok || p.Status != 409 || p.Code != "cart.insufficient-stock" || p.Detail != "only 3 available" || p.Fields["quantity"] != "too many" || p.RetryAfter != 7 {
		t.Fatalf("problem = %+v (%v)", p, err)
	}
	_, err = c.Do(context.Background(), httpclient.Request{Path: "/missing"})
	if !errors.Is(err, domain.ErrUpstreamUnavailable) {
		t.Fatalf("missing route: %v", err)
	}
	if c.BreakerState() != httpclient.StateClosed {
		t.Fatal("a missing route must not open the breaker")
	}
	_, err = c.Do(context.Background(), httpclient.Request{Path: "/oauth"})
	if p, ok := port.AsUpstreamProblem(err); !ok || p.Code != "invalid_grant" {
		t.Fatalf("oauth error = %v", err)
	}
}

func TestTimeoutOpensBreakerAndFailsFast(t *testing.T) {
	var calls atomic.Int32
	c, reg := newClient(t, func(_ http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		select {
		case <-r.Context().Done():
		case <-time.After(time.Second):
		}
	}, httpclient.Options{Timeout: 20 * time.Millisecond, BreakerFailures: 2, BreakerCooldown: time.Hour})
	for range 2 {
		if _, err := c.Do(context.Background(), httpclient.Request{Path: "/slow"}); !errors.Is(err, domain.ErrUpstreamUnavailable) {
			t.Fatalf("timeout must be unavailable: %v", err)
		}
	}
	if c.BreakerState() != httpclient.StateOpen {
		t.Fatalf("breaker = %v, want open", c.BreakerState())
	}
	before := calls.Load()
	start := time.Now()
	if _, err := c.Do(context.Background(), httpclient.Request{Path: "/slow"}); !errors.Is(err, domain.ErrUpstreamUnavailable) {
		t.Fatalf("open breaker: %v", err)
	}
	if calls.Load() != before || time.Since(start) > 10*time.Millisecond {
		t.Fatal("an open breaker must fail fast without calling the upstream")
	}
	n, err := testutil.GatherAndCount(reg, "bff_upstream_breaker_state", "bff_upstream_requests_total", "bff_upstream_request_duration_seconds")
	if err != nil || n < 3 {
		t.Fatalf("metrics: n=%d err=%v", n, err)
	}
}

func TestBreakerHalfOpenProbe(t *testing.T) {
	now := time.Unix(0, 0)
	var states []httpclient.BreakerState
	b := httpclient.NewBreaker(2, 10*time.Second, func() time.Time { return now }, func(s httpclient.BreakerState) { states = append(states, s) })
	b.Failure()
	if b.State() != httpclient.StateClosed {
		t.Fatal("one failure must not open")
	}
	b.Failure()
	if b.Allow() {
		t.Fatal("open breaker must reject")
	}
	now = now.Add(11 * time.Second)
	if !b.Allow() || b.Allow() {
		t.Fatal("half-open allows exactly one probe")
	}
	b.Failure()
	if b.State() != httpclient.StateOpen {
		t.Fatal("failed probe re-opens")
	}
	now = now.Add(11 * time.Second)
	_ = b.Allow()
	b.Success()
	if b.State() != httpclient.StateClosed || !b.Allow() {
		t.Fatal("successful probe closes")
	}
	want := []httpclient.BreakerState{httpclient.StateOpen, httpclient.StateHalfOpen, httpclient.StateOpen, httpclient.StateHalfOpen, httpclient.StateClosed}
	if len(states) != len(want) {
		t.Fatalf("transitions = %v", states)
	}
}
