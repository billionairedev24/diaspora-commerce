package httpapi

import "net/http"

// registerReferral mounts "Refer a friend" (S322). Rewards are granted by payments after the friend's first delivered
// order and the return window: there is no route to claim, move or withdraw anything.
func (h *handlersImpl) registerReferral(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/account/referral", h.accountReferral)
}

func (h *handlersImpl) accountReferral(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Referral.Account(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}
