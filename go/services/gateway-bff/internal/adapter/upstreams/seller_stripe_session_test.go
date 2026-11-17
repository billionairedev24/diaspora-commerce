package upstreams

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
)

// The first Stripe session call of an application creates the connected account, which is slower than the upstream's
// default timeout; it used to fail the step on the first try and work on the retry. It now waits and retries transient errors.
func TestApplicationStripeSessionSurvivesASlowFirstUseAndATransientError(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/applications/me/stripe/account-session" || r.Method != http.MethodPost {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		switch calls.Add(1) {
		case 1:
			time.Sleep(150 * time.Millisecond) // account creation: longer than the upstream's default timeout below
			w.WriteHeader(http.StatusBadGateway)
		default:
			time.Sleep(60 * time.Millisecond)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"accountId":"acct_1","clientSecret":"secret_1","localTest":false}`))
		}
	}))
	t.Cleanup(srv.Close)
	saved := *stripeSessionPolicy
	stripeSessionPolicy.Timeout, stripeSessionPolicy.Backoff = 2*time.Second, 5*time.Millisecond
	t.Cleanup(func() { *stripeSessionPolicy = saved })
	s := NewSellerService(httpclient.New(httpclient.Options{Name: "seller", BaseURL: srv.URL, Timeout: 40 * time.Millisecond, BreakerFailures: 100}))

	got, err := s.ApplicationStripeSession(context.Background(), "tok")
	if err != nil || got.AccountID != "acct_1" || got.ClientSecret != "secret_1" || calls.Load() != 2 {
		t.Fatalf("got=%+v err=%v calls=%d", got, err, calls.Load())
	}
}
