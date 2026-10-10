package app

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AuthService runs the token-mediating backend: OIDC authorization code + PKCE with identity, session rotation
// on sign-in, guest-cart merge, sign-out with revocation, sign-up and email verification, and /v1/me.
type AuthService interface {
	// AuthorizeURL starts hosted (provider "") or social sign-in and returns identity's /oauth2/authorize URL.
	AuthorizeURL(ctx context.Context, c *domain.Caller, provider, returnTo, prompt string) (string, error)
	// Callback exchanges the code, rotates the session and returns where to send the browser.
	Callback(ctx context.Context, c *domain.Caller, code, state, oauthError string) string
	SignOut(ctx context.Context, c *domain.Caller) error
	Me(ctx context.Context, c *domain.Caller) (domain.Me, error)
	SignUp(ctx context.Context, c *domain.Caller, req SignUpRequest) (domain.AuthResult, error)
	VerifyEmail(ctx context.Context, c *domain.Caller, challengeID, code string) (domain.AuthResult, error)
	ResendEmailCode(ctx context.Context, challengeID string) (int, error)
	// FirstPartyCredentials covers password sign-in, MFA verification and passkeys, which identity only offers on
	// its hosted pages today (no JSON API), so the BFF answers 503 and the storefront falls back to hosted login.
	FirstPartyCredentials(ctx context.Context) error
}

// SignUpRequest is the storefront sign-up form.
type SignUpRequest struct {
	FirstName     string `json:"firstName"`
	LastName      string `json:"lastName"`
	Email         string `json:"email"`
	Phone         string `json:"phone"`
	Password      string `json:"password"`
	TermsAccepted bool   `json:"termsAccepted"`
	SMSConsent    bool   `json:"smsConsent"`
	ReturnTo      string `json:"returnTo"`
}

// AuthOptions configure sign-in.
type AuthOptions struct {
	IdentityPublicURL string // browser-facing identity base (authorize endpoint)
	ClientID          string
	RedirectURI       string
	Scopes            []string
	WebOrigin         string // storefront origin for post-login redirects
	BFFPath           string // storefront path that proxies to the BFF, e.g. /api/bff
	StateTTL          time.Duration
	Clock             Clock
	Log               *slog.Logger
}

type authServiceImpl struct {
	sessions  SessionService
	store     port.SessionStore
	ephemeral port.EphemeralStore
	identity  port.IdentityClient
	verifier  port.TokenVerifier
	cart      port.CartClient
	payments  port.PaymentsClient
	o         AuthOptions
}

var _ AuthService = (*authServiceImpl)(nil)

// NewAuthService returns the AuthService.
func NewAuthService(sessions SessionService, store port.SessionStore, ephemeral port.EphemeralStore, identity port.IdentityClient,
	verifier port.TokenVerifier, cart port.CartClient, payments port.PaymentsClient, o AuthOptions) AuthService {
	if o.StateTTL <= 0 {
		o.StateTTL = 10 * time.Minute
	}
	if o.Log == nil {
		o.Log = slog.Default()
	}
	if len(o.Scopes) == 0 {
		o.Scopes = []string{"openid", "email", "profile", "offline_access"}
	}
	return &authServiceImpl{sessions: sessions, store: store, ephemeral: ephemeral, identity: identity, verifier: verifier,
		cart: cart, payments: payments, o: o}
}

// Social providers the contract allows (IdentityProviderId).
var socialProviders = map[string]bool{"google": true, "apple": true, "facebook": true}

func (a *authServiceImpl) AuthorizeURL(ctx context.Context, c *domain.Caller, provider, returnTo, prompt string) (string, error) {
	if provider != "" {
		if !socialProviders[provider] {
			return "", domain.NewProblem(http.StatusNotFound, domain.CodeProviderDisabled, "Sign-in option unavailable", provider+" sign-in is not enabled.")
		}
		providers, err := a.identity.Providers(ctx)
		if err != nil {
			return "", mapErr("identity", err)
		}
		enabled := false
		for _, p := range providers {
			if strings.EqualFold(p.ID, provider) {
				enabled = true
			}
		}
		if !enabled {
			return "", domain.NewProblem(http.StatusNotFound, domain.CodeProviderDisabled, "Sign-in option unavailable", provider+" sign-in is not enabled.")
		}
	}
	verifier, state, nonce := randomID(48), randomID(32), randomID(24)
	req := port.AuthRequest{Verifier: verifier, Nonce: nonce, ReturnTo: domain.SafeReturnTo(returnTo), Provider: provider}
	if c.Session != nil && !c.App {
		req.SessionID = c.Session.ID
	}
	if err := a.ephemeral.PutAuthRequest(ctx, state, req, a.o.StateTTL); err != nil {
		return "", domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	sum := sha256.Sum256([]byte(verifier))
	q := url.Values{
		"response_type":         {"code"},
		"client_id":             {a.o.ClientID},
		"redirect_uri":          {a.o.RedirectURI},
		"scope":                 {strings.Join(a.o.Scopes, " ")},
		"code_challenge":        {base64.RawURLEncoding.EncodeToString(sum[:])},
		"code_challenge_method": {"S256"},
		"state":                 {state},
		"nonce":                 {nonce},
	}
	if provider != "" {
		q.Set("idp_hint", provider)
	}
	if prompt == "login" || prompt == "create" {
		q.Set("prompt", prompt)
	}
	return strings.TrimSuffix(a.o.IdentityPublicURL, "/") + "/oauth2/authorize?" + q.Encode(), nil
}

func (a *authServiceImpl) signinError(reason string) string {
	return strings.TrimSuffix(a.o.WebOrigin, "/") + "/signin?error=" + url.QueryEscape(reason)
}

func (a *authServiceImpl) Callback(ctx context.Context, c *domain.Caller, code, state, oauthError string) string {
	req, err := a.ephemeral.TakeAuthRequest(ctx, state)
	if err != nil || req == nil {
		return a.signinError("expired")
	}
	if oauthError != "" {
		return a.signinError(oauthError)
	}
	// Bind the flow to the browser that started it: a different signed session means a login-CSRF attempt.
	if req.SessionID != "" && c.Session != nil && !c.App && c.Session.ID != req.SessionID {
		return a.signinError("state_mismatch")
	}
	if code == "" {
		return a.signinError("missing_code")
	}
	tokens, err := a.identity.ExchangeCode(ctx, code, req.Verifier, a.o.RedirectURI)
	if err != nil {
		a.o.Log.WarnContext(ctx, "authorization code exchange failed", "error", err)
		return a.signinError("exchange_failed")
	}
	profile, err := a.verifier.VerifyIDToken(ctx, tokens.IDToken, req.Nonce)
	if err != nil {
		a.o.Log.WarnContext(ctx, "id token rejected", "error", err)
		return a.signinError("invalid_token")
	}
	// Rotate the session id: carry guest state over to a fresh session and delete the old one.
	old := c.Session
	if old == nil && req.SessionID != "" {
		old, _ = a.store.Load(ctx, "web:"+req.SessionID)
	}
	next := &domain.Session{ID: randomID(24), CreatedAt: a.o.Clock.now(), Tokens: &tokens, Profile: &profile}
	if old != nil {
		next.ZIP, next.GuestCartID = old.ZIP, old.GuestCartID
	}
	if next.GuestCartID != "" {
		if _, err := a.cart.Merge(ctx, port.CartOwner{BearerToken: tokens.AccessToken, GuestID: next.GuestCartID}); err == nil {
			next.GuestCartID = ""
		} else {
			a.o.Log.InfoContext(ctx, "guest cart not merged; keeping it on the session", "error", err)
		}
	}
	if old != nil {
		_ = a.store.Delete(ctx, "web:"+old.ID)
	}
	c.App = false
	c.Session = next
	c.SessionRotated = true
	if err := a.sessions.Save(ctx, c); err != nil {
		return a.signinError("session_unavailable")
	}
	return strings.TrimSuffix(a.o.WebOrigin, "/") + req.ReturnTo
}

func (a *authServiceImpl) SignOut(ctx context.Context, c *domain.Caller) error {
	if c.Session != nil && c.Session.Tokens != nil {
		if rt := c.Session.Tokens.RefreshToken; rt != "" {
			if err := a.identity.Revoke(ctx, rt, "refresh_token"); err != nil {
				a.o.Log.WarnContext(ctx, "refresh token revocation failed", "error", err)
			}
		}
		if at := c.Session.Tokens.AccessToken; at != "" {
			_ = a.identity.Revoke(ctx, at, "access_token")
		}
	}
	if c.App {
		return nil
	}
	if err := a.sessions.Delete(ctx, c); err != nil {
		return fmt.Errorf("sign out: %w", err)
	}
	c.Session = nil
	c.BearerToken, c.Subject = "", ""
	c.SessionEnded = true
	return nil
}

func (a *authServiceImpl) Me(ctx context.Context, c *domain.Caller) (domain.Me, error) {
	if !c.Authenticated() {
		return domain.Me{Authenticated: false}, nil
	}
	user := domain.MeUser{ID: c.Subject, Roles: []string{}}
	if c.Session != nil && c.Session.Profile != nil {
		p := c.Session.Profile
		user = domain.MeUser{ID: p.Subject, FirstName: p.FirstName, LastName: p.LastName, Email: p.Email, Roles: p.Roles}
	}
	me, err := a.identity.Me(ctx, c.BearerToken)
	switch {
	case err == nil:
		user = domain.MeUser{ID: firstNonEmpty(me.ID, c.Subject), FirstName: me.FirstName, LastName: me.LastName, Email: me.Email, Roles: me.Roles}
	case isUnauthorized(err):
		return domain.Me{Authenticated: false}, nil
	default:
		a.o.Log.InfoContext(ctx, "identity /v1/me unavailable; using the ID token profile", "error", err)
	}
	if user.Roles == nil {
		user.Roles = []string{}
	}
	user.Initials = domain.Profile{FirstName: user.FirstName, LastName: user.LastName}.Initials()
	counts := domain.MeCounts{}
	if addrs, err := a.identity.Addresses(ctx, c.BearerToken); err == nil {
		counts.Addresses = len(addrs)
	}
	if pms, err := a.payments.PaymentMethods(ctx, c.BearerToken); err == nil {
		counts.PaymentMethods = len(pms)
	}
	return domain.Me{Authenticated: true, User: &user, Counts: &counts}, nil
}

func isUnauthorized(err error) bool {
	p, ok := port.AsUpstreamProblem(err)
	return ok && p.Status == http.StatusUnauthorized
}

// hostedLogin is where the storefront sends the browser after sign-up: identity's hosted login (PKCE via the BFF).
func (a *authServiceImpl) hostedLogin(returnTo string) string {
	return strings.TrimSuffix(a.o.BFFPath, "/") + "/v1/auth/login?" + url.Values{"returnTo": {domain.SafeReturnTo(returnTo)}}.Encode()
}

func maskEmail(e string) string {
	local, dom, ok := strings.Cut(e, "@")
	if !ok || local == "" {
		return e
	}
	r := []rune(local)
	return string(r[0]) + strings.Repeat("•", max(1, min(6, len(r)-1))) + "@" + dom
}

const resendAfterSeconds = 30

func (a *authServiceImpl) SignUp(ctx context.Context, _ *domain.Caller, req SignUpRequest) (domain.AuthResult, error) {
	email := strings.ToLower(strings.TrimSpace(req.Email))
	err := a.identity.SignUp(ctx, port.IdentitySignUp{FirstName: strings.TrimSpace(req.FirstName), LastName: strings.TrimSpace(req.LastName),
		Email: email, Phone: req.Phone, Password: req.Password, ConfirmPassword: req.Password, TermsAccepted: req.TermsAccepted,
		SMSConsent: req.SMSConsent, Intent: "shop"})
	if err != nil {
		return domain.AuthResult{}, mapIdentityErr(err)
	}
	id := randomID(18)
	if err := a.ephemeral.PutChallenge(ctx, id, port.Challenge{Email: email, ReturnTo: domain.SafeReturnTo(req.ReturnTo)}, 15*time.Minute); err != nil {
		return domain.AuthResult{}, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	return domain.AuthResult{Status: "verification_required", ChallengeID: id, Destination: maskEmail(email), ResendAfterSeconds: ptr(resendAfterSeconds)}, nil
}

func (a *authServiceImpl) challenge(ctx context.Context, id string) (*port.Challenge, error) {
	ch, err := a.ephemeral.GetChallenge(ctx, id)
	if err != nil {
		return nil, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	if ch == nil {
		return nil, domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Verification expired", "That code has expired. Start again.")
	}
	return ch, nil
}

// VerifyEmail confirms the address with identity. Identity issues tokens only through its authorization endpoint,
// so the customer finishes signing in on the hosted login (redirectTo) with the account just verified.
func (a *authServiceImpl) VerifyEmail(ctx context.Context, _ *domain.Caller, challengeID, code string) (domain.AuthResult, error) {
	ch, err := a.challenge(ctx, challengeID)
	if err != nil {
		return domain.AuthResult{}, err
	}
	if err := a.identity.VerifyEmail(ctx, ch.Email, code); err != nil {
		return domain.AuthResult{}, mapIdentityErr(err)
	}
	_ = a.ephemeral.DeleteChallenge(ctx, challengeID)
	return domain.AuthResult{Status: "authenticated", RedirectTo: a.hostedLogin(ch.ReturnTo)}, nil
}

func (a *authServiceImpl) ResendEmailCode(ctx context.Context, challengeID string) (int, error) {
	ch, err := a.challenge(ctx, challengeID)
	if err != nil {
		return 0, err
	}
	if err := a.identity.ResendCode(ctx, ch.Email); err != nil {
		return 0, mapIdentityErr(err)
	}
	return resendAfterSeconds, nil
}

func (a *authServiceImpl) FirstPartyCredentials(context.Context) error {
	p := domain.UpstreamUnavailable("identity")
	p.Detail = "Sign in with your password, a code or a passkey on the AfroTransact sign-in page."
	return p.WithCause(errors.New("identity has no first-party credential API; use GET /v1/auth/login"))
}

// mapIdentityErr maps identity's problem codes onto the contract's.
func mapIdentityErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("identity", err)
	}
	switch p.Code {
	case "identity.email-taken":
		pr := domain.NewProblem(http.StatusConflict, domain.CodeEmailTaken, "Account exists", "An account with this email already exists. Sign in instead.")
		pr.Errors = map[string]string{"email": "An account with this email already exists"}
		return pr.WithCause(err)
	case "identity.invalid-sign-up", "identity.staff-email", "identity.invalid-input":
		if msg, ok := p.Fields["password"]; ok {
			return domain.FieldProblem(domain.CodeWeakPassword, "Password too weak", "password", msg).WithCause(err)
		}
		if len(p.Fields) > 0 {
			return domain.ValidationProblem(p.Fields).WithCause(err)
		}
		return domain.ValidationProblem(map[string]string{"_": firstNonEmpty(p.Detail, "One or more fields are invalid.")}).WithCause(err)
	case "identity.invalid-code":
		return domain.FieldProblem(domain.CodeInvalidCode, "Incorrect code", "code", "That code is incorrect or expired.").WithCause(err)
	case "identity.code-expired", "identity.code-not-found", "identity.code-used", "identity.too-many-attempts", "identity.already-verified":
		return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Verification expired", "That code has expired. Start again.").WithCause(err)
	case "identity.resend-too-soon", "identity.too-many-codes":
		pr := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many requests", firstNonEmpty(p.Detail, "Please wait before requesting another code."))
		pr.RetryAfter = max(1, p.RetryAfter)
		return pr.WithCause(err)
	}
	return mapErr("identity", err)
}
