package httpapi_test

import (
	"net/url"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// signInAt signs in at one of the web apps (storefront, Seller Center, staff console) and leaves the browser on it.
func signInAt(t *testing.T, bff *testkit.BFF, b *browser, origin, callback string) {
	t.Helper()
	b.origin = origin
	r := b.do("GET", "/v1/auth/login?returnTo="+url.QueryEscape(origin+"/start"), nil)
	loc, err := url.Parse(r.header.Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	q := loc.Query()
	code := bff.Upstreams.IssueCode(q.Get("code_challenge"), q.Get("nonce"), "user-1", callback)
	mustStatus(t, b.do("GET", "/v1/auth/oidc/callback?code="+url.QueryEscape(code)+"&state="+url.QueryEscape(q.Get("state")), nil), 302)
}

func timeoutRig(t *testing.T, origin, callback string) (*testkit.BFF, *browser, time.Time) {
	t.Helper()
	bff, b := setup(t, testkit.Options{})
	start := time.Now().Truncate(time.Second)
	bff.Clock.T = start
	signInAt(t, bff, b, origin, callback)
	return bff, b, start
}

func TestStaffConsoleSessionEndsAfterFifteenIdleMinutes(t *testing.T) {
	bff, b, start := timeoutRig(t, testkit.StaffConsoleOrigin, testkit.StaffConsoleRedirectURI)

	me := b.do("GET", "/v1/me", nil).json(t)
	session, _ := me["session"].(map[string]any)
	if me["authenticated"] != true || session["audience"] != "staff" || num(session, "idleTimeoutSeconds") != 900 ||
		num(session, "absoluteTimeoutSeconds") != 8*3600 || num(session, "warnSeconds") != 60 {
		t.Fatalf("me.session = %v", me)
	}

	bff.Clock.T = start.Add(14 * time.Minute)
	mustStatus(t, b.do("GET", "/v1/me", nil), 200) // real activity: the window moves to 14 + 15 minutes

	bff.Clock.T = start.Add(28 * time.Minute)
	mustStatus(t, b.do("GET", "/v1/me", nil), 200)

	bff.Clock.T = start.Add(44 * time.Minute)
	r := b.do("GET", "/v1/me", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "SESSION_IDLE_TIMEOUT" {
		t.Fatalf("code = %s", c)
	}
	// Afterwards the person is a guest, and /v1/me says why.
	again := b.do("GET", "/v1/me", nil).json(t)
	if again["authenticated"] != false || again["signedOutReason"] != "SESSION_IDLE_TIMEOUT" {
		t.Fatalf("me after the end = %v", again)
	}
}

func TestBackgroundPollingDoesNotKeepASessionAliveButIsRefusedWhenExpired(t *testing.T) {
	bff, b, start := timeoutRig(t, testkit.StaffConsoleOrigin, testkit.StaffConsoleRedirectURI)
	b.headers["X-Background"] = "1"
	for _, m := range []int{3, 6, 9, 12, 14} {
		bff.Clock.T = start.Add(time.Duration(m) * time.Minute)
		mustStatus(t, b.do("GET", "/v1/me", nil), 200)
	}
	bff.Clock.T = start.Add(16 * time.Minute)
	r := b.do("GET", "/v1/me", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "SESSION_IDLE_TIMEOUT" {
		t.Fatalf("code = %s", c)
	}
}

func TestActivityEndpointKeepsAReadingUserSignedIn(t *testing.T) {
	bff, b, start := timeoutRig(t, testkit.StaffConsoleOrigin, testkit.StaffConsoleRedirectURI)
	bff.Clock.T = start.Add(10 * time.Minute)
	b.headers["X-Background"] = "1" // polling alongside the pings changes nothing
	mustStatus(t, b.do("GET", "/v1/me", nil), 200)
	delete(b.headers, "X-Background")
	r := b.do("POST", "/v1/session/activity", nil)
	mustStatus(t, r, 204)

	bff.Clock.T = start.Add(24 * time.Minute)
	b.headers["X-Background"] = "1"
	mustStatus(t, b.do("GET", "/v1/me", nil), 200)
	p := b.do("GET", "/v1/session/policy", nil).json(t)
	last, _ := time.Parse(time.RFC3339Nano, p["lastActivityAt"].(string))
	if !last.Equal(start.Add(10 * time.Minute)) {
		t.Fatalf("lastActivityAt = %v, want the ping at 10 minutes", last)
	}
	expires, _ := time.Parse(time.RFC3339Nano, p["expiresAt"].(string))
	if !expires.Equal(start.Add(25 * time.Minute)) {
		t.Fatalf("expiresAt = %v, want last activity + 15 minutes", expires)
	}
	// The policy read itself is never activity, with or without the header.
	delete(b.headers, "X-Background")
	bff.Clock.T = start.Add(26 * time.Minute)
	r = b.do("GET", "/v1/session/policy", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "SESSION_IDLE_TIMEOUT" {
		t.Fatalf("code = %s", c)
	}
}

func TestActivityEndpointNeedsASession(t *testing.T) {
	_, b := setup(t, testkit.Options{})
	r := b.do("POST", "/v1/session/activity", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "UNAUTHENTICATED" {
		t.Fatalf("code = %s", c)
	}
	mustStatus(t, b.do("GET", "/v1/session/policy", nil), 401)
}

func TestSellerCenterSessionHasAThirtyMinuteIdleAndTwelveHourLimit(t *testing.T) {
	bff, b, start := timeoutRig(t, testkit.SellerCenterOrigin, testkit.SellerCenterRedirectURI)
	p := b.do("GET", "/v1/session/policy", nil).json(t)
	if p["audience"] != "seller" || num(p, "idleTimeoutSeconds") != 1800 || num(p, "absoluteTimeoutSeconds") != 12*3600 {
		t.Fatalf("policy = %v", p)
	}
	// Active every 20 minutes for 12 hours: the absolute limit still ends it.
	for m := 20; m <= 12*60; m += 20 {
		bff.Clock.T = start.Add(time.Duration(m) * time.Minute)
		mustStatus(t, b.do("GET", "/v1/me", nil), 200)
	}
	bff.Clock.T = start.Add(12*time.Hour + 10*time.Minute)
	r := b.do("GET", "/v1/me", nil)
	mustStatus(t, r, 401)
	if c := problemCode(t, r); c != "SESSION_ABSOLUTE_TIMEOUT" {
		t.Fatalf("code = %s", c)
	}
}

func TestStorefrontKeepsCustomersSignedInForThirtyDays(t *testing.T) {
	bff, b, start := timeoutRig(t, testkit.WebOrigin, testkit.RedirectURI)
	p := b.do("GET", "/v1/session/policy", nil).json(t)
	if p["audience"] != "customer-web" || num(p, "idleTimeoutSeconds") != 30*86400 || num(p, "warnSeconds") != 0 {
		t.Fatalf("policy = %v", p)
	}
	bff.Clock.T = start.Add(29 * 24 * time.Hour)
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != true {
		t.Fatalf("29 days idle must stay signed in: %v", me)
	}
}

func TestSignInAgainAfterTheEndWorks(t *testing.T) {
	bff, b, start := timeoutRig(t, testkit.StaffConsoleOrigin, testkit.StaffConsoleRedirectURI)
	bff.Clock.T = start.Add(time.Hour)
	mustStatus(t, b.do("GET", "/v1/me", nil), 401)
	// /v1/auth/ routes are never answered 401 by the policy, so sign-in starts at once.
	signInAt(t, bff, b, testkit.StaffConsoleOrigin, testkit.StaffConsoleRedirectURI)
	if me := b.do("GET", "/v1/me", nil).json(t); me["authenticated"] != true {
		t.Fatalf("me after signing in again = %v", me)
	}
}

func TestBearerAppsGetThePolicyWithoutExpiry(t *testing.T) {
	bff, b := setup(t, testkit.Options{})
	b.origin = ""
	b.headers["Authorization"] = "Bearer " + bff.Upstreams.Token(jwt.MapClaims{"sub": "user-9", "pt": "CUSTOMER"})
	p := b.do("GET", "/v1/session/policy", nil).json(t)
	if p["audience"] != "customer-app" || num(p, "idleTimeoutSeconds") != 30*86400 || p["expiresAt"] != nil || p["lastActivityAt"] != nil {
		t.Fatalf("customer app policy = %v", p)
	}
	b.headers["X-App"] = "driver"
	p = b.do("GET", "/v1/session/policy", nil).json(t)
	if p["audience"] != "driver" || num(p, "idleTimeoutSeconds") != 14*3600 || num(p, "absoluteTimeoutSeconds") != 14*3600 {
		t.Fatalf("driver policy = %v", p)
	}
	if me := b.do("GET", "/v1/me", nil).json(t); me["session"] != nil {
		t.Fatalf("app me must not guess an app: %v", me)
	}
}
