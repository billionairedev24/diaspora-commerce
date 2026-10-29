package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ---------------------------------------------------------------- native app sign-in (customer and driver apps)

// nativeRequest is the part every native sign-in body shares.
type nativeRequest struct {
	App           string `json:"app"`
	CodeChallenge string `json:"codeChallenge"`
	RedirectURI   string `json:"redirectUri"`
}

func (h *handlersImpl) nativeCtx(r *http.Request, b nativeRequest) app.NativeRequest {
	return app.NativeRequest{App: b.App, CodeChallenge: b.CodeChallenge, RedirectURI: b.RedirectURI,
		IP: callerFrom(r.Context()).ClientIP, UserAgent: r.UserAgent()}
}

func (h *handlersImpl) nativePassword(w http.ResponseWriter, r *http.Request) {
	var b struct {
		nativeRequest
		Email              string `json:"email"`
		Password           string `json:"password"`
		TrustedDeviceToken string `json:"trustedDeviceToken"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	if err := h.throttleEmail(r, b.Email); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Native.Password(r.Context(), app.NativePassword{NativeRequest: h.nativeCtx(r, b.nativeRequest), Email: b.Email,
		Password: b.Password, TrustedDeviceToken: b.TrustedDeviceToken})
	h.reply(w, r, http.StatusOK, v, err)
}

func (h *handlersImpl) nativeSMS(w http.ResponseWriter, r *http.Request) {
	var b struct {
		nativeRequest
		ChallengeID string `json:"challengeId"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Native.SendSMSCode(r.Context(), app.NativeSMS{NativeRequest: h.nativeCtx(r, b.nativeRequest), ChallengeID: b.ChallengeID})
	h.reply(w, r, http.StatusAccepted, v, err)
}

func (h *handlersImpl) nativeVerify(w http.ResponseWriter, r *http.Request) {
	var b struct {
		nativeRequest
		ChallengeID string `json:"challengeId"`
		Factor      string `json:"factor"`
		Code        string `json:"code"`
		TrustDevice bool   `json:"trustDevice"`
	}
	if err := decode(r, &b, false); err != nil {
		h.reply(w, r, 0, nil, err)
		return
	}
	v, err := h.s.Native.VerifyFactor(r.Context(), app.NativeFactor{NativeRequest: h.nativeCtx(r, b.nativeRequest),
		ChallengeID: b.ChallengeID, Factor: b.Factor, Code: b.Code, TrustDevice: b.TrustDevice})
	h.reply(w, r, http.StatusOK, v, err)
}

// Attempts per email within nativeEmailWindow, whatever the source address. The per-IP limit and identity's account
// lockout still apply; this one holds when an attacker rotates or spoofs IPs, and (unlike the lockout) it does not
// count the real owner's later successful sign-ins against anything.
const (
	nativeEmailAttempts = 10
	nativeEmailWindow   = 15 * time.Minute
)

func (h *handlersImpl) throttleEmail(r *http.Request, email string) error {
	if h.rl.rl == nil || strings.TrimSpace(email) == "" {
		return nil
	}
	sum := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(email))))
	ok, retry, err := h.rl.rl.Allow(r.Context(), "native-email:"+hex.EncodeToString(sum[:16]), nativeEmailAttempts, nativeEmailWindow)
	if err != nil || ok {
		return nil // the limiter being down must not lock everyone out; identity's lockout still protects accounts
	}
	p := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many attempts",
		"Too many sign-in attempts for this account. Try again later, or reset your password.")
	p.RetryAfter = max(1, int(math.Ceil(retry.Seconds())))
	return p
}
