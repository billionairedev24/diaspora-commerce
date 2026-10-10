package httpapi

import (
	"encoding/json"
	"net/http"
	"net/mail"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- auth

func (h *handlersImpl) me(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Auth.Me(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) startLogin(w http.ResponseWriter, r *http.Request, provider string) {
	q := r.URL.Query()
	returnTo := q.Get("returnTo")
	if returnTo == "" {
		returnTo = "/"
	}
	url, err := h.s.Auth.AuthorizeURL(r.Context(), callerFrom(r.Context()), provider, returnTo, q.Get("prompt"))
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

var (
	codeRe  = regexp.MustCompile(`^\d{6}$`)
	phoneRe = regexp.MustCompile(`^\+[1-9]\d{6,14}$`)
)

func emailOK(e string) bool {
	a, err := mail.ParseAddress(e)
	return err == nil && a.Address == e
}

// TrustedDeviceCookie keeps identity's trusted-device token (HttpOnly, 30 days) so the second factor is skipped on
// this browser.
const TrustedDeviceCookie = "at_trusted_device"

// firstPartyReply answers a sign-in step and keeps (or refreshes) the trusted-device cookie.
func (h *handlersImpl) firstPartyReply(w http.ResponseWriter, r *http.Request, res app.FirstPartyResult, err error) {
	if err == nil && res.TrustedDevice != "" {
		maxAge := int(time.Until(res.TrustedDeviceExpiry).Seconds())
		if maxAge <= 0 {
			maxAge = 30 * 24 * 3600
		}
		// HttpOnly + SameSite=Lax; Secure follows SESSION_COOKIE_SECURE (off only for plain-http localhost).
		http.SetCookie(w, &http.Cookie{Name: TrustedDeviceCookie, Value: res.TrustedDevice, Path: "/", Domain: h.o.CookieDomain, //nolint:gosec // see above
			MaxAge: maxAge, HttpOnly: true, Secure: h.o.CookieSecure, SameSite: http.SameSiteLaxMode})
	}
	h.reply(w, r, http.StatusOK, res.Result, err)
}

// signIn validates the form (422 like the contract) and signs in with identity's first-party API: either the
// shopper is signed in (session rotated) or a two-step verification challenge comes back.
func (h *handlersImpl) signIn(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		ReturnTo string `json:"returnTo"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	errs := map[string]string{}
	if !emailOK(strings.TrimSpace(b.Email)) {
		errs["email"] = "Enter a valid email address"
	}
	if b.Password == "" {
		errs["password"] = "Enter your password"
	}
	if len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	trusted := ""
	if ck, err := r.Cookie(TrustedDeviceCookie); err == nil {
		trusted = ck.Value
	}
	res, err := h.s.Auth.SignIn(r.Context(), callerFrom(r.Context()), app.SignInInput{Email: b.Email, Password: b.Password,
		ReturnTo: b.ReturnTo, UserAgent: r.UserAgent(), TrustedDevice: trusted})
	h.firstPartyReply(w, r, res, err)
}

type verifyCode struct {
	ChallengeID string `json:"challengeId"`
	Code        string `json:"code"`
	Method      string `json:"method"`
	TrustDevice bool   `json:"trustDevice"`
}

var recoveryRe = regexp.MustCompile(`^[A-Za-z0-9-]{8,20}$`)

func (v verifyCode) problems() map[string]string {
	errs := map[string]string{}
	if v.ChallengeID == "" {
		errs["challengeId"] = "Required"
	}
	switch {
	case v.Method == "recovery":
		if !recoveryRe.MatchString(v.Code) {
			errs["code"] = "Enter one of your recovery codes"
		}
	case !codeRe.MatchString(v.Code):
		errs["code"] = "Enter the 6-digit code"
	}
	if v.Method != "" && v.Method != "totp" && v.Method != "sms" && v.Method != "recovery" {
		errs["method"] = "Choose totp, sms or recovery"
	}
	return errs
}

func (h *handlersImpl) verifyMfa(w http.ResponseWriter, r *http.Request) {
	var b verifyCode
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if errs := b.problems(); len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	res, err := h.s.Auth.VerifyMFA(r.Context(), callerFrom(r.Context()), app.VerifyMFAInput{ChallengeID: b.ChallengeID, Code: b.Code,
		Method: b.Method, TrustDevice: b.TrustDevice, UserAgent: r.UserAgent()})
	h.firstPartyReply(w, r, res, err)
}

// sendMfaCode texts the sign-in code (the shopper chose SMS on the two-step form).
func (h *handlersImpl) sendMfaCode(w http.ResponseWriter, r *http.Request) {
	var b verifyCode
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if b.ChallengeID == "" {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"challengeId": "Required"}))
		return
	}
	v, err := h.s.Auth.SendMFACode(r.Context(), callerFrom(r.Context()), b.ChallengeID)
	h.reply(w, r, http.StatusAccepted, v, err)
}

func (h *handlersImpl) signUp(w http.ResponseWriter, r *http.Request) {
	var b app.SignUpRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	errs := map[string]string{}
	if f := strings.TrimSpace(b.FirstName); f == "" || len(f) > 49 {
		errs["firstName"] = "Enter your first name"
	}
	if l := strings.TrimSpace(b.LastName); l == "" || len(l) > 49 {
		errs["lastName"] = "Enter your last name"
	}
	if !emailOK(strings.TrimSpace(b.Email)) {
		errs["email"] = "Enter a valid email address"
	}
	if !phoneRe.MatchString(b.Phone) {
		errs["phone"] = "Enter a valid mobile number"
	}
	if len(b.Password) < 12 {
		errs["password"] = "Use at least 12 characters"
	}
	if !b.TermsAccepted {
		errs["termsAccepted"] = "Accept the terms to continue"
	}
	if len(b.InvitationToken) > 200 {
		errs["invitationToken"] = "This invitation link is not valid"
	}
	if len(errs) > 0 {
		if _, weak := errs["password"]; weak && len(errs) == 1 {
			h.reply(w, r, 0, nil, domain.FieldProblem(domain.CodeWeakPassword, "Password too weak", "password", errs["password"]))
			return
		}
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	v, err := h.s.Auth.SignUp(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) verifyEmail(w http.ResponseWriter, r *http.Request) {
	var b verifyCode
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if errs := b.problems(); len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	v, err := h.s.Auth.VerifyEmail(r.Context(), callerFrom(r.Context()), b.ChallengeID, b.Code)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) resendEmail(w http.ResponseWriter, r *http.Request) {
	var b verifyCode
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if b.ChallengeID == "" {
		h.reply(w, r, 0, nil, domain.ValidationProblem(map[string]string{"challengeId": "Required"}))
		return
	}
	secs, err := h.s.Auth.ResendEmailCode(r.Context(), b.ChallengeID)
	h.reply(w, r, http.StatusAccepted, map[string]int{"resendAfterSeconds": secs}, err)
}

func (h *handlersImpl) passkeyOptions(w http.ResponseWriter, r *http.Request) {
	var b map[string]json.RawMessage
	if err := decode(r, &b, true); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Auth.PasskeyOptions(r.Context(), callerFrom(r.Context()), r.UserAgent())
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) passkeyVerify(w http.ResponseWriter, r *http.Request) {
	var b struct {
		ChallengeID            string          `json:"challengeId"`
		ID                     string          `json:"id"`
		RawID                  string          `json:"rawId"`
		Type                   string          `json:"type"`
		Response               json.RawMessage `json:"response"`
		AuthenticatorAttach    string          `json:"authenticatorAttachment,omitempty"`
		ClientExtensionResults json.RawMessage `json:"clientExtensionResults,omitempty"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	errs := map[string]string{}
	if b.ChallengeID == "" {
		errs["challengeId"] = "Required"
	}
	if b.ID == "" || b.RawID == "" || len(b.Response) == 0 {
		errs["response"] = "Send the passkey assertion"
	}
	if b.Type != "public-key" {
		errs["type"] = "Must be public-key"
	}
	if len(errs) > 0 {
		h.reply(w, r, 0, nil, domain.ValidationProblem(errs))
		return
	}
	ext := b.ClientExtensionResults
	if len(ext) == 0 {
		ext = json.RawMessage(`{}`)
	}
	credential, err := json.Marshal(map[string]any{"id": b.ID, "rawId": b.RawID, "type": b.Type, "response": b.Response,
		"authenticatorAttachment": firstOf(b.AuthenticatorAttach, "platform"), "clientExtensionResults": ext})
	if err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	res, err := h.s.Auth.PasskeyVerify(r.Context(), callerFrom(r.Context()), b.ChallengeID, credential, r.UserAgent())
	h.firstPartyReply(w, r, res, err)
}

func firstOf(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

func (h *handlersImpl) signOut(w http.ResponseWriter, r *http.Request) {
	err := h.s.Auth.SignOut(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusNoContent, nil, err)
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
	v, err := h.s.Account.PaymentMethods(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) setupIntent(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Account.SetupIntent(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusCreated, v, err)
}

func (h *handlersImpl) deletePaymentMethod(w http.ResponseWriter, r *http.Request) {
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
	v, err := h.s.Account.Orders(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("cursor"), limit)
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) cancelShipment(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Account.CancelShipment(r.Context(), callerFrom(r.Context()), r.PathValue("orderNumber"), r.PathValue("shipmentId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) returns(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Account.Returns(r.Context(), callerFrom(r.Context()))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) startReturn(w http.ResponseWriter, r *http.Request) {
	var b domain.StartReturn
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Account.StartReturn(r.Context(), callerFrom(r.Context()), b)
	h.reply(w, r, http.StatusCreated, v, err)
}

// ---------------------------------------------------------------- checkout

func (h *handlersImpl) checkoutSummary(w http.ResponseWriter, r *http.Request) {
	v, err := h.s.Checkout.Summary(r.Context(), callerFrom(r.Context()), r.URL.Query().Get("addressId"))
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) placeOrder(w http.ResponseWriter, r *http.Request) {
	var b domain.PlaceOrderRequest
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	res, err := h.s.Checkout.PlaceOrder(r.Context(), callerFrom(r.Context()), r.Header.Get("Idempotency-Key"), b)
	status := http.StatusCreated
	if res.Status == http.StatusAccepted {
		status = http.StatusAccepted
	}
	h.reply(w, r, status, res.Confirmation, err)
}
