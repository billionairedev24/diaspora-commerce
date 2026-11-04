package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// registerCredit mounts AfroTransact credit and gift cards (S320, S321). There is no route that moves credit out:
// no cash-out, payout, card refund or transfer.
func (h *handlersImpl) registerCredit(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/account/credit", h.accountCredit)
	route("GET /v1/gift-cards/config", h.giftCardConfig)
	route("GET /v1/account/gift-cards", h.myGiftCards)
	route("POST /v1/account/gift-cards", h.buyGiftCard)
	route("POST /v1/account/gift-cards/redeem", h.redeemGiftCard)
	route("POST /v1/account/gift-cards/{giftCardId}/confirm", h.confirmGiftCard)
}

func (h *handlersImpl) accountCredit(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	v, err := h.s.Shopper.Credit.Account(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("before"), limit)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) giftCardConfig(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Credit.GiftCardConfig(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) myGiftCards(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Credit.MyGiftCards(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) buyGiftCard(w http.ResponseWriter, r *http.Request) {
	var b domain.GiftCardPurchaseRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Credit.Buy(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) confirmGiftCard(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Credit.ConfirmPurchase(r.Context(), callerFrom(r.Context()), r.PathValue("giftCardId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) redeemGiftCard(w http.ResponseWriter, r *http.Request) {
	var b domain.GiftCardRedeemRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	c := callerFrom(r.Context())
	v, err := h.s.Shopper.Credit.Redeem(r.Context(), c, b.Code, c.ClientIP)
	h.ok(w, r, v, err)
}
