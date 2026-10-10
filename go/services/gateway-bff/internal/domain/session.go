package domain

import (
	"strings"
	"time"
)

// Tokens are identity's OAuth tokens, held server-side only (never sent to browsers, never logged).
type Tokens struct {
	AccessToken  string    `json:"accessToken"`
	RefreshToken string    `json:"refreshToken,omitempty"`
	IDToken      string    `json:"idToken,omitempty"`
	ExpiresAt    time.Time `json:"expiresAt"`
}

// Valid reports whether there is an access token.
func (t *Tokens) Valid() bool { return t != nil && t.AccessToken != "" }

// NeedsRefresh reports whether the access token expires within the skew window.
func (t *Tokens) NeedsRefresh(now time.Time, skew time.Duration) bool {
	return t != nil && t.AccessToken != "" && now.Add(skew).After(t.ExpiresAt)
}

// Profile is what the BFF remembers about the signed-in user (from the ID token) for fast /v1/me fallbacks.
type Profile struct {
	Subject   string   `json:"sub"`
	FirstName string   `json:"firstName"`
	LastName  string   `json:"lastName"`
	Email     string   `json:"email"`
	Roles     []string `json:"roles,omitempty"`
}

// Initials of the profile ("AN").
func (p Profile) Initials() string {
	first := func(s string) string {
		for _, r := range strings.TrimSpace(s) {
			return strings.ToUpper(string(r))
		}
		return ""
	}
	return first(p.FirstName) + first(p.LastName)
}

// Session is the BFF session: guest state plus (once signed in) identity tokens.
type Session struct {
	ID          string    `json:"id"`
	CreatedAt   time.Time `json:"createdAt"`
	GuestCartID string    `json:"guestCartId,omitempty"`
	ZIP         string    `json:"zip,omitempty"`
	Tokens      *Tokens   `json:"tokens,omitempty"`
	Profile     *Profile  `json:"profile,omitempty"`
}

// Authenticated reports whether a customer is signed in on this session.
func (s *Session) Authenticated() bool { return s != nil && s.Tokens.Valid() && s.Profile != nil }

// Caller is the request principal as the use cases see it.
//
// Web callers have a cookie session (possibly not yet created); app callers send a verified bearer token and
// keep their small state (deliver-to ZIP) in a per-subject session. Either way the guest cart id is known.
type Caller struct {
	// Session is the web session or the app subject's state; nil until something needs storing.
	Session *Session
	// SessionCreated is set when a use case created the session; the HTTP adapter then issues the cookie.
	SessionCreated bool
	// SessionRotated is set when sign-in rotated the session id.
	SessionRotated bool
	// SessionEnded is set by sign-out; the HTTP adapter clears the cookie.
	SessionEnded bool
	// App is true for bearer-token (mobile) callers.
	App bool
	// BearerToken is the access token to relay downstream (empty for guests).
	BearerToken string
	// Subject is the signed-in customer's id (empty for guests).
	Subject string
	// AppGuestCartID is the X-Guest-Cart header sent by the app.
	AppGuestCartID string
	// IssuedGuestCartID is set when the cart service issued a new guest id that the app must store.
	IssuedGuestCartID string
	// ClientIP is used for rate limits.
	ClientIP string
	// Language is the Accept-Language header.
	Language string
}

// Authenticated reports whether the caller is a signed-in customer.
func (c *Caller) Authenticated() bool { return c != nil && c.BearerToken != "" && c.Subject != "" }

// GuestCartID returns the guest cart id from the session (web) or header (app).
func (c *Caller) GuestCartID() string {
	if c.App {
		if c.IssuedGuestCartID != "" {
			return c.IssuedGuestCartID
		}
		return c.AppGuestCartID
	}
	if c.Session != nil {
		return c.Session.GuestCartID
	}
	return ""
}

// ZIP returns the deliver-to ZIP, or "" for the default.
func (c *Caller) ZIP() string {
	if c.Session != nil {
		return c.Session.ZIP
	}
	return ""
}

// SafeReturnTo keeps only relative storefront paths ("/…", not "//…"); anything else becomes "/".
func SafeReturnTo(v string) string {
	if strings.HasPrefix(v, "/") && !strings.HasPrefix(v, "//") && !strings.HasPrefix(v, "/\\") &&
		!strings.ContainsAny(v, "\r\n") {
		return v
	}
	return "/"
}
