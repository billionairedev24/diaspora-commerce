package app

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"log/slog"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ResolveInput is what the HTTP adapter knows about the caller.
type ResolveInput struct {
	CookieValue    string
	BearerToken    string
	AppGuestCartID string
	ClientIP       string
	Language       string
}

// SessionService resolves the caller (cookie session or app bearer), refreshes tokens before they expire and
// persists session state.
type SessionService interface {
	Resolve(ctx context.Context, in ResolveInput) (*domain.Caller, error)
	// Ensure creates the caller's session when it has none (web: new id + cookie; app: per-subject state).
	Ensure(c *domain.Caller)
	// Save persists the caller's session.
	Save(ctx context.Context, c *domain.Caller) error
	// Delete removes the caller's session (sign-out).
	Delete(ctx context.Context, c *domain.Caller) error
	// Cookie returns the signed cookie value for the caller's web session.
	Cookie(c *domain.Caller) string
	// IdleTTL is how long an untouched session lives (cookie Max-Age).
	IdleTTL() time.Duration
	// RefreshNow rotates the web session's tokens now, whatever their expiry, because their claims are stale (identity
	// just added the person to an approved organisation). The caller's bearer token becomes the new access token.
	// False when there is nothing to refresh (an app bearer, a guest, no refresh token) or identity refused.
	RefreshNow(ctx context.Context, c *domain.Caller) bool
}

// SessionOptions configure sessions.
type SessionOptions struct {
	SigningKey   string
	IdleTTL      time.Duration
	RefreshSkew  time.Duration
	VerifyBearer bool
	Clock        Clock
	Log          *slog.Logger
}

type sessionServiceImpl struct {
	store    port.SessionStore
	identity port.IdentityClient
	verifier port.TokenVerifier
	o        SessionOptions
}

var _ SessionService = (*sessionServiceImpl)(nil)

// NewSessionService returns the SessionService.
func NewSessionService(store port.SessionStore, identity port.IdentityClient, verifier port.TokenVerifier, o SessionOptions) SessionService {
	if o.IdleTTL <= 0 {
		o.IdleTTL = 30 * 24 * time.Hour
	}
	if o.RefreshSkew <= 0 {
		o.RefreshSkew = 30 * time.Second
	}
	if o.Log == nil {
		o.Log = slog.Default()
	}
	return &sessionServiceImpl{store: store, identity: identity, verifier: verifier, o: o}
}

func (s *sessionServiceImpl) IdleTTL() time.Duration { return s.o.IdleTTL }

func (s *sessionServiceImpl) sign(id string) string {
	m := hmac.New(sha256.New, []byte(s.o.SigningKey))
	m.Write([]byte("at_session:" + id))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil)[:18])
}

func (s *sessionServiceImpl) Cookie(c *domain.Caller) string {
	if c.Session == nil {
		return ""
	}
	return c.Session.ID + "." + s.sign(c.Session.ID)
}

// sessionIDFromCookie verifies the HMAC; forged or truncated ids never reach Valkey.
func (s *sessionServiceImpl) sessionIDFromCookie(v string) (string, bool) {
	id, sig, ok := strings.Cut(v, ".")
	if !ok || len(id) < 20 || len(id) > 64 {
		return "", false
	}
	return id, hmac.Equal([]byte(sig), []byte(s.sign(id)))
}

func storeKey(c *domain.Caller) string {
	if c.App {
		return "app:" + c.Subject
	}
	return "web:" + c.Session.ID
}

func (s *sessionServiceImpl) Resolve(ctx context.Context, in ResolveInput) (*domain.Caller, error) {
	c := &domain.Caller{ClientIP: in.ClientIP, Language: in.Language}
	if in.BearerToken != "" {
		return s.resolveApp(ctx, c, in)
	}
	if in.CookieValue == "" {
		return c, nil
	}
	id, ok := s.sessionIDFromCookie(in.CookieValue)
	if !ok {
		return c, nil
	}
	sess, err := s.store.Load(ctx, "web:"+id)
	if err != nil {
		return nil, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	if sess == nil {
		return c, nil
	}
	c.Session = sess
	if sess.Tokens.Valid() {
		s.refreshIfNeeded(ctx, c)
	}
	if c.Session.Authenticated() {
		c.BearerToken = c.Session.Tokens.AccessToken
		c.Subject = c.Session.Profile.Subject
	}
	return c, nil
}

func (s *sessionServiceImpl) resolveApp(ctx context.Context, c *domain.Caller, in ResolveInput) (*domain.Caller, error) {
	c.App = true
	c.AppGuestCartID = in.AppGuestCartID
	if s.o.VerifyBearer {
		sub, _, err := s.verifier.VerifyAccess(ctx, in.BearerToken)
		if err != nil {
			return nil, domain.NewProblem(401, domain.CodeUnauthenticated, "Sign in required", "Invalid or expired token.").WithCause(err)
		}
		c.Subject = sub
	} else {
		c.Subject = unverifiedSubject(in.BearerToken)
	}
	if c.Subject == "" {
		return nil, domain.NewProblem(401, domain.CodeUnauthenticated, "Sign in required", "Invalid or expired token.")
	}
	c.BearerToken = in.BearerToken
	sess, err := s.store.Load(ctx, "app:"+c.Subject)
	if err != nil {
		return nil, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	c.Session = sess
	return c, nil
}

func (s *sessionServiceImpl) RefreshNow(ctx context.Context, c *domain.Caller) bool {
	if c == nil || c.App || c.Session == nil || !c.Session.Tokens.Valid() || c.Session.Tokens.RefreshToken == "" {
		return false
	}
	stale := c.Session.Tokens.AccessToken
	s.refresh(ctx, c, true)
	if !c.Session.Authenticated() || c.Session.Tokens.AccessToken == stale {
		return false
	}
	c.BearerToken = c.Session.Tokens.AccessToken
	return true
}

// refreshIfNeeded rotates the access token shortly before it expires. One replica refreshes (Valkey lock); others
// wait for the new token, because identity's refresh tokens are single-use.
func (s *sessionServiceImpl) refreshIfNeeded(ctx context.Context, c *domain.Caller) {
	s.refresh(ctx, c, false)
}

// refresh rotates the tokens when they are about to expire, or now when force is set (stale claims). A rotation
// another request made meanwhile (the session in the store has another access token) counts as done.
func (s *sessionServiceImpl) refresh(ctx context.Context, c *domain.Caller, force bool) {
	now := s.o.Clock.now()
	if !force && !c.Session.Tokens.NeedsRefresh(now, s.o.RefreshSkew) {
		return
	}
	stale := c.Session.Tokens.AccessToken
	key := "web:" + c.Session.ID
	unlock, ok, err := s.store.Lock(ctx, "refresh:"+c.Session.ID, 10*time.Second)
	if err != nil {
		s.o.Log.WarnContext(ctx, "session refresh lock failed", "error", err)
		return
	}
	if !ok {
		s.waitForRefresh(ctx, c, key)
		return
	}
	defer unlock()
	if fresh, err := s.store.Load(ctx, key); err == nil && fresh != nil {
		c.Session = fresh
		rotated := fresh.Tokens.Valid() && fresh.Tokens.AccessToken != stale
		if (!force && !fresh.Tokens.NeedsRefresh(now, s.o.RefreshSkew)) || (force && rotated) {
			return
		}
	}
	if c.Session.Tokens.RefreshToken == "" {
		s.expireIfDue(ctx, c, key, now)
		return
	}
	t, err := s.identity.Refresh(ctx, c.Session.Tokens.RefreshToken)
	switch {
	case err == nil:
		if t.RefreshToken == "" {
			t.RefreshToken = c.Session.Tokens.RefreshToken
		}
		if t.IDToken == "" {
			t.IDToken = c.Session.Tokens.IDToken
		}
		c.Session.Tokens = &t
		if err := s.store.Save(ctx, key, c.Session, s.o.IdleTTL); err != nil {
			s.o.Log.WarnContext(ctx, "save refreshed session failed", "error", err)
		}
	case errors.Is(err, port.ErrInvalidGrant):
		// Refresh token expired, reused or revoked: the customer is signed out; guest state stays.
		c.Session.Tokens, c.Session.Profile = nil, nil
		if err := s.store.Save(ctx, key, c.Session, s.o.IdleTTL); err != nil {
			s.o.Log.WarnContext(ctx, "save signed-out session failed", "error", err)
		}
	default:
		s.o.Log.WarnContext(ctx, "token refresh failed; identity unavailable", "error", err)
		s.expireIfDue(ctx, c, key, now)
	}
}

// expireIfDue treats an already-expired access token as signed out for this request only.
func (s *sessionServiceImpl) expireIfDue(_ context.Context, c *domain.Caller, _ string, now time.Time) {
	if now.After(c.Session.Tokens.ExpiresAt) {
		copySess := *c.Session
		copySess.Tokens, copySess.Profile = nil, nil
		c.Session = &copySess
	}
}

func (s *sessionServiceImpl) waitForRefresh(ctx context.Context, c *domain.Caller, key string) {
	before := c.Session.Tokens.ExpiresAt
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return
		case <-time.After(100 * time.Millisecond):
		}
		fresh, err := s.store.Load(ctx, key)
		if err != nil || fresh == nil {
			return
		}
		if !fresh.Tokens.Valid() || fresh.Tokens.ExpiresAt.After(before) {
			c.Session = fresh
			return
		}
	}
}

func (s *sessionServiceImpl) Ensure(c *domain.Caller) {
	if c.Session != nil {
		return
	}
	now := s.o.Clock.now()
	if c.App {
		c.Session = &domain.Session{ID: "app", CreatedAt: now}
		return
	}
	c.Session = &domain.Session{ID: randomID(24), CreatedAt: now}
	c.SessionCreated = true
}

func (s *sessionServiceImpl) Save(ctx context.Context, c *domain.Caller) error {
	if c.Session == nil {
		return nil
	}
	if err := s.store.Save(ctx, storeKey(c), c.Session, s.o.IdleTTL); err != nil {
		return domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	return nil
}

func (s *sessionServiceImpl) Delete(ctx context.Context, c *domain.Caller) error {
	if c.Session == nil {
		return nil
	}
	if err := s.store.Delete(ctx, storeKey(c)); err != nil {
		return domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	return nil
}

// unverifiedSubject reads sub from a JWT without verification (only when VerifyBearer is off: tests/local tools).
func unverifiedSubject(token string) string {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return ""
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ""
	}
	const k = `"sub":"`
	i := strings.Index(string(raw), k)
	if i < 0 {
		return ""
	}
	rest := string(raw)[i+len(k):]
	end := strings.IndexByte(rest, '"')
	if end < 0 {
		return ""
	}
	return rest[:end]
}
