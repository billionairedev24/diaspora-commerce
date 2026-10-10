package carrierhttp_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/carrier/carrierhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

func get(url string) func(ctx context.Context) (*http.Request, error) {
	return func(ctx context.Context) (*http.Request, error) {
		return http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	}
}

func TestRetriesOn5xxThenSucceeds(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if calls.Add(1) < 3 {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()
	c := carrierhttp.New(carrierhttp.Options{Carrier: domain.UPS, HTTP: srv.Client(), MaxRetries: 2, BaseBackoff: time.Millisecond})
	res, err := c.Do(context.Background(), get(srv.URL))
	if err != nil || res.Status != 200 || calls.Load() != 3 {
		t.Fatalf("res=%+v err=%v calls=%d", res, err, calls.Load())
	}
}

func TestRetryAfterOn429(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if calls.Add(1) == 1 {
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	c := carrierhttp.New(carrierhttp.Options{Carrier: domain.FedEx, HTTP: srv.Client(), BaseBackoff: time.Millisecond})
	if _, err := c.Do(context.Background(), get(srv.URL)); err != nil || calls.Load() != 2 {
		t.Fatalf("err=%v calls=%d", err, calls.Load())
	}
}

func TestErrorMapping(t *testing.T) {
	cases := []struct {
		status int
		want   error
		calls  int32
	}{
		{http.StatusBadRequest, domain.ErrCarrierRejected, 1},
		{http.StatusUnprocessableEntity, domain.ErrCarrierRejected, 1},
		{http.StatusNotFound, domain.ErrNotFound, 1},
		{http.StatusForbidden, domain.ErrCarrierAuth, 1},
		{http.StatusServiceUnavailable, domain.ErrCarrierUnavailable, 3},
		{http.StatusTooManyRequests, domain.ErrCarrierUnavailable, 3},
	}
	for _, tc := range cases {
		var calls atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			calls.Add(1)
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(`{"code":"E1","message":"nope"}`))
		}))
		c := carrierhttp.New(carrierhttp.Options{Carrier: domain.DHL, HTTP: srv.Client(), MaxRetries: 2, BaseBackoff: time.Millisecond,
			ParseError: func(int, []byte) (string, string) { return "E1", "nope" }})
		_, err := c.Do(context.Background(), get(srv.URL))
		srv.Close()
		var ce *domain.CarrierError
		if !errors.Is(err, tc.want) || !errors.As(err, &ce) || ce.Code != "E1" || ce.Status != tc.status || calls.Load() != tc.calls {
			t.Fatalf("status %d: err=%v calls=%d", tc.status, err, calls.Load())
		}
		if domain.Retryable(err) != errors.Is(tc.want, domain.ErrCarrierUnavailable) {
			t.Fatalf("status %d: retryable mismatch", tc.status)
		}
	}
}

func TestDeadlineStopsRetries(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Retry-After", "5")
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()
	c := carrierhttp.New(carrierhttp.Options{Carrier: domain.USPS, HTTP: srv.Client(), MaxRetries: 5})
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := c.Do(ctx, get(srv.URL))
	if !errors.Is(err, domain.ErrCarrierUnavailable) || time.Since(start) > 150*time.Millisecond {
		t.Fatalf("must give up immediately when Retry-After exceeds the deadline: err=%v elapsed=%v", err, time.Since(start))
	}
}

func TestTransportErrorIsRetryable(t *testing.T) {
	c := carrierhttp.New(carrierhttp.Options{Carrier: domain.USPS, HTTP: &http.Client{Timeout: time.Second}, MaxRetries: -1})
	_, err := c.Do(context.Background(), get("http://127.0.0.1:1"))
	if !errors.Is(err, domain.ErrCarrierUnavailable) {
		t.Fatalf("got %v", err)
	}
}

func TestTokenSourceCachesAndRefreshes(t *testing.T) {
	now := time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)
	var fetches int
	ts := carrierhttp.NewTokenSource(func(context.Context) (string, time.Duration, error) {
		fetches++
		return "tok" + string(rune('0'+fetches)), time.Hour, nil
	}, func() time.Time { return now })
	for range 5 {
		if tok, _ := ts.Token(context.Background()); tok != "tok1" {
			t.Fatalf("token %q", tok)
		}
	}
	now = now.Add(59*time.Minute + 30*time.Second) // inside the 60 s refresh skew of a 1 h token
	if tok, _ := ts.Token(context.Background()); tok != "tok2" || fetches != 2 {
		t.Fatalf("expected refresh before expiry, got %q after %d fetches", tok, fetches)
	}
	ts.Invalidate()
	if tok, _ := ts.Token(context.Background()); tok != "tok3" {
		t.Fatalf("invalidate must force a new exchange, got %q", tok)
	}
}

func TestUnauthorizedRefreshesTokenOnce(t *testing.T) {
	var fetches atomic.Int32
	ts := carrierhttp.NewTokenSource(func(context.Context) (string, time.Duration, error) {
		n := fetches.Add(1)
		return map[int32]string{1: "stale", 2: "fresh"}[n], time.Hour, nil
	}, nil)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer fresh" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	c := carrierhttp.New(carrierhttp.Options{Carrier: domain.UPS, HTTP: srv.Client(), Auth: ts})
	if _, err := c.Do(context.Background(), get(srv.URL)); err != nil || fetches.Load() != 2 {
		t.Fatalf("err=%v fetches=%d", err, fetches.Load())
	}
}

func TestVerifyHMAC(t *testing.T) {
	body := []byte(`{"trackingNumber":"1"}`)
	sig := carrierhttp.SignHMAC("s3cret", body)
	if !carrierhttp.VerifyHMAC("s3cret", sig, body) {
		t.Fatal("valid base64 signature rejected")
	}
	if carrierhttp.VerifyHMAC("s3cret", sig, []byte(`{"trackingNumber":"2"}`)) || carrierhttp.VerifyHMAC("other", sig, body) ||
		carrierhttp.VerifyHMAC("", "", body) {
		t.Fatal("invalid signature accepted")
	}
}

func TestOneOrMany(t *testing.T) {
	one, err := carrierhttp.OneOrMany[map[string]string]([]byte(`{"a":"1"}`))
	if err != nil || len(one) != 1 {
		t.Fatalf("object: %v %v", one, err)
	}
	many, err := carrierhttp.OneOrMany[map[string]string]([]byte(`[{"a":"1"},{"a":"2"}]`))
	if err != nil || len(many) != 2 {
		t.Fatalf("array: %v %v", many, err)
	}
}
