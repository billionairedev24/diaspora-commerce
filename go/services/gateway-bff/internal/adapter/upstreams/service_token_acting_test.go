package upstreams

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Security audit F16: X-Acting-As: CUSTOMER is added only when the bearer is the person's own relayed token
// (httpclient.Request.UserBearer). Every call that carries the gateway's client-credentials token, on a storefront request
// (port.WithShopper), must reach the upstream without it: with security off the upstream resolves the local super admin and
// the header would narrow it to a shopper without the permission the service scope stands for (403).
type seen struct {
	mu          sync.Mutex
	acting, aut string
}

func (s *seen) handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		s.acting, s.aut = r.Header.Get("X-Acting-As"), r.Header.Get("Authorization")
		s.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{}`))
	})
}

func (s *seen) get() (acting, auth string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.acting, s.aut
}

func TestServiceTokenCallsNeverActAsTheShopper(t *testing.T) {
	rec := &seen{}
	srv := httptest.NewServer(rec.handler())
	t.Cleanup(srv.Close)
	hc := func(name string) httpclient.Client {
		return httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 2 * time.Second})
	}
	tokens := &scopeTokens{}
	shopper := port.WithShopper(context.Background())
	guest := port.WithServiceBearer(shopper)

	cases := []struct {
		name string
		call func()
	}{
		{"stock alert (guest)", func() {
			_ = NewNotifications(hc("notifications")).SubscribeGuestStockAlert(shopper, "svc-token", port.GuestStockAlertInput{})
		}},
		{"careers resume upload (guest)", func() {
			_, _ = NewMedia(hc("media")).RequestUpload(guest, "svc-token", "career-resume", "cv.pdf", "application/pdf", 10)
		}},
		{"careers resume complete (guest)", func() { _, _ = NewMedia(hc("media")).Complete(guest, "svc-token", "a1") }},
		{"membership offer (guest)", func() { _, _ = NewMembership(hc("payments")).Tiers(guest, "svc-token") }},
		{"local delivery location", func() {
			_, _ = NewLocalDelivery(hc("seller"), hc("geo"), hc("payments"), tokens).Location(shopper, "loc1")
		}},
		{"local delivery quote", func() {
			_, _ = NewLocalDelivery(hc("seller"), hc("geo"), hc("payments"), tokens).Quote(shopper, "org1", 3, 1)
		}},
		{"pay links lookup", func() { _, _ = NewPayLinks(hc("orders"), tokens).Lookup(shopper, "AT-1", "tok") }},
		{"native sign-in password", func() {
			_, _ = NewNativeSignIn(hc("identity"), tokens).Password(shopper, "a@b.c", "pw", "", port.NativeWho{})
		}},
		{"proof links", func() { NewProofLinks(hc("media"), tokens).URLs(shopper, "a1") }},
		{"shipping tracking (rate scope)", func() {
			_, _ = NewDeliveryFacts(hc("promise"), hc("carrier"), hc("geo"), tokens).Tracking(shopper, "", "ups", "1Z")
		}},
		{"fees", func() { _, _ = NewFees(hc("payments"), tokens).Schedules(shopper) }},
		{"session policy", func() { _, _ = NewSessionPolicy(hc("identity"), tokens).Policies(shopper) }},
		{"tax quote", func() { _, _ = NewReturns(hc("tax-returns"), tokens).TaxQuote(shopper, port.UpTaxQuoteRequest{}) }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			*rec = seen{}
			tc.call()
			acting, auth := rec.get()
			if auth == "" {
				t.Fatalf("no upstream call with a bearer was made")
			}
			if acting != "" {
				t.Fatalf("X-Acting-As = %q on a service-token call; it must be absent", acting)
			}
		})
	}
}

func TestUserTokenCallsStillActAsTheShopperOnStorefrontRequests(t *testing.T) {
	rec := &seen{}
	srv := httptest.NewServer(rec.handler())
	t.Cleanup(srv.Close)
	c := NewMembership(httpclient.New(httpclient.Options{Name: "payments", BaseURL: srv.URL, Timeout: 2 * time.Second}))

	_, _ = c.Me(port.WithShopper(context.Background()), "user-token")
	if acting, auth := rec.get(); acting != "CUSTOMER" || auth != "Bearer user-token" {
		t.Fatalf("storefront user call: X-Acting-As=%q Authorization=%q", acting, auth)
	}
	*rec = seen{}
	_, _ = c.Me(context.Background(), "user-token") // back office request: never marked
	if acting, _ := rec.get(); acting != "" {
		t.Fatalf("back-office user call must not act as a shopper, got %q", acting)
	}
	*rec = seen{}
	// The same method on the guest path carries the BFF's own token: no acting-as.
	_, _ = c.Tiers(port.WithServiceBearer(port.WithShopper(context.Background())), "svc-token")
	if acting, _ := rec.get(); acting != "" {
		t.Fatalf("guest tiers call must not act as a shopper, got %q", acting)
	}
	*rec = seen{}
	_, _ = c.Tiers(port.WithShopper(context.Background()), "user-token") // signed in: the person's own token
	if acting, _ := rec.get(); acting != "CUSTOMER" {
		t.Fatalf("signed-in tiers call: X-Acting-As = %q, want CUSTOMER", acting)
	}
}

func TestEmptyScopeServiceTokenIsRefused(t *testing.T) {
	asked := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { asked = true }))
	t.Cleanup(srv.Close)
	tokens := NewServiceTokens(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Timeout: time.Second}), IdentityOptions{})

	for _, scope := range []string{"", "   "} {
		if _, err := tokens.Token(context.Background(), scope); err == nil {
			t.Fatalf("scope %q: want an error, got a token", scope)
		}
	}
	if asked {
		t.Fatal("an empty scope must be refused before identity is called")
	}
}
