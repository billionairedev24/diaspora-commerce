package app

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ActivityKind says whether a request counts as the person using the app.
type ActivityKind int

const (
	// ActivityCounts is an ordinary request: it moves LastActivityAt forward (written at most every
	// ActivityWriteInterval).
	ActivityCounts ActivityKind = iota
	// ActivityBackground is polling or a timer (header X-Background: 1, or GET /v1/session/policy): it never moves
	// LastActivityAt, but an expired session is still refused.
	ActivityBackground
	// ActivityExplicit is POST /v1/session/activity, which the web app sends on real input and from the
	// "stay signed in" button: it counts like an ordinary request with a short write interval (explicitInterval).
	ActivityExplicit
)

// explicitInterval is the shortest gap between two writes caused by POST /v1/session/activity.
const explicitInterval = 5 * time.Second

// endedMemory is how long GET /v1/me tells a signed-out person why they were signed out.
const endedMemory = 10 * time.Minute

// enforce applies the audience's sign-out policy to a web session that holds tokens: it stamps sessions stored before
// the policy existed, ends the session when it is idle or too old (401 with the reason), and otherwise records the
// request as activity. An error leaves nothing alive: whatever fails, the stored LastActivityAt/CreatedAt are never
// moved forward, so the next request judges the session again.
func (s *sessionServiceImpl) enforce(ctx context.Context, c *domain.Caller, in ResolveInput) error {
	sess := c.Session
	now := s.o.Clock.now()
	stamped := false
	if sess.Audience == "" {
		sess.Audience, stamped = s.audienceOf(sess, in), true
	}
	if sess.CreatedAt.IsZero() {
		sess.CreatedAt, stamped = now, true
	}
	if sess.LastActivityAt.IsZero() {
		// Stored before the policy existed: one fresh idle window from this request.
		sess.LastActivityAt, stamped = now, true
	}
	pol := s.o.Policies.Policy(ctx, sess.Audience)
	if code := sess.EndedBy(pol, now); code != "" {
		return s.end(ctx, c, in, code, now)
	}
	touched := in.Activity != ActivityBackground && now.Sub(sess.LastActivityAt) >= writeInterval(pol, in.Activity)
	if touched {
		sess.LastActivityAt = now
	}
	if !stamped && !touched {
		return nil
	}
	if err := s.store.Save(ctx, "web:"+sess.ID, sess, s.o.IdleTTL); err != nil {
		if stamped {
			// An unstamped session would get a fresh window on every request while the store is failing.
			return domain.UpstreamUnavailable("valkey").WithCause(err)
		}
		s.o.Log.WarnContext(ctx, "recording session activity failed", "error", err)
	}
	return nil
}

func writeInterval(p domain.SessionPolicy, k ActivityKind) time.Duration {
	if k == ActivityExplicit {
		return min(explicitInterval, p.ActivityWriteInterval())
	}
	return p.ActivityWriteInterval()
}

// audienceOf works out the audience of a session stored before the policy existed: the web app the request comes
// from, else what the person's roles say.
func (s *sessionServiceImpl) audienceOf(sess *domain.Session, in ResolveInput) domain.Audience {
	if a, ok := domain.AudienceForOrigin(in.Origin, s.o.AppOrigins); ok {
		return a
	}
	if sess.Profile != nil {
		return domain.AudienceForRoles(sess.Profile.Roles)
	}
	return domain.AudienceCustomerWeb
}

// end signs the person out because of the policy: tokens and profile are cleared and saved (guest cart and ZIP
// stay), the refresh and access tokens are revoked at identity best-effort, and the request is refused with 401
// SESSION_IDLE_TIMEOUT or SESSION_ABSOLUTE_TIMEOUT. Sign-in routes carry on as a guest instead, so signing in again
// works. Failing to save or revoke changes nothing: the stored session is still over its limit.
func (s *sessionServiceImpl) end(ctx context.Context, c *domain.Caller, in ResolveInput, code string, now time.Time) error {
	sess := c.Session
	if s.identity != nil && sess.Tokens != nil {
		rctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 3*time.Second)
		for token, hint := range map[string]string{sess.Tokens.RefreshToken: "refresh_token", sess.Tokens.AccessToken: "access_token"} {
			if token == "" {
				continue
			}
			if err := s.identity.Revoke(rctx, token, hint); err != nil {
				s.o.Log.WarnContext(ctx, "revoking tokens of an ended session failed", "reason", code, "error", err)
			}
		}
		cancel()
	}
	sess.Tokens, sess.Profile = nil, nil
	sess.LastActivityAt, sess.EndedReason, sess.EndedAt = time.Time{}, code, now
	if err := s.store.Save(ctx, "web:"+sess.ID, sess, s.o.IdleTTL); err != nil {
		s.o.Log.WarnContext(ctx, "saving a signed-out session failed", "reason", code, "error", err)
	}
	if strings.HasPrefix(in.Path, "/v1/auth/") || in.Path == "/v1/maintenance" {
		return nil
	}
	detail := "You were signed out because you were away for a while. Sign in to continue."
	if code == domain.CodeSessionAbsoluteTimeout {
		detail = "You were signed out because your session reached its maximum length. Sign in to continue."
	}
	return domain.NewProblem(http.StatusUnauthorized, code, "Signed out", detail)
}

// Policy implements SessionService.
func (s *sessionServiceImpl) Policy(ctx context.Context, c *domain.Caller, appHint string) (domain.SessionPolicyView, error) {
	if c == nil || !c.Authenticated() {
		return domain.SessionPolicyView{}, domain.Unauthenticated()
	}
	if c.App {
		a := domain.AudienceCustomerApp
		if strings.EqualFold(strings.TrimSpace(appHint), "driver") {
			a = domain.AudienceDriver
		}
		return domain.NewSessionPolicyView(a, s.o.Policies.Policy(ctx, a), nil, nil), nil
	}
	sess := c.Session
	a := sess.Audience
	if a == "" {
		a = domain.AudienceForRoles(sess.Profile.Roles)
	}
	pol := s.o.Policies.Policy(ctx, a)
	now := s.o.Clock.now()
	last, expires := sess.LastActivityAt, sess.ExpiresAt(pol, now)
	if last.IsZero() {
		last = now
	}
	return domain.NewSessionPolicyView(a, pol, &last, &expires), nil
}
