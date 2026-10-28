package httpapi

import (
	"net/http"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// registerMembership mounts the shopper's AfroTransact membership (S323, ADR 0006): the tiers, joining on a saved card,
// changing plan, canceling with the benefits to the end of the period, and what the membership does for the cart.
func (h *handlersImpl) registerMembership(route func(pattern string, fn http.HandlerFunc)) {
	route("GET /v1/membership/tiers", h.membershipOffer)
	route("GET /v1/account/membership", h.accountMembership)
	route("POST /v1/account/membership", h.joinMembership)
	route("PUT /v1/account/membership", h.changeMembership)
	route("POST /v1/account/membership/cancel", h.cancelMembership)
	route("POST /v1/account/membership/resume", h.resumeMembership)
	route("GET /v1/account/membership/benefits", h.membershipBenefits)
	route("GET /v1/cart/membership", h.cartMembership)
}

func (h *handlersImpl) membershipOffer(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Membership.Offer(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) accountMembership(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Membership.Account(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) joinMembership(w http.ResponseWriter, r *http.Request) {
	var b domain.MembershipJoinRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Membership.Join(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) changeMembership(w http.ResponseWriter, r *http.Request) {
	var b domain.MembershipJoinRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Membership.Change(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) cancelMembership(w http.ResponseWriter, r *http.Request) {
	var b domain.MembershipCancelRequest
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Membership.Cancel(r.Context(), callerFrom(r.Context()), b.Refund)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) resumeMembership(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Membership.Resume(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) membershipBenefits(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	v, err := h.s.Shopper.Membership.Benefits(r.Context(), callerFrom(r.Context()), limit)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) cartMembership(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Membership.CartSavings(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}
