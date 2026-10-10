package upstreams

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func TestCartSendsDeliveryPlaceHeaders(t *testing.T) {
	var got http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"lines":[]}`))
	}))
	defer srv.Close()
	cart := NewCart(httpclient.New(httpclient.Options{Name: "cart", BaseURL: srv.URL, Transport: http.DefaultTransport}))

	owner := port.CartOwner{GuestID: "g-1", Country: "US", Region: "TX", City: "Lubbock"}
	if _, err := cart.Get(context.Background(), owner); err != nil {
		t.Fatalf("get: %v", err)
	}
	for h, want := range map[string]string{"X-Delivery-Country": "US", "X-Delivery-Region": "TX", "X-Delivery-City": "Lubbock", GuestCartHeader: "g-1"} {
		if v := got.Get(h); v != want {
			t.Errorf("%s = %q, want %q", h, v, want)
		}
	}

	if _, err := cart.Get(context.Background(), port.CartOwner{GuestID: "g-1"}); err != nil {
		t.Fatalf("get without place: %v", err)
	}
	if v := got.Get("X-Delivery-Country"); v != "" {
		t.Errorf("X-Delivery-Country sent without a place: %q", v)
	}
}
