package domain

import (
	"slices"
	"strings"
	"time"
)

// Stable problem codes of a session that ended by policy (401).
const (
	// CodeSessionIdleTimeout: the session saw no real user activity for longer than the audience's idle timeout.
	CodeSessionIdleTimeout = "SESSION_IDLE_TIMEOUT"
	// CodeSessionAbsoluteTimeout: the session is older than the audience's session length, however active.
	CodeSessionAbsoluteTimeout = "SESSION_ABSOLUTE_TIMEOUT"
)

// Audience is who a session belongs to, for the sign-out policy that staff edit at identity.
type Audience string

// The audiences. Seller covers Seller Center, providers included.
const (
	AudienceCustomerWeb Audience = "customer-web"
	AudienceCustomerApp Audience = "customer-app"
	AudienceSeller      Audience = "seller"
	AudienceStaff       Audience = "staff"
	AudienceDriver      Audience = "driver"
)

// Audiences lists every audience.
var Audiences = []Audience{AudienceCustomerWeb, AudienceCustomerApp, AudienceSeller, AudienceStaff, AudienceDriver}

// ParseAudience accepts the wire names.
func ParseAudience(s string) (Audience, bool) {
	a := Audience(strings.ToLower(strings.TrimSpace(s)))
	return a, slices.Contains(Audiences, a)
}

// SessionPolicy is when an audience is signed out. Idle and Absolute are always positive.
type SessionPolicy struct {
	// Idle is how long without real user activity ends the session.
	Idle time.Duration
	// Absolute is how long after sign-in ends the session, however active.
	Absolute time.Duration
	// Warn is how long before Idle clients warn (zero: no warning).
	Warn time.Duration
}

// DefaultSessionPolicy is the built-in policy of an audience. It is identity's default
// (SessionPolicyDefaults.java) and the fail-safe when identity cannot be reached: the short windows of staff and
// sellers apply, never "no timeout".
func DefaultSessionPolicy(a Audience) SessionPolicy {
	const day = 24 * time.Hour
	switch a {
	case AudienceStaff:
		return SessionPolicy{Idle: 15 * time.Minute, Absolute: 8 * time.Hour, Warn: time.Minute}
	case AudienceSeller:
		return SessionPolicy{Idle: 30 * time.Minute, Absolute: 12 * time.Hour, Warn: time.Minute}
	case AudienceDriver:
		return SessionPolicy{Idle: 14 * time.Hour, Absolute: 14 * time.Hour}
	default: // customer-web, customer-app
		return SessionPolicy{Idle: 30 * day, Absolute: 90 * day}
	}
}

// Valid reports whether the policy can be enforced (identity sent real numbers).
func (p SessionPolicy) Valid() bool {
	return p.Idle > 0 && p.Absolute > 0 && p.Warn >= 0 && p.Warn < p.Idle
}

// Shorter is the stricter of two policies, field by field (a warning is only meaningful with the shorter idle).
func (p SessionPolicy) Shorter(o SessionPolicy) SessionPolicy {
	out := SessionPolicy{Idle: min(p.Idle, o.Idle), Absolute: min(p.Absolute, o.Absolute), Warn: min(p.Warn, o.Warn)}
	if out.Warn >= out.Idle {
		out.Warn = 0
	}
	return out
}

// ActivityWriteInterval is the longest a stored LastActivityAt may lag behind real activity: the session is written
// at most this often, so a quiet-then-active user is signed out at most this much early, never late.
func (p SessionPolicy) ActivityWriteInterval() time.Duration {
	return min(30*time.Second, p.Idle/4)
}

// AudienceForApp maps a web app key (storefront, seller-center, staff-console) to its audience.
func AudienceForApp(app string) Audience {
	switch app {
	case "seller-center":
		return AudienceSeller
	case "staff-console":
		return AudienceStaff
	default:
		return AudienceCustomerWeb
	}
}

// AudienceForOrigin names the audience of a browser Origin, given the web apps' origins by app key.
func AudienceForOrigin(origin string, appOrigins map[string]string) (Audience, bool) {
	o := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(origin), "/"))
	if o == "" {
		return "", false
	}
	for app, appOrigin := range appOrigins {
		if strings.ToLower(strings.TrimSuffix(appOrigin, "/")) == o {
			return AudienceForApp(app), true
		}
	}
	return "", false
}

// AudienceForRoles is the fallback for a session that predates the audience field and shows no known Origin: staff
// roles mean the staff console, organisation roles Seller Center, anything else the storefront.
func AudienceForRoles(roles []string) Audience {
	for _, r := range roles {
		if strings.HasPrefix(r, "platform.") {
			return AudienceStaff
		}
	}
	for _, r := range roles {
		if strings.HasPrefix(r, "seller.") || strings.HasPrefix(r, "provider.") {
			return AudienceSeller
		}
	}
	return AudienceCustomerWeb
}

// EndedBy returns SESSION_ABSOLUTE_TIMEOUT or SESSION_IDLE_TIMEOUT when the session is over under p at now, "" while
// it is alive. Absolute wins when both apply. A zero CreatedAt or LastActivityAt counts as now (sessions stored before
// the policy existed are given one fresh window; the service stamps them).
func (s *Session) EndedBy(p SessionPolicy, now time.Time) string {
	if !s.CreatedAt.IsZero() && now.Sub(s.CreatedAt) > p.Absolute {
		return CodeSessionAbsoluteTimeout
	}
	if !s.LastActivityAt.IsZero() && now.Sub(s.LastActivityAt) > p.Idle {
		return CodeSessionIdleTimeout
	}
	return ""
}

// ExpiresAt is the earlier of last activity plus idle and creation plus the session length.
func (s *Session) ExpiresAt(p SessionPolicy, now time.Time) time.Time {
	created, last := s.CreatedAt, s.LastActivityAt
	if created.IsZero() {
		created = now
	}
	if last.IsZero() {
		last = now
	}
	idle, abs := last.Add(p.Idle), created.Add(p.Absolute)
	if abs.Before(idle) {
		return abs
	}
	return idle
}

// SessionPolicyView is GET /v1/session/policy and the `session` member of GET /v1/me.
type SessionPolicyView struct {
	Audience               Audience   `json:"audience"`
	IdleTimeoutSeconds     int64      `json:"idleTimeoutSeconds"`
	AbsoluteTimeoutSeconds int64      `json:"absoluteTimeoutSeconds"`
	WarnSeconds            int64      `json:"warnSeconds"`
	LastActivityAt         *time.Time `json:"lastActivityAt"`
	ExpiresAt              *time.Time `json:"expiresAt"`
}

// NewSessionPolicyView renders the policy; the times are nil for app callers (their idle is enforced on the device).
func NewSessionPolicyView(a Audience, p SessionPolicy, lastActivity, expires *time.Time) SessionPolicyView {
	return SessionPolicyView{Audience: a, IdleTimeoutSeconds: int64(p.Idle.Seconds()),
		AbsoluteTimeoutSeconds: int64(p.Absolute.Seconds()), WarnSeconds: int64(p.Warn.Seconds()),
		LastActivityAt: lastActivity, ExpiresAt: expires}
}
