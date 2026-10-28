package httpapi

import (
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
)

// Delivery runs, shopper side (S262, S263): contracts/openapi/gateway-bff.yaml, tag Delivery runs. Locations and
// their pages, location run-alert subscriptions with consent per channel (guest by email with double opt-in, or
// signed in from the account screen) and the advertorials for the shopper's place.

func (h *handlersImpl) registerRunAlerts(route func(pattern string, fn http.HandlerFunc), lookup func(http.HandlerFunc) http.HandlerFunc) {
	if h.s.RunAlerts == nil {
		return
	}
	route("GET /v1/delivery-runs/locations", h.runLocations)
	route("GET /v1/delivery-runs/locations/{slug}", h.runLocationPage)
	route("POST /v1/delivery-runs/locations/{slug}/visits", lookup(h.countRunVisit))
	route("POST /v1/delivery-runs/subscriptions", lookup(h.subscribeRunAlerts))
	route("POST /v1/delivery-runs/subscriptions/confirm", lookup(h.confirmRunAlerts))
	route("POST /v1/delivery-runs/subscriptions/unsubscribe", lookup(h.unsubscribeRunAlerts))
	route("GET /v1/account/run-subscriptions", h.myRunSubscriptions)
	route("POST /v1/account/run-subscriptions", h.subscribeMyRunAlerts)
	route("DELETE /v1/account/run-subscriptions/{locationId}", h.unsubscribeMyRunAlerts)
	route("GET /v1/delivery-runs/advertorials", h.runAdvertorials)
	route("POST /v1/delivery-runs/advertorials/{advertorialId}/events", lookup(h.runAdvertorialEvent))
}

func (h *handlersImpl) runLocations(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.RunAlerts.Locations(r.Context())
	if err == nil {
		w.Header().Set("Cache-Control", "public, max-age=60")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) runLocationPage(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.RunAlerts.LocationPage(r.Context(), r.PathValue("slug"))
	if err == nil {
		w.Header().Set("Cache-Control", "public, max-age=30")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) subscribeRunAlerts(w http.ResponseWriter, r *http.Request) {
	var b app.RunSubscribeRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.RunAlerts.Subscribe(r.Context(), b)
	h.reply(w, r, http.StatusAccepted, v, err)
}

func (h *handlersImpl) confirmRunAlerts(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Token string `json:"token"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.RunAlerts.Confirm(r.Context(), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) unsubscribeRunAlerts(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Token    string   `json:"token"`
		Channels []string `json:"channels"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.RunAlerts.Unsubscribe(r.Context(), b.Token, b.Channels)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) myRunSubscriptions(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.RunAlerts.Mine(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) subscribeMyRunAlerts(w http.ResponseWriter, r *http.Request) {
	var b app.MyRunSubscriptionRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.RunAlerts.SubscribeMine(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) unsubscribeMyRunAlerts(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.RunAlerts.UnsubscribeMine(r.Context(), callerFrom(r.Context()), r.PathValue("locationId"), r.URL.Query()["channel"])
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) runAdvertorials(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.RunAlerts.Advertorials(r.Context(), callerFrom(r.Context()), app.AdvertorialQuery{ZIP: q.Get("zip"), Lat: q.Get("lat"),
		Lng: q.Get("lng"), Country: q.Get("country"), Region: q.Get("region"), City: q.Get("city"), Visitor: q.Get("visitor"), Limit: q.Get("limit")})
	if err == nil {
		// Personal: the variant is the visitor's and the place is the session's.
		w.Header().Set("Cache-Control", "private, max-age=15")
	}
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) countRunVisit(w http.ResponseWriter, r *http.Request) {
	var b app.RunVisitRequest
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	h.reply(w, r, http.StatusNoContent, nil, h.s.RunAlerts.CountVisit(r.Context(), r.PathValue("slug"), b))
}

func (h *handlersImpl) runAdvertorialEvent(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Type string `json:"type"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	err := h.s.RunAlerts.AdvertorialEvent(r.Context(), r.PathValue("advertorialId"), b.Type)
	h.reply(w, r, http.StatusNoContent, nil, err)
}
