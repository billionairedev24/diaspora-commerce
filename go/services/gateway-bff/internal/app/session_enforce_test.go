package app

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

const staffOrigin = "https://staff.example.test"

var t0 = time.Date(2026, 11, 2, 9, 0, 0, 0, time.UTC)

// countingStore is an in-memory session store that counts writes and can be made to fail them.
type countingStore struct {
	m        map[string]domain.Session
	saves    int
	failSave bool
}

func (s *countingStore) Load(_ context.Context, key string) (*domain.Session, error) {
	v, ok := s.m[key]
	if !ok {
		return nil, nil
	}
	return &v, nil
}

func (s *countingStore) Save(_ context.Context, key string, v *domain.Session, _ time.Duration) error {
	if s.failSave {
		return errors.New("valkey down")
	}
	s.saves++
	s.m[key] = *v
	return nil
}

func (s *countingStore) Delete(_ context.Context, key string) error {
	delete(s.m, key)
	return nil
}

func (s *countingStore) Lock(context.Context, string, time.Duration) (func(), bool, error) {
	return func() {}, true, nil
}

type revokingIdentity struct {
	port.IdentityClient
	revoked []string
}

func (r *revokingIdentity) Revoke(_ context.Context, token, _ string) error {
	r.revoked = append(r.revoked, token)
	return nil
}

type fixedPolicies map[domain.Audience]domain.SessionPolicy

func (f fixedPolicies) Policy(_ context.Context, a domain.Audience) domain.SessionPolicy {
	if p, ok := f[a]; ok {
		return p
	}
	return domain.DefaultSessionPolicy(a)
}

type enforceRig struct {
	svc      SessionService
	store    *countingStore
	identity *revokingIdentity
	now      time.Time
	cookie   string
}

// newRig stores a signed-in staff session (audience staff: idle 15 min, absolute 8 h) created and last active at t0.
func newRig(t *testing.T, mutate func(*domain.Session)) *enforceRig {
	t.Helper()
	r := &enforceRig{store: &countingStore{m: map[string]domain.Session{}}, identity: &revokingIdentity{}, now: t0}
	r.svc = NewSessionService(r.store, r.identity, nil, SessionOptions{
		SigningKey: "k-0123456789abcdef0123456789abcdef", Clock: func() time.Time { return r.now },
		Policies: fixedPolicies{}, AppOrigins: map[string]string{"staff-console": staffOrigin, "storefront": "https://shop.example.test"}})
	sess := domain.Session{ID: "0123456789abcdef01234567", CreatedAt: t0, LastActivityAt: t0, Audience: domain.AudienceStaff,
		Tokens:  &domain.Tokens{AccessToken: "at-secret", RefreshToken: "rt-secret", ExpiresAt: t0.Add(24 * time.Hour)},
		Profile: &domain.Profile{Subject: "u1", Roles: []string{"platform.ops_manager"}}, GuestCartID: "g1"}
	if mutate != nil {
		mutate(&sess)
	}
	r.store.m["web:"+sess.ID] = sess
	r.cookie = r.svc.Cookie(&domain.Caller{Session: &sess})
	r.store.saves = 0
	return r
}

func (r *enforceRig) at(d time.Duration, kind ActivityKind) (*domain.Caller, error) {
	r.now = t0.Add(d)
	return r.svc.Resolve(context.Background(), ResolveInput{CookieValue: r.cookie, Origin: staffOrigin, Path: "/v1/staff/shell", Activity: kind})
}

func wantCode(t *testing.T, err error, code string) {
	t.Helper()
	p, ok := domain.AsProblem(err)
	if !ok || p.Status != 401 || p.Code != code {
		t.Fatalf("want 401 %s, got %v", code, err)
	}
}

func (r *enforceRig) stored() domain.Session { return r.store.m["web:0123456789abcdef01234567"] }

func TestIdleSessionIsEndedWith401AndTokensAreRevoked(t *testing.T) {
	r := newRig(t, nil)
	if _, err := r.at(14*time.Minute, ActivityCounts); err != nil {
		t.Fatalf("within the idle window: %v", err)
	}
	_, err := r.at(30*time.Minute, ActivityCounts)
	wantCode(t, err, domain.CodeSessionIdleTimeout)
	got := r.stored()
	if got.Tokens != nil || got.Profile != nil || got.EndedReason != domain.CodeSessionIdleTimeout || got.GuestCartID != "g1" {
		t.Fatalf("the stored session must be signed out but keep guest state: %+v", got)
	}
	if len(r.identity.revoked) != 2 {
		t.Fatalf("refresh and access tokens must be revoked, got %v", r.identity.revoked)
	}
	// The next request is a guest, not a session with tokens.
	c, err := r.at(31*time.Minute, ActivityCounts)
	if err != nil || c.Authenticated() {
		t.Fatalf("after the end: %+v %v", c, err)
	}
}

func TestAbsoluteLimitEndsAnActiveSession(t *testing.T) {
	r := newRig(t, func(s *domain.Session) {
		s.CreatedAt = t0.Add(-7*time.Hour - 50*time.Minute)
		s.LastActivityAt = t0.Add(-time.Minute)
	})
	if _, err := r.at(0, ActivityCounts); err != nil {
		t.Fatalf("just under 8 hours: %v", err)
	}
	_, err := r.at(11*time.Minute, ActivityCounts)
	wantCode(t, err, domain.CodeSessionAbsoluteTimeout)
	if r.stored().EndedReason != domain.CodeSessionAbsoluteTimeout || r.stored().Tokens != nil {
		t.Fatalf("stored: %+v", r.stored())
	}
}

func TestBackgroundRequestsNeverKeepASessionAlive(t *testing.T) {
	r := newRig(t, nil)
	for _, d := range []time.Duration{time.Minute, 5 * time.Minute, 10 * time.Minute, 14 * time.Minute} {
		if _, err := r.at(d, ActivityBackground); err != nil {
			t.Fatalf("background at %s: %v", d, err)
		}
	}
	if !r.stored().LastActivityAt.Equal(t0) || r.store.saves != 0 {
		t.Fatalf("background polling moved activity: %v, %d writes", r.stored().LastActivityAt, r.store.saves)
	}
	_, err := r.at(16*time.Minute, ActivityBackground)
	wantCode(t, err, domain.CodeSessionIdleTimeout)
}

func TestBackgroundRequestIsStillRefusedWhenExpired(t *testing.T) {
	r := newRig(t, nil)
	_, err := r.at(20*time.Minute, ActivityBackground)
	wantCode(t, err, domain.CodeSessionIdleTimeout)
}

func TestActivityPingExtendsTheSession(t *testing.T) {
	r := newRig(t, nil)
	if _, err := r.at(10*time.Minute, ActivityExplicit); err != nil {
		t.Fatal(err)
	}
	if !r.stored().LastActivityAt.Equal(t0.Add(10 * time.Minute)) {
		t.Fatalf("activity ping did not move LastActivityAt: %v", r.stored().LastActivityAt)
	}
	if _, err := r.at(24*time.Minute, ActivityBackground); err != nil {
		t.Fatalf("14 minutes after the ping must be alive: %v", err)
	}
	_, err := r.at(26*time.Minute+time.Second, ActivityBackground)
	wantCode(t, err, domain.CodeSessionIdleTimeout)
}

func TestActivityWritesAreThrottled(t *testing.T) {
	r := newRig(t, nil)
	for _, d := range []time.Duration{5 * time.Second, 12 * time.Second, 29 * time.Second} {
		if _, err := r.at(d, ActivityCounts); err != nil {
			t.Fatal(err)
		}
	}
	if r.store.saves != 0 {
		t.Fatalf("writes within 30 s: %d", r.store.saves)
	}
	if _, err := r.at(31*time.Second, ActivityCounts); err != nil {
		t.Fatal(err)
	}
	if _, err := r.at(40*time.Second, ActivityCounts); err != nil {
		t.Fatal(err)
	}
	if _, err := r.at(55*time.Second, ActivityCounts); err != nil {
		t.Fatal(err)
	}
	if r.store.saves != 1 {
		t.Fatalf("want one write for 31 s, 40 s and 55 s, got %d", r.store.saves)
	}
	if _, err := r.at(62*time.Second, ActivityCounts); err != nil {
		t.Fatal(err)
	}
	if r.store.saves != 2 {
		t.Fatalf("want a second write after another 30 s, got %d", r.store.saves)
	}
}

func TestExplicitActivityWritesEveryFiveSecondsAtMost(t *testing.T) {
	r := newRig(t, nil)
	_, _ = r.at(3*time.Second, ActivityExplicit)
	_, _ = r.at(6*time.Second, ActivityExplicit)
	_, _ = r.at(8*time.Second, ActivityExplicit)
	if r.store.saves != 1 {
		t.Fatalf("pings 3 s, 6 s, 8 s: want one write (6 s), got %d", r.store.saves)
	}
}

func TestSessionsStoredBeforeThePolicyGetOneFreshWindow(t *testing.T) {
	r := newRig(t, func(s *domain.Session) { s.CreatedAt, s.LastActivityAt, s.Audience = time.Time{}, time.Time{}, "" })
	c, err := r.at(40*time.Minute, ActivityCounts)
	if err != nil || !c.Authenticated() {
		t.Fatalf("legacy session must be accepted once: %+v %v", c, err)
	}
	got := r.stored()
	if got.Audience != domain.AudienceStaff || !got.CreatedAt.Equal(t0.Add(40*time.Minute)) || !got.LastActivityAt.Equal(t0.Add(40*time.Minute)) {
		t.Fatalf("legacy session was not stamped from the Origin: %+v", got)
	}
	_, err = r.at(70*time.Minute, ActivityCounts)
	wantCode(t, err, domain.CodeSessionIdleTimeout)
}

func TestLegacyAudienceFallsBackToRolesWithoutAnOrigin(t *testing.T) {
	r := newRig(t, func(s *domain.Session) { s.Audience = "" })
	r.now = t0
	if _, err := r.svc.Resolve(context.Background(), ResolveInput{CookieValue: r.cookie, Path: "/v1/me"}); err != nil {
		t.Fatal(err)
	}
	if r.stored().Audience != domain.AudienceStaff {
		t.Fatalf("platform role must map to staff, got %q", r.stored().Audience)
	}
}

func TestStampingFailsClosedWhenTheStoreCannotWrite(t *testing.T) {
	r := newRig(t, func(s *domain.Session) { s.LastActivityAt = time.Time{} })
	r.store.failSave = true
	_, err := r.at(time.Minute, ActivityCounts)
	if p, ok := domain.AsProblem(err); !ok || p.Status != 503 {
		t.Fatalf("want 503 when a legacy session cannot be stamped, got %v", err)
	}
}

func TestAFailedActivityWriteNeverExtendsTheStoredSession(t *testing.T) {
	r := newRig(t, nil)
	r.store.failSave = true
	if _, err := r.at(10*time.Minute, ActivityCounts); err != nil {
		t.Fatalf("a failed activity write must not fail the request: %v", err)
	}
	_, err := r.at(16*time.Minute, ActivityCounts)
	wantCode(t, err, domain.CodeSessionIdleTimeout)
}

func TestSignInRoutesContinueAsGuestAfterTheEnd(t *testing.T) {
	r := newRig(t, nil)
	r.now = t0.Add(time.Hour)
	c, err := r.svc.Resolve(context.Background(), ResolveInput{CookieValue: r.cookie, Origin: staffOrigin, Path: "/v1/auth/login"})
	if err != nil || c.Authenticated() || c.Session == nil || c.Session.Tokens != nil {
		t.Fatalf("sign-in must carry on as a guest: %+v %v", c, err)
	}
	if r.stored().Tokens != nil {
		t.Fatal("the stored session must still be ended")
	}
}

func TestGuestSessionsAreNotSubjectToThePolicy(t *testing.T) {
	r := newRig(t, func(s *domain.Session) { s.Tokens, s.Profile, s.LastActivityAt, s.Audience = nil, nil, time.Time{}, "" })
	c, err := r.at(1000*time.Hour, ActivityCounts)
	if err != nil || c.Session == nil || r.store.saves != 0 {
		t.Fatalf("guest: %+v %v, %d writes", c, err, r.store.saves)
	}
}

func TestAppBearerCallersAreNotEnforcedHere(t *testing.T) {
	r := newRig(t, nil)
	r.svc = NewSessionService(r.store, r.identity, nil, SessionOptions{SigningKey: "k-0123456789abcdef0123456789abcdef",
		Clock: func() time.Time { return r.now }, Policies: fixedPolicies{}})
	r.now = t0.Add(1000 * time.Hour)
	c, err := r.svc.Resolve(context.Background(), ResolveInput{BearerToken: "x.eyJzdWIiOiJ1MSJ9.y", Path: "/v1/me"})
	if err != nil || !c.App || c.Subject != "u1" {
		t.Fatalf("app caller: %+v %v", c, err)
	}
}

func TestPolicyViewForACookieSession(t *testing.T) {
	r := newRig(t, func(s *domain.Session) { s.CreatedAt = t0.Add(-7*time.Hour - 55*time.Minute) })
	c, err := r.at(time.Minute, ActivityBackground)
	if err != nil {
		t.Fatal(err)
	}
	v, err := r.svc.Policy(context.Background(), c, "")
	if err != nil {
		t.Fatal(err)
	}
	if v.Audience != domain.AudienceStaff || v.IdleTimeoutSeconds != 900 || v.AbsoluteTimeoutSeconds != 8*3600 || v.WarnSeconds != 60 {
		t.Fatalf("view: %+v", v)
	}
	// The absolute limit (4 minutes away) is earlier than last activity + 15 minutes.
	if want := t0.Add(-7*time.Hour - 55*time.Minute).Add(8 * time.Hour); v.ExpiresAt == nil || !v.ExpiresAt.Equal(want) {
		t.Fatalf("expiresAt = %v want %v", v.ExpiresAt, want)
	}
	if v.LastActivityAt == nil || !v.LastActivityAt.Equal(t0) {
		t.Fatalf("lastActivityAt = %v", v.LastActivityAt)
	}
}

func TestPolicyViewForABearerAppHasNoExpiry(t *testing.T) {
	svc := NewSessionService(&countingStore{m: map[string]domain.Session{}}, nil, nil, SessionOptions{SigningKey: "k-0123456789abcdef0123456789abcdef", Policies: fixedPolicies{}})
	app := &domain.Caller{App: true, BearerToken: "t", Subject: "u1"}
	v, err := svc.Policy(context.Background(), app, "driver")
	if err != nil || v.Audience != domain.AudienceDriver || v.ExpiresAt != nil || v.LastActivityAt != nil || v.IdleTimeoutSeconds != 14*3600 {
		t.Fatalf("driver: %+v %v", v, err)
	}
	v, _ = svc.Policy(context.Background(), app, "")
	if v.Audience != domain.AudienceCustomerApp || v.IdleTimeoutSeconds != 30*24*3600 {
		t.Fatalf("customer app: %+v", v)
	}
	if _, err := svc.Policy(context.Background(), &domain.Caller{}, ""); err == nil {
		t.Fatal("a guest has no policy")
	}
}
