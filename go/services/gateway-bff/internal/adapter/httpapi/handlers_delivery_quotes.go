package httpapi

import (
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// registerDeliveryQuotes mounts "Ask for a delivery quote" (listing rules D20): the shopper's own requests only, each
// call carrying their token to orders. Requests are rate-limited here per client address as well as per shopper in orders.
func (h *handlersImpl) registerDeliveryQuotes(route func(pattern string, fn http.HandlerFunc)) {
	lookup := func(fn http.HandlerFunc) http.HandlerFunc { return h.rl.wrap("lookup", h.o.LookupPerMinute, fn) }
	route("POST /v1/me/delivery-quotes", lookup(h.requestDeliveryQuote))
	route("GET /v1/me/delivery-quotes", h.myDeliveryQuotes)
	route("GET /v1/me/delivery-quotes/{id}", h.myDeliveryQuote)
}

func (h *handlersImpl) requestDeliveryQuote(w http.ResponseWriter, r *http.Request) {
	var b domain.NewQuoteRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.DeliveryQuotes.Request(r.Context(), callerFrom(r.Context()), b)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) myDeliveryQuotes(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.DeliveryQuotes.Mine(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) myDeliveryQuote(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.DeliveryQuotes.Get(r.Context(), callerFrom(r.Context()), r.PathValue("id"))
	h.ok(w, r, v, err)
}
