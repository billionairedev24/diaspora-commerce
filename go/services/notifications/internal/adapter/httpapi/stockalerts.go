package httpapi

import (
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// shopper returns the signed-in person behind the request. Service tokens are refused: an alert belongs to the
// shopper whose token the BFF forwards, never to a client id.
func shopper(w http.ResponseWriter, r *http.Request) (string, bool) {
	a, ok := auth.FromContext(r.Context())
	if !ok || a.Subject == "" {
		problem.Write(w, r, problem.New(http.StatusUnauthorized, "auth.unauthenticated", "Bearer token required"))
		return "", false
	}
	if a.Type == "SERVICE" {
		problem.Write(w, r, problem.New(http.StatusForbidden, "notifications.shopper-token-required",
			"Stock alerts need the shopper's own token"))
		return "", false
	}
	return a.Subject, true
}

func (h *handlersImpl) subscribeStockAlert(w http.ResponseWriter, r *http.Request) {
	userID, ok := shopper(w, r)
	if !ok {
		return
	}
	var body struct {
		Atin         string `json:"atin"`
		Email        string `json:"email"`
		Market       string `json:"market"`
		Locale       string `json:"locale"`
		ProductTitle string `json:"productTitle"`
	}
	if !decode(w, r, &body) {
		return
	}
	out, created, err := h.d.StockAlerts.Subscribe(r.Context(), domain.StockAlert{UserID: userID, Atin: body.Atin,
		Email: body.Email, Market: body.Market, Locale: body.Locale, ProductTitle: body.ProductTitle})
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, out)
}

func (h *handlersImpl) listStockAlerts(w http.ResponseWriter, r *http.Request) {
	userID, ok := shopper(w, r)
	if !ok {
		return
	}
	out, err := h.d.StockAlerts.List(r.Context(), userID)
	if err != nil {
		h.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (h *handlersImpl) unsubscribeStockAlert(w http.ResponseWriter, r *http.Request) {
	userID, ok := shopper(w, r)
	if !ok {
		return
	}
	if err := h.d.StockAlerts.Unsubscribe(r.Context(), userID, r.PathValue("atin")); err != nil {
		h.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
