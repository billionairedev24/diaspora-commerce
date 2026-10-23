package httpapi

import (
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// maintenanceOpen are the routes that work in maintenance: the status the apps poll, staff (so they can switch it off)
// and sign-in/out (staff sign in through the same flow).
func maintenanceOpen(path string) bool {
	return path == "/v1/maintenance" || strings.HasPrefix(path, "/v1/staff/") || strings.HasPrefix(path, "/v1/auth/") || path == "/v1/me"
}

// sellerSide are the routes Seller Center uses: its own, and the address lookup its forms share with checkout.
func sellerSide(path string) bool {
	return strings.HasPrefix(path, "/v1/seller/") || strings.HasPrefix(path, "/v1/geo/")
}

// maintenanceGate answers 503 MAINTENANCE (with Retry-After) to customer and seller calls while the switch is on.
// With the SHOPPERS scope the seller routes (/v1/seller/...) still work.
func maintenanceGate(svc app.MaintenanceService, log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		if svc == nil {
			return next
		}
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodOptions || maintenanceOpen(r.URL.Path) {
				next.ServeHTTP(w, r)
				return
			}
			m := svc.Current(r.Context())
			if !m.Blocks(sellerSide(r.URL.Path)) {
				next.ServeHTTP(w, r)
				return
			}
			p := domain.NewProblem(http.StatusServiceUnavailable, domain.CodeMaintenance, m.Title, m.Message)
			p.RetryAfter = 300
			if m.ExpectedBackAt != nil {
				if d := time.Until(*m.ExpectedBackAt); d > 0 {
					p.RetryAfter = int(d.Seconds()) + 1
				}
			}
			writeProblem(w, r, log, p)
		})
	}
}

func (h *handlersImpl) maintenance(w http.ResponseWriter, r *http.Request) {
	st := domain.MaintenanceStatus{}
	if h.s.Maintenance != nil {
		st = h.s.Maintenance.Status(r.Context())
	}
	w.Header().Set("Cache-Control", "no-store")
	h.reply(w, r, http.StatusOK, st, nil)
}
