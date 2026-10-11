package app

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AuthService runs the token-mediating backend for the web apps: OIDC authorization code + PKCE with identity's
// hosted sign-in (the only place credentials are entered, so one identity session signs in every app), session
// rotation on sign-in, guest-cart merge, sign-out with revocation and RP-initiated logout, and /v1/me.
type AuthService interface {
	// AuthorizeURL starts hosted (provider "") or social sign-in and returns identity's /oauth2/authorize URL.
	AuthorizeURL(ctx context.Context, c *domain.Caller, in LoginRequest) (string, error)
	// Callback exchanges the code, rotates the session and returns where to send the browser.
	Callback(ctx context.Context, c *domain.Caller, code, state, oauthError string) string
	// SignOut revokes the tokens and ends the session; it returns identity's end-session URL (RP-initiated logout,
	// back to the origin's home page) for the browser to visit, or "" when there is no identity session to end.
	SignOut(ctx context.Context, c *domain.Caller, origin string) (string, error)
	Me(ctx context.Context, c *domain.Caller) (domain.Me, error)
}

// LoginRequest starts sign-in on identity's hosted pages.
type LoginRequest struct {
	// Provider is "" for identity's own sign-in page, or google | apple | facebook (idp_hint).
	Provider string
	// ReturnTo is a path on the storefront, or an absolute URL on one of the web apps' origins (Seller Center, staff
	// console); anything else becomes "/" on the storefront.
	ReturnTo string
	// Prompt is the OIDC prompt: "login" (sign in again) or "create" (identity's sign-up page); others are dropped.
	Prompt string
	// LoginHint prefills the email on identity's pages (OIDC login_hint).
	LoginHint string
	// Invitation is an organisation invitation token: the account created on identity's sign-up page joins it.
	Invitation string
	// Intent is the Seller Center entry point: "sell" (Start selling) or "provide" (Offer your services). Identity
	// brands its pages for it and adds the applicant role to an account created there; other values are dropped.
	Intent string
}

// AuthOptions configure sign-in.
type AuthOptions struct {
	IdentityPublicURL string // browser-facing identity base (authorize and end-session endpoints)
	ClientID          string
	// RedirectURI is the storefront's callback (the fallback for an origin missing from RedirectURIs).
	RedirectURI string
	// RedirectURIs maps each web app origin to its own callback (<origin><BFFPath>/v1/auth/oidc/callback), so the
	// session cookie is set on the app's own host.
	RedirectURIs map[string]string
	Scopes       []string
	WebOrigin    string // storefront origin: default post-login destination and sign-in error page
	StateTTL     time.Duration
	Clock        Clock
	Log          *slog.Logger
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
	o.WebOrigin = strings.TrimSuffix(o.WebOrigin, "/")
	uris := make(map[string]string, len(o.RedirectURIs))
	for origin, uri := range o.RedirectURIs {
		uris[strings.ToLower(strings.TrimSuffix(origin, "/"))] = uri
	}
	o.RedirectURIs = uris
	return &authServiceImpl{sessions: sessions, store: store, ephemeral: ephemeral, identity: identity, verifier: verifier,
		cart: cart, payments: payments, o: o}
}

// Social providers the contract allows (IdentityProviderId).
var socialProviders = map[string]bool{"google": true, "apple": true, "facebook": true}

const (
	maxLoginHint  = 254
	maxInvitation = 200
)

// appOrigin returns the origin of an absolute URL when it is one of the web apps' (an origin with a registered
// callback), else "".
func (a *authServiceImpl) appOrigin(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil {
		return ""
	}
	origin := strings.ToLower(u.Scheme + "://" + u.Host)
	if _, ok := a.o.RedirectURIs[origin]; ok {
		return origin
	}
	return ""
}

// destination splits returnTo into the app origin and a safe relative path on it.
func (a *authServiceImpl) destination(returnTo string) (origin, path string) {
	if strings.HasPrefix(returnTo, "/") {
		return a.o.WebOrigin, domain.SafeReturnTo(returnTo)
	}
	if origin = a.appOrigin(returnTo); origin == "" {
		return a.o.WebOrigin, "/"
	}
	u, _ := url.Parse(strings.TrimSpace(returnTo)) // parsed by appOrigin
	return origin, domain.SafeReturnTo(firstNonEmpty(u.RequestURI(), "/"))
}

func (a *authServiceImpl) redirectURI(origin string) string {
	if uri, ok := a.o.RedirectURIs[strings.ToLower(origin)]; ok {
		return uri
	}
	return a.o.RedirectURI
}

func (a *authServiceImpl) AuthorizeURL(ctx context.Context, c *domain.Caller, in LoginRequest) (string, error) {
	provider := in.Provider
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
	origin, path := a.destination(in.ReturnTo)
	redirectURI := a.redirectURI(origin)
	verifier, state, nonce := randomID(48), randomID(32), randomID(24)
	req := port.AuthRequest{Verifier: verifier, Nonce: nonce, ReturnTo: path, Provider: provider, Origin: origin, RedirectURI: redirectURI}
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
		"redirect_uri":          {redirectURI},
		"scope":                 {strings.Join(a.o.Scopes, " ")},
		"code_challenge":        {base64.RawURLEncoding.EncodeToString(sum[:])},
		"code_challenge_method": {"S256"},
		"state":                 {state},
		"nonce":                 {nonce},
	}
	if provider != "" {
		q.Set("idp_hint", provider)
	}
	if in.Prompt == "login" || in.Prompt == "create" {
		q.Set("prompt", in.Prompt)
	}
	if hint := strings.TrimSpace(in.LoginHint); hint != "" && len(hint) <= maxLoginHint && emailLike(hint) {
		q.Set("login_hint", hint)
	}
	if inv := strings.TrimSpace(in.Invitation); inv != "" && len(inv) <= maxInvitation {
		q.Set("invitation", inv)
	}
	if in.Intent == "sell" || in.Intent == "provide" {
		q.Set("intent", in.Intent)
	}
	return strings.TrimSuffix(a.o.IdentityPublicURL, "/") + "/oauth2/authorize?" + q.Encode(), nil
}

func emailLike(v string) bool {
	at := strings.Index(v, "@")
	return at > 0 && at < len(v)-1 && !strings.ContainsAny(v, " \r\n")
}

// signinError is the storefront's sign-in error page; it offers to start again towards the original destination.
func (a *authServiceImpl) signinError(reason string, req *port.AuthRequest) string {
	q := url.Values{"error": {reason}}
	if req != nil && req.Origin != "" && !strings.EqualFold(req.Origin, a.o.WebOrigin) {
		q.Set("returnTo", req.Origin+req.ReturnTo)
	} else if req != nil && req.ReturnTo != "" && req.ReturnTo != "/" {
		q.Set("returnTo", req.ReturnTo)
	}
	return a.o.WebOrigin + "/signin?" + q.Encode()
}

func (a *authServiceImpl) Callback(ctx context.Context, c *domain.Caller, code, state, oauthError string) string {
	req, err := a.ephemeral.TakeAuthRequest(ctx, state)
	if err != nil || req == nil {
		return a.signinError("expired", nil)
	}
	if oauthError != "" {
		return a.signinError(oauthError, req)
	}
	// Bind the flow to the browser that started it: a different signed session means a login-CSRF attempt.
	if req.SessionID != "" && c.Session != nil && !c.App && c.Session.ID != req.SessionID {
		return a.signinError("state_mismatch", req)
	}
	if code == "" {
		return a.signinError("missing_code", req)
	}
	if err := a.complete(ctx, c, code, *req); err != nil {
		return a.signinError(err.Error(), req)
	}
	return firstNonEmpty(req.Origin, a.o.WebOrigin) + domain.SafeReturnTo(req.ReturnTo)
}

// signInFailure is a short reason for /signin?error= (never contains tokens).
type signInFailure string

func (f signInFailure) Error() string { return string(f) }

// complete exchanges the code (PKCE), verifies the ID token, rotates the session id (carrying guest ZIP and cart
// over), merges the guest cart and saves the signed-in session.
func (a *authServiceImpl) complete(ctx context.Context, c *domain.Caller, code string, req port.AuthRequest) error {
	tokens, err := a.identity.ExchangeCode(ctx, code, req.Verifier, firstNonEmpty(req.RedirectURI, a.o.RedirectURI))
	if err != nil {
		a.o.Log.WarnContext(ctx, "authorization code exchange failed", "error", err)
		return signInFailure("exchange_failed")
	}
	profile, err := a.verifier.VerifyIDToken(ctx, tokens.IDToken, req.Nonce)
	if err != nil {
		a.o.Log.WarnContext(ctx, "id token rejected", "error", err)
		return signInFailure("invalid_token")
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
	c.BearerToken, c.Subject = tokens.AccessToken, profile.Subject
	if err := a.sessions.Save(ctx, c); err != nil {
		return signInFailure("session_unavailable")
	}
	return nil
}

func (a *authServiceImpl) SignOut(ctx context.Context, c *domain.Caller, origin string) (string, error) {
	idToken := ""
	if c.Session != nil && c.Session.Tokens != nil {
		idToken = c.Session.Tokens.IDToken
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
		return "", nil
	}
	if err := a.sessions.Delete(ctx, c); err != nil {
		return "", fmt.Errorf("sign out: %w", err)
	}
	c.Session = nil
	c.BearerToken, c.Subject = "", ""
	c.SessionEnded = true
	if idToken == "" {
		return "", nil
	}
	// RP-initiated logout ends the identity session too (single sign-out), then identity sends the browser back to
	// the home page of the app that signed out (a post-logout redirect URI registered for gateway-bff).
	home := firstNonEmpty(a.appOrigin(origin), a.o.WebOrigin) + "/"
	q := url.Values{"id_token_hint": {idToken}, "client_id": {a.o.ClientID}, "post_logout_redirect_uri": {home}}
	return strings.TrimSuffix(a.o.IdentityPublicURL, "/") + "/connect/logout?" + q.Encode(), nil
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
		user = domain.MeUser{ID: firstNonEmpty(me.ID, c.Subject), FirstName: me.FirstName, LastName: me.LastName, Email: me.Email, Roles: me.Roles,
			Phone: strPtr(maskPhone(me.Phone)), PhoneVerified: ptr(me.PhoneVerified)}
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

// maskPhone keeps the last four digits (•••• 0142).
func maskPhone(p string) string {
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, p)
	if len(digits) < 4 {
		return ""
	}
	return "•••• " + digits[len(digits)-4:]
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
