package httpapi

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ShopperServices are the storefront's account, checkout, services and Lola use cases (S047, S048, S135, S148,
// S150, S238). When set they also serve the account routes that used to point at planned upstream paths.
type ShopperServices struct {
	Wallet   app.WalletService
	Orders   app.CustomerOrdersService
	Checkout app.CheckoutPaymentService
	Returns  app.ReturnsService
	Security app.SecurityService
	// Settings is the profile, display preferences, back-office alerts and leaving an organisation (every app).
	Settings app.AccountSettingsService
	Services app.ServicesService
	Lola     app.LolaService
	// SearchClicks reports result clicks to search (optional).
	SearchClicks app.SearchClickService
	// PayLinks serves the pay-now page of late pre-order adds (optional; public, the link's token is the credential).
	PayLinks app.PayLinkService
	// BusinessPages serves a business's plain public page (optional; public, read-only).
	BusinessPages app.BusinessPageService
	// Credit is AfroTransact credit and gift cards (S320, S321; optional).
	Credit app.CreditService
	// Membership is the shopper's membership: tiers, joining, changing plan, canceling (S323; optional).
	Membership app.MembershipService
}

func (s ShopperServices) enabled() bool { return s.Wallet != nil }

func (h *handlersImpl) registerShopper(route func(pattern string, fn http.HandlerFunc)) {
	if !h.s.Shopper.enabled() {
		return
	}
	auth := func(fn http.HandlerFunc) http.HandlerFunc { return h.rl.wrap("auth", h.o.AuthPerMinute, fn) }
	if h.s.Shopper.SearchClicks != nil {
		route("POST /v1/search/clicks", h.searchClick)
	}
	// Payments & PaymentSheet
	route("POST /v1/account/payment-methods/{paymentMethodId}/default", h.defaultPaymentMethod)
	route("POST /v1/account/payment-sheet", h.paymentSheet)
	// Orders & tracking
	route("GET /v1/account/orders/{orderNumber}", h.orderDetail)
	route("GET /v1/account/orders/{orderNumber}/tracking", h.orderTracking)
	route("GET /v1/account/orders/{orderNumber}/returnable", h.returnable)
	// Returns
	route("GET /v1/account/returns/{rmaId}", h.returnDetail)
	route("POST /v1/account/returns/{rmaId}/cancel", h.cancelReturn)
	if h.s.Shopper.BusinessPages != nil {
		route("GET /v1/business-pages/{slug}", h.businessPage)
	}
	if h.s.Shopper.Credit != nil {
		h.registerCredit(route)
	}
	if h.s.Shopper.Membership != nil {
		h.registerMembership(route)
	}
	// Pay-now page of late pre-order adds (no session: the signed token is the credential; rate-limited per IP)
	if h.s.Shopper.PayLinks != nil {
		lookup := func(fn http.HandlerFunc) http.HandlerFunc { return h.rl.wrap("lookup", h.o.LookupPerMinute, fn) }
		route("POST /v1/pay-links/{orderNumber}/lookup", lookup(h.payLinkLookup))
		route("POST /v1/pay-links/{orderNumber}/confirm", lookup(h.payLinkConfirm))
	}
	// Checkout payment
	route("GET /v1/checkout/orders/{orderNumber}", h.checkoutStatus)
	route("POST /v1/checkout/orders/{orderNumber}/confirm", h.confirmCheckout)
	// Login & security
	route("GET /v1/account/security", h.security)
	route("PUT /v1/account/password", auth(h.changePassword))
	route("POST /v1/account/mfa/totp", h.startTOTP)
	route("POST /v1/account/mfa/totp/confirm", auth(h.confirmTOTP))
	route("DELETE /v1/account/mfa/totp", h.disableTOTP)
	route("POST /v1/account/mfa/sms", auth(h.startSMS))
	route("POST /v1/account/mfa/sms/confirm", auth(h.confirmSMS))
	route("DELETE /v1/account/mfa/sms", h.disableSMS)
	route("POST /v1/account/mfa/recovery-codes", auth(h.recoveryCodes))
	route("DELETE /v1/account/passkeys/{credentialId}", h.removePasskey)
	route("PATCH /v1/account/passkeys/{credentialId}", h.renamePasskey)
	route("DELETE /v1/account/trusted-browsers", h.forgetTrustedBrowsers)
	route("DELETE /v1/account/trusted-browsers/{browserId}", h.forgetTrustedBrowser)
	route("GET /v1/account/sign-in-activity", h.signInActivity)
	route("DELETE /v1/account/sessions/{sessionId}", h.revokeSession)
	route("POST /v1/account/sessions/revoke-others", h.revokeOtherSessions)
	route("DELETE /v1/account/devices/{deviceId}", h.removeDevice)
	route("DELETE /v1/account/linked-accounts/{provider}", h.unlink)
	route("GET /v1/account/notification-preferences", h.preferences)
	route("PUT /v1/account/notification-preferences", h.savePreferences)
	if h.s.Shopper.Settings != nil {
		h.registerAccountSettings(route)
	}
	// Services & bookings
	route("GET /v1/services/search", h.searchServices)
	route("GET /v1/service-providers/{orgId}", h.providerPage)
	route("GET /v1/services/{serviceId}", h.serviceDetail)
	route("GET /v1/services/{serviceId}/days", h.serviceDays)
	route("GET /v1/services/{serviceId}/slots", h.serviceSlots)
	route("GET /v1/services/{serviceId}/summary", h.serviceQuote)
	route("POST /v1/services/bookings", h.book)
	route("GET /v1/account/bookings", h.myBookings)
	route("GET /v1/account/bookings/{bookingId}", h.myBooking)
	route("GET /v1/account/bookings/{bookingId}/cancellation-quote", h.bookingQuote)
	route("POST /v1/account/bookings/{bookingId}/cancel", h.cancelBooking)
	route("POST /v1/account/bookings/{bookingId}/reschedule", h.rescheduleBooking)
	route("POST /v1/account/bookings/{bookingId}/new-time/{decision}", h.answerNewTime)
	route("POST /v1/account/bookings/{bookingId}/review", h.reviewBooking)
	route("POST /v1/account/bookings/{bookingId}/payment/sync", h.syncBookingPayment)
	// Lola
	// SSE: exempt from the shared server's write timeout (a Lola turn outlasts it).
	route("POST /v1/lola/chat", httpserver.Stream(http.HandlerFunc(h.lolaChat)).ServeHTTP)
	route("GET /v1/lola/sessions/{sessionId}/messages", h.lolaHistory)
}

func (h *handlersImpl) ok(w http.ResponseWriter, r *http.Request, v any, err error) {
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- payments

func (h *handlersImpl) defaultPaymentMethod(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Wallet.MakeDefault(r.Context(), callerFrom(r.Context()), r.PathValue("paymentMethodId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) paymentSheet(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Wallet.PaymentSheet(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusCreated, v, err)
}

// ---------------------------------------------------------------- orders, tracking, returns

func (h *handlersImpl) orderDetail(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Orders.Detail(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) orderTracking(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Orders.Tracking(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) returnable(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Returns.Returnable(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) returnDetail(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Returns.Detail(r.Context(), callerFrom(r.Context()), r.PathValue("rmaId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) cancelReturn(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Returns.Cancel(r.Context(), callerFrom(r.Context()), r.PathValue("rmaId"))
	h.ok(w, r, v, err)
}

// ---------------------------------------------------------------- pay-now links

type payLinkBody struct {
	Token string `json:"token"`
}

func (h *handlersImpl) businessPage(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.BusinessPages.Page(r.Context(), r.PathValue("slug"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) payLinkLookup(w http.ResponseWriter, r *http.Request) {
	var b payLinkBody
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.PayLinks.Lookup(r.Context(), r.PathValue("orderNumber"), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) payLinkConfirm(w http.ResponseWriter, r *http.Request) {
	var b payLinkBody
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.PayLinks.Confirm(r.Context(), r.PathValue("orderNumber"), b.Token)
	h.reply(w, r, http.StatusOK, v, err)
}

// ---------------------------------------------------------------- checkout

func (h *handlersImpl) checkoutStatus(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Checkout.Status(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) confirmCheckout(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Checkout.Confirm(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"))
	h.ok(w, r, v, err)
}

// ---------------------------------------------------------------- login & security

func (h *handlersImpl) security(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Security.Overview(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) changePassword(w http.ResponseWriter, r *http.Request) {
	var b domain.PasswordChange
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.ChangePassword(r.Context(), callerFrom(r.Context()), b))
}

func codeBody(r *http.Request) (string, error) {
	var b struct {
		Code string `json:"code"`
	}
	err := decode(r, &b, false)
	return b.Code, err
}

func (h *handlersImpl) startTOTP(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Security.StartTOTP(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) confirmTOTP(w http.ResponseWriter, r *http.Request) {
	code, err := codeBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Security.ConfirmTOTP(r.Context(), callerFrom(r.Context()), code)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) disableTOTP(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.DisableTOTP(r.Context(), callerFrom(r.Context())))
}

func (h *handlersImpl) startSMS(w http.ResponseWriter, r *http.Request) {
	at, err := h.s.Shopper.Security.StartSMS(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusAccepted, map[string]any{"codeExpiresAt": optional(at)}, err)
}

func optional(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func (h *handlersImpl) confirmSMS(w http.ResponseWriter, r *http.Request) {
	code, err := codeBody(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Security.ConfirmSMS(r.Context(), callerFrom(r.Context()), code)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) disableSMS(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.DisableSMS(r.Context(), callerFrom(r.Context())))
}

func (h *handlersImpl) recoveryCodes(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Security.RecoveryCodes(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) removePasskey(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.RemovePasskey(r.Context(), callerFrom(r.Context()), r.PathValue("credentialId")))
}

func (h *handlersImpl) renamePasskey(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Label string `json:"label"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Security.RenamePasskey(r.Context(), callerFrom(r.Context()), r.PathValue("credentialId"), b.Label)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) forgetTrustedBrowser(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.ForgetTrustedBrowser(r.Context(), callerFrom(r.Context()), r.PathValue("browserId")))
}

func (h *handlersImpl) forgetTrustedBrowsers(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.ForgetTrustedBrowsers(r.Context(), callerFrom(r.Context())))
}

func (h *handlersImpl) signInActivity(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Security.SignInActivity(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, map[string]any{"items": v}, err)
}

func (h *handlersImpl) revokeSession(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.RevokeSession(r.Context(), callerFrom(r.Context()), r.PathValue("sessionId")))
}

func (h *handlersImpl) revokeOtherSessions(w http.ResponseWriter, r *http.Request) {
	n, err := h.s.Shopper.Security.RevokeOtherSessions(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, map[string]int{"revoked": n}, err)
}

func (h *handlersImpl) removeDevice(w http.ResponseWriter, r *http.Request) {
	h.reply(w, r, http.StatusNoContent, nil, h.s.Shopper.Security.RemoveDevice(r.Context(), callerFrom(r.Context()), r.PathValue("deviceId")))
}

func (h *handlersImpl) unlink(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Security.Unlink(r.Context(), callerFrom(r.Context()), r.PathValue("provider"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) preferences(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Security.Preferences(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) savePreferences(w http.ResponseWriter, r *http.Request) {
	var b domain.NotificationPreferences
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Security.SavePreferences(r.Context(), callerFrom(r.Context()), b)
	h.ok(w, r, v, err)
}

// ---------------------------------------------------------------- services & bookings

func slotQueryOf(r *http.Request) (port.SlotQuery, error) {
	v := r.URL.Query()
	q := port.SlotQuery{From: v.Get("from"), Date: v.Get("date"), MemberID: v.Get("memberId"), Mode: v.Get("mode")}
	if s := v.Get("addOnIds"); s != "" {
		q.AddOnIDs = strings.Split(s, ",")
	}
	if s := v.Get("days"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 1 {
			return q, domain.ValidationProblem(map[string]string{"days": "Between 1 and 62"})
		}
		q.Days = n
	}
	return q, nil
}

func (h *handlersImpl) searchServices(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	v, err := h.s.Shopper.Services.Search(r.Context(), callerFrom(r.Context()), q.Get("q"), q.Get("category"), q.Get("zip"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) providerPage(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Services.Provider(r.Context(), r.PathValue("orgId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) serviceDetail(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Services.Service(r.Context(), r.PathValue("serviceId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) serviceDays(w http.ResponseWriter, r *http.Request) {
	q, err := slotQueryOf(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Days(r.Context(), r.PathValue("serviceId"), q)
	h.ok(w, r, map[string]any{"days": v}, err)
}

func (h *handlersImpl) serviceSlots(w http.ResponseWriter, r *http.Request) {
	q, err := slotQueryOf(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Slots(r.Context(), r.PathValue("serviceId"), q)
	h.ok(w, r, map[string]any{"slots": v}, err)
}

func (h *handlersImpl) serviceQuote(w http.ResponseWriter, r *http.Request) {
	q, err := slotQueryOf(r)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Quote(r.Context(), r.PathValue("serviceId"), q)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) book(w http.ResponseWriter, r *http.Request) {
	var b domain.BookingRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Book(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) myBookings(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Services.Mine(r.Context(), callerFrom(r.Context()))
	h.ok(w, r, map[string]any{"items": v}, err)
}

func (h *handlersImpl) myBooking(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Services.Booking(r.Context(), callerFrom(r.Context()), r.PathValue("bookingId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) bookingQuote(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Services.CancellationQuote(r.Context(), callerFrom(r.Context()), r.PathValue("bookingId"))
	h.ok(w, r, v, err)
}

func (h *handlersImpl) cancelBooking(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Reason string `json:"reason"`
	}
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Cancel(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), r.PathValue("bookingId"), b.Reason)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) rescheduleBooking(w http.ResponseWriter, r *http.Request) {
	var b struct {
		StartAt string `json:"startAt"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Reschedule(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), r.PathValue("bookingId"), b.StartAt)
	h.ok(w, r, v, err)
}

func (h *handlersImpl) answerNewTime(w http.ResponseWriter, r *http.Request) {
	d := r.PathValue("decision")
	if d != "accept" && d != "decline" {
		h.reply(w, r, 0, nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "Accept or decline the new time."))
		return
	}
	v, err := h.s.Shopper.Services.AnswerNewTime(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), r.PathValue("bookingId"), d == "accept")
	h.ok(w, r, v, err)
}

func (h *handlersImpl) reviewBooking(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Rating int    `json:"rating"`
		Text   string `json:"text"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.Services.Review(r.Context(), callerFrom(r.Context()), r.PathValue("bookingId"), b.Rating, b.Text)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) syncBookingPayment(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Services.SyncPayment(r.Context(), callerFrom(r.Context()), r.PathValue("bookingId"))
	h.ok(w, r, v, err)
}

// ---------------------------------------------------------------- Lola (SSE relay)

func (h *handlersImpl) lolaChat(w http.ResponseWriter, r *http.Request) {
	var b domain.LolaChatRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	c := callerFrom(r.Context())
	stream, err := h.s.Shopper.Lola.Chat(r.Context(), c, b)
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	defer func() { _ = stream.Close() }()
	h.finish(w, c)
	hd := w.Header()
	hd.Set("Content-Type", "text/event-stream")
	hd.Set("Cache-Control", "no-cache, no-transform")
	hd.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	rc := http.NewResponseController(w)
	buf := make([]byte, 8<<10)
	for {
		n, rerr := stream.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return // the shopper went away; closing the stream cancels the upstream turn
			}
			_ = rc.Flush()
		}
		if rerr != nil {
			if !errors.Is(rerr, io.EOF) && r.Context().Err() == nil {
				h.log.WarnContext(r.Context(), "lola stream interrupted", "error", rerr)
				_, _ = io.WriteString(w, "event: error\ndata: {\"code\":\"UPSTREAM_UNAVAILABLE\",\"detail\":\"Lola stopped answering. Please try again.\"}\n\n")
				_ = rc.Flush()
			}
			return
		}
	}
}

func (h *handlersImpl) lolaHistory(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Shopper.Lola.History(r.Context(), callerFrom(r.Context()), r.PathValue("sessionId"))
	h.ok(w, r, map[string]any{"items": v}, err)
}

func (h *handlersImpl) searchClick(w http.ResponseWriter, r *http.Request) {
	var body domain.SearchClick
	if err := decode(r, &body, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Shopper.SearchClicks.Record(r.Context(), callerFrom(r.Context()), body)
	h.reply(w, r, http.StatusAccepted, v, err)
}
