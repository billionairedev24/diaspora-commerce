package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The acting context follows the surface (route family), never the caller's role: every storefront and customer-app
// route is a shopper request for staff, members and customers alike; only the back offices relay the token as issued.
func TestActingContextIsDecidedByTheRouteNotTheRole(t *testing.T) {
	for _, tc := range []struct {
		path        string
		wantShopper bool
	}{
		{"/v1/cart", true},
		{"/v1/cart/items", true},
		{"/v1/checkout/summary", true},
		{"/v1/checkout/orders", true},
		{"/v1/account/orders", true},
		{"/v1/account/addresses", true},
		{"/v1/account/payment-methods", true},
		{"/v1/me", true},
		{"/v1/stock-alerts", true},
		{"/v1/services/bookings", true},
		{"/v1/seller/session", false},
		{"/v1/seller/orders", false},
		{"/v1/staff/orders", false},
		{"/v1/staff/credit", false},
	} {
		t.Run(tc.path, func(t *testing.T) {
			var got bool
			h := actingContext(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
				got = port.IsShopper(r.Context())
			}))
			h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequestWithContext(context.Background(), http.MethodGet, tc.path, nil))
			if got != tc.wantShopper {
				t.Fatalf("%s: shopper = %v, want %v", tc.path, got, tc.wantShopper)
			}
		})
	}
}
