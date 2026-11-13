package upstreams

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The tax quote is the gateway's own call with its service token. On a storefront request it must not carry
// X-Acting-As: CUSTOMER: with security off tax-returns resolves the local super admin and the header would narrow it to a
// shopper without tax:quote (403 auth.missing-permission, checkout without a tax estimate).
func TestTaxQuoteNeverActsAsTheShopper(t *testing.T) {
	var acting, auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		acting, auth = r.Header.Get("X-Acting-As"), r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{}`))
	}))
	t.Cleanup(srv.Close)
	tokens := &scopeTokens{}
	c := NewReturns(httpclient.New(httpclient.Options{Name: "tax-returns", BaseURL: srv.URL, Timeout: 2 * time.Second}), tokens)

	if _, err := c.TaxQuote(port.WithShopper(context.Background()), port.UpTaxQuoteRequest{}); err != nil {
		t.Fatal(err)
	}
	if auth != "Bearer svc-token" || acting != "" {
		t.Fatalf("Authorization=%q X-Acting-As=%q; want the service token and no acting-as header", auth, acting)
	}
}
