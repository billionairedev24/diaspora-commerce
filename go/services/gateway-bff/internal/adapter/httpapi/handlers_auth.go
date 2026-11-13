package httpapi

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- auth

func (h *handlersImpl) me(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Auth.Me(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

// sessionPolicy is GET /v1/session/policy: when this caller is signed out. It never counts as activity.
func (h *handlersImpl) sessionPolicy(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Sessions.Policy(r.Context(), callerFrom(r.Context()), r.Header.Get("X-App"))
	h.reply(w, r, http.StatusOK, v, err)
}

// sessionActivity is POST /v1/session/activity: the web app reports real user input. The identify middleware already
// moved LastActivityAt (ActivityExplicit); an expired session never gets here (401 SESSION_*_TIMEOUT).
func (h *handlersImpl) sessionActivity(w http.ResponseWriter, r *http.Request) {
	if !callerFrom(r.Context()).Authenticated() {
		h.reply(w, r, 0, nil, domain.Unauthenticated())
		return
	}
	h.reply(w, r, http.StatusNoContent, nil, nil)
}

func (h *handlersImpl) startLogin(w http.ResponseWriter, r *http.Request, provider string) {
	q := r.URL.Query()
	returnTo := q.Get("returnTo")
	if returnTo == "" {
		returnTo = "/"
	}
	url, err := h.s.Auth.AuthorizeURL(r.Context(), callerFrom(r.Context()), app.LoginRequest{Provider: provider, ReturnTo: returnTo,
		Prompt: q.Get("prompt"), LoginHint: q.Get("login_hint"), Invitation: q.Get("invitation"), Referral: q.Get("referral"), Intent: q.Get("intent"), Retried: q.Get("retried") == "1"})
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	h.redirect(w, r, url)
}

func (h *handlersImpl) login(w http.ResponseWriter, r *http.Request) { h.startLogin(w, r, "") }

func (h *handlersImpl) socialLogin(w http.ResponseWriter, r *http.Request) {
	h.startLogin(w, r, strings.ToLower(r.PathValue("provider")))
}

func (h *handlersImpl) callback(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	to := h.s.Auth.Callback(r.Context(), callerFrom(r.Context()), q.Get("code"), q.Get("state"), q.Get("error"))
	h.redirect(w, r, to)
}

// signOut ends the BFF session and answers where the browser goes next: identity's end-session endpoint, which ends
// the identity session as well (single sign-out) and returns to the calling app's signed-out landing.
func (h *handlersImpl) signOut(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ReturnTo string `json:"returnTo"`
	}
	if err := decode(r, &in, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	to, err := h.s.Auth.SignOut(r.Context(), callerFrom(r.Context()), r.Header.Get("Origin"), in.ReturnTo)
	var body map[string]any
	if err == nil {
		body = map[string]any{"redirectTo": nil}
		if to != "" {
			body["redirectTo"] = to
		}
	}
	h.reply(w, r, http.StatusOK, body, err)
}

// signedOut is each web app's post-logout landing (registered at identity): the storefront signed out, or the app's
// own sign-in for Seller Center and the staff console.
func (h *handlersImpl) signedOut(w http.ResponseWriter, r *http.Request) {
	to, err := h.s.Auth.SignedOut(r.Context(), callerFrom(r.Context()), r.PathValue("app"), r.URL.Query().Get("state"))
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	h.redirect(w, r, to)
}

// ---------------------------------------------------------------- account

func (h *handlersImpl) listAddresses(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Account.Addresses(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) createAddress(w http.ResponseWriter, r *http.Request) {
	var in domain.AddressInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Account.CreateAddress(r.Context(), callerFrom(r.Context()), in)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) updateAddress(w http.ResponseWriter, r *http.Request) {
	var in domain.AddressInput
	if err := decode(r, &in, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Account.UpdateAddress(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"), in)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) deleteAddress(w http.ResponseWriter, r *http.Request) {
	err := h.s.Account.DeleteAddress(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) startPhoneVerification(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Account.StartPhoneVerification(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusAccepted, v, err)
}

func (h *handlersImpl) confirmPhoneVerification(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Code string `json:"code"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Account.ConfirmPhoneVerification(r.Context(), callerFrom(r.Context()), b.Code)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) defaultAddress(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Account.SetDefaultAddress(r.Context(), callerFrom(r.Context()), r.PathValue("addressId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) paymentMethods(w http.ResponseWriter, r *http.Request) {
	if h.s.Shopper.enabled() {
		v, err := h.s.Shopper.Wallet.List(r.Context(), callerFrom(r.Context()))
		h.reply(w, r, http.StatusOK, v, err)
		return
	}
	v, err := h.s.Account.PaymentMethods(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) setupIntent(w http.ResponseWriter, r *http.Request) {
	if h.s.Shopper.enabled() {
		var b struct {
			Types []string `json:"types"`
		}
		if err := decode(r, &b, true); err != nil {
			h.reply(w, r, 0, nil, err)
			return
		}
		v, err := h.s.Shopper.Wallet.SetupIntent(r.Context(), callerFrom(r.Context()), b.Types)
		h.reply(w, r, http.StatusCreated, v, err)
		return
	}
	v, err := h.s.Account.SetupIntent(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) deletePaymentMethod(w http.ResponseWriter, r *http.Request) {
	if h.s.Shopper.enabled() {
		err := h.s.Shopper.Wallet.Remove(r.Context(), callerFrom(r.Context()), r.PathValue("paymentMethodId"))
		h.reply(w, r, http.StatusNoContent, nil, err)
		return
	}
	err := h.s.Account.DeletePaymentMethod(r.Context(), callerFrom(r.Context()), r.PathValue("paymentMethodId"))
	h.reply(w, r, http.StatusNoContent, nil, err)
}

func (h *handlersImpl) orders(w http.ResponseWriter, r *http.Request) {
	limit := 10
	if s := r.URL.Query().Get("limit"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 1 || n > 50 {
			h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"limit": "Between 1 and 50"}))
			return
		}
		limit = n
	}
	if h.s.Shopper.enabled() {
		v, err := h.s.Shopper.Orders.List(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("cursor"), limit)
		h.reply(w, r, http.StatusOK, v, err)
		return
	}
	v, err := h.s.Account.Orders(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("cursor"), limit)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) cancelShipment(w http.ResponseWriter, r *http.Request) {
	if h.s.Shopper.enabled() {
		var b struct {
			Reason string `json:"reason"`
		}
		if err := decode(r, &b, true); err != nil {
			h.reply(w, r, 0, nil, err)
			return
		}
		v, err := h.s.Shopper.Orders.Cancel(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"), r.PathValue("shipmentId"), b.Reason)
		h.reply(w, r, http.StatusOK, v, err)
		return
	}
	v, err := h.s.Account.CancelShipment(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"), r.PathValue("shipmentId"))
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- checkout

func parseCheckoutPackageDelivery(q map[string][]string) map[string]string {
	out := map[string]string{}
	for _, raw := range q["packageDelivery"] {
		for _, part := range strings.Split(raw, ",") {
			part = strings.TrimSpace(part)
			if i := strings.Index(part, ":"); i > 0 {
				out[strings.TrimSpace(part[:i])] = strings.TrimSpace(part[i+1:])
			}
		}
	}
	return out
}

func (h *handlersImpl) checkoutSummary(w http.ResponseWriter, r *http.Request) {
	delivery := parseCheckoutPackageDelivery(r.URL.Query())
	if h.s.Shopper.enabled() {
		v, err := h.s.Shopper.Checkout.Summary(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("addressId"), delivery,
			r.URL.Query().Get("couponCode"))
		h.reply(w, r, http.StatusOK, v, err)
		return
	}
	v, err := h.s.Checkout.Summary(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("addressId"))
	h.reply(w, r, http.StatusOK, v, err)
}

// attributeHomeOrder credits a placed order to the home module the shopper last clicked (cookie at_hm, 24 hours). No consent,
// no read: the cookie is not even looked at unless the shopper's own consent cookie says Analytics is on. Nothing is added
// to the order and nothing identifies the shopper; the only effect is two aggregate counters.
func (h *handlersImpl) attributeHomeOrder(r *http.Request, conf domain.OrderConfirmation) {
	if h.s.Site == nil {
		return
	}
	consent, err := r.Cookie(domain.ConsentCookie)
	if err != nil || !domain.AnalyticsConsented(consent.Value) {
		return
	}
	hm, err := r.Cookie(domain.HomeAttributionCookie)
	if err != nil {
		return
	}
	module, ok := domain.ParseHomeAttribution(hm.Value)
	if !ok {
		return
	}
	variant := ""
	if v, verr := r.Cookie(domain.HomeVariantCookie); verr == nil {
		variant = v.Value
	}
	h.s.Site.RecordHomeOrder(r.Context(), conf.OrderNumber, domain.HomeOrderAttribution{Module: module, Variant: variant,
		Locale: firstNonBlank(r.URL.Query().Get("locale"), r.Header.Get("Accept-Language")), TotalMinor: conf.TotalMinor})
}

func (h *handlersImpl) placeOrder(w http.ResponseWriter, r *http.Request) {
	var b domain.PlaceOrderRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	b.Attribution = b.Attribution.Clean()
	if b.Attribution == nil {
		// Last-touch attribution the storefront keeps in at_attr (the visit, or 30 days with consent).
		if ck, err := r.Cookie(domain.PreorderAttrCookie); err == nil {
			b.Attribution = domain.ParseAttributionCookie(ck.Value)
		}
	}
	place := h.s.Checkout.PlaceOrder
	if h.s.Shopper.enabled() {
		place = h.s.Shopper.Checkout.Place
	}
	res, err := place(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	status := http.StatusCreated
	if res.Status == http.StatusAccepted {
		status = http.StatusAccepted
	}
	if err == nil && res.Status != http.StatusAccepted {
		h.attributeHomeOrder(r, res.Confirmation)
	}
	h.reply(w, r, status, res.Confirmation, err)
}
