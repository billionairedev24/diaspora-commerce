package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

type fixedMaintenance struct{ m domain.Maintenance }

func (f fixedMaintenance) Current(context.Context) domain.Maintenance { return f.m }
func (f fixedMaintenance) Status(context.Context) domain.MaintenanceStatus {
	return f.m.Status()
}
func (f fixedMaintenance) Set(context.Context, string, app.MaintenanceChange) (domain.Maintenance, error) {
	return f.m, nil
}

func serveGate(m domain.Maintenance, method, path string) *httptest.ResponseRecorder {
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	rec := httptest.NewRecorder()
	maintenanceGate(fixedMaintenance{m}, slog.Default())(next).ServeHTTP(rec, httptest.NewRequest(method, path, nil))
	return rec
}

func TestMaintenanceGate(t *testing.T) {
	back := time.Now().Add(10 * time.Minute)
	on := domain.Maintenance{Enabled: true, Scope: domain.MaintenanceShoppers, Title: "Back soon", Message: "Upgrading the database.", ExpectedBackAt: &back}
	all := domain.Maintenance{Enabled: true, Scope: domain.MaintenanceAll, Message: "Upgrading."}
	cases := []struct {
		name   string
		m      domain.Maintenance
		method string
		path   string
		want   int
	}{
		{"off lets everything through", domain.Maintenance{}, "GET", "/v1/cart", 200},
		{"shopper call is refused", on, "GET", "/v1/cart", 503},
		{"shopper write is refused", on, "POST", "/v1/checkout/place", 503},
		{"sellers keep working when only shoppers are paused", on, "GET", "/v1/seller/orders", 200},
		{"sellers keep their address lookup", on, "GET", "/v1/geo/address-suggestions", 200},
		{"sellers are refused when all are paused", all, "GET", "/v1/seller/orders", 503},
		{"the status is always answered", all, "GET", "/v1/maintenance", 200},
		{"staff always work", all, "GET", "/v1/staff/resources/orders", 200},
		{"sign-in always works", all, "GET", "/v1/auth/login", 200},
		{"preflights pass", all, "OPTIONS", "/v1/cart", 200},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := serveGate(c.m, c.method, c.path)
			if rec.Code != c.want {
				t.Fatalf("status %d, want %d: %s", rec.Code, c.want, rec.Body)
			}
		})
	}
	rec := serveGate(on, "GET", "/v1/cart")
	if rec.Header().Get("Retry-After") == "" || rec.Header().Get("Retry-After") == "300" {
		t.Fatalf("Retry-After should follow the expected time: %q", rec.Header().Get("Retry-After"))
	}
}
