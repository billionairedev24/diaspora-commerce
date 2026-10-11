package valkey_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/testdocker"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/valkey"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

func startValkey(t *testing.T) *redis.Client {
	t.Helper()
	testdocker.Require(t)
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "valkey/valkey:8.1-alpine",
		testcontainers.WithExposedPorts("6379/tcp"),
		testcontainers.WithWaitStrategy(wait.ForListeningPort("6379/tcp").WithStartupTimeout(time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	addr, err := ctr.PortEndpoint(ctx, "6379/tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	rdb := redis.NewClient(&redis.Options{Addr: addr})
	t.Cleanup(func() { _ = rdb.Close() })
	return rdb
}

func TestValkeyStore(t *testing.T) {
	rdb := startValkey(t)
	ctx := context.Background()
	store, err := valkey.New(rdb, "integration-secret-0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Ping(ctx); err != nil {
		t.Fatal(err)
	}

	t.Run("sessions are encrypted at rest and expire", func(t *testing.T) {
		s := &domain.Session{ID: "sid-1", ZIP: "77042", GuestCartID: "g-1",
			Tokens:  &domain.Tokens{AccessToken: "secret-access-token", RefreshToken: "secret-refresh", ExpiresAt: time.Now().Add(time.Minute)},
			Profile: &domain.Profile{Subject: "u1", FirstName: "Ada"}}
		if err := store.Save(ctx, "web:sid-1", s, 2*time.Second); err != nil {
			t.Fatal(err)
		}
		raw, err := rdb.Get(ctx, "bff:sess:web:sid-1").Bytes()
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(raw), "secret-access-token") || strings.Contains(string(raw), "77042") {
			t.Fatal("session stored in clear text")
		}
		got, err := store.Load(ctx, "web:sid-1")
		if err != nil || got == nil || got.Tokens.AccessToken != "secret-access-token" || got.ZIP != "77042" || !got.Authenticated() {
			t.Fatalf("load = %+v %v", got, err)
		}
		// A record copied under another key does not decrypt (key bound as associated data).
		rdb.Set(ctx, "bff:sess:web:other", raw, time.Minute)
		if other, _ := store.Load(ctx, "web:other"); other != nil {
			t.Fatal("a moved record must not load")
		}
		// Another signing secret cannot read it either.
		foreign, _ := valkey.New(rdb, "another-secret-0123456789abcdef0000")
		if s, _ := foreign.Load(ctx, "web:sid-1"); s != nil {
			t.Fatal("foreign key decrypted the session")
		}
		if ttl := rdb.TTL(ctx, "bff:sess:web:sid-1").Val(); ttl <= 0 || ttl > 2*time.Second {
			t.Fatalf("ttl = %v", ttl)
		}
		if err := store.Delete(ctx, "web:sid-1"); err != nil {
			t.Fatal(err)
		}
		if s, _ := store.Load(ctx, "web:sid-1"); s != nil {
			t.Fatal("deleted session loaded")
		}
	})

	t.Run("OIDC requests are single use", func(t *testing.T) {
		if err := store.PutAuthRequest(ctx, "state-1", port.AuthRequest{Verifier: "v", Nonce: "n", ReturnTo: "/cart"}, time.Minute); err != nil {
			t.Fatal(err)
		}
		r, err := store.TakeAuthRequest(ctx, "state-1")
		if err != nil || r == nil || r.Verifier != "v" || r.ReturnTo != "/cart" {
			t.Fatalf("take = %+v %v", r, err)
		}
		if again, _ := store.TakeAuthRequest(ctx, "state-1"); again != nil {
			t.Fatal("state replayed")
		}
		_ = store.PutChallenge(ctx, "ch-1", port.Challenge{Email: "a@example.com"}, time.Minute)
		if c, _ := store.GetChallenge(ctx, "ch-1"); c == nil || c.Email != "a@example.com" {
			t.Fatalf("challenge = %+v", c)
		}
		_ = store.DeleteChallenge(ctx, "ch-1")
		if c, _ := store.GetChallenge(ctx, "ch-1"); c != nil {
			t.Fatal("challenge not deleted")
		}
	})

	t.Run("refresh lock is exclusive", func(t *testing.T) {
		unlock, ok, err := store.Lock(ctx, "refresh:sid", 5*time.Second)
		if err != nil || !ok {
			t.Fatalf("lock: %v %v", ok, err)
		}
		if _, ok2, _ := store.Lock(ctx, "refresh:sid", 5*time.Second); ok2 {
			t.Fatal("second holder")
		}
		unlock()
		if _, ok3, _ := store.Lock(ctx, "refresh:sid", 5*time.Second); !ok3 {
			t.Fatal("lock not released")
		}
	})

	t.Run("selection and rate limits", func(t *testing.T) {
		_ = store.SetSelected(ctx, "guest:g", []string{"A", "B"}, false)
		_ = store.SetSelected(ctx, "guest:g", []string{"A"}, true)
		d, err := store.Deselected(ctx, "guest:g")
		if err != nil || len(d) != 1 || !d["B"] {
			t.Fatalf("deselected = %v %v", d, err)
		}
		var denied int
		for range 5 {
			ok, retry, err := store.Allow(ctx, "auth:1.2.3.4", 3, time.Minute)
			if err != nil {
				t.Fatal(err)
			}
			if !ok {
				denied++
				if retry <= 0 || retry > time.Minute {
					t.Fatalf("retry = %v", retry)
				}
			}
		}
		if denied != 2 {
			t.Fatalf("denied = %d, want 2", denied)
		}
	})
}

// TestSessionFlowOnValkey runs sign-in (PKCE), session rotation and sign-out end to end with sessions in Valkey.
func TestSessionFlowOnValkey(t *testing.T) {
	rdb := startValkey(t)
	store, err := valkey.New(rdb, "integration-secret-0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	u := testkit.NewUpstreams(testkit.Options{})
	t.Cleanup(u.Close)
	bff := testkit.NewBFF(u, testkit.Config{Store: store})
	cookie := ""
	do := func(method, path, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, strings.NewReader(body))
		req.Header.Set("Origin", testkit.WebOrigin)
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		if cookie != "" {
			req.AddCookie(&http.Cookie{Name: "at_session", Value: cookie})
		}
		rec := httptest.NewRecorder()
		bff.Handler.ServeHTTP(rec, req)
		for _, c := range rec.Result().Cookies() {
			if c.Name == "at_session" {
				cookie = c.Value
			}
		}
		return rec
	}
	if rec := do("POST", "/v1/cart/items", `{"offerId":"ATSHEAV001","quantity":1}`); rec.Code != 200 {
		t.Fatalf("add: %d %s", rec.Code, rec.Body)
	}
	guest := cookie
	rec := do("GET", "/v1/auth/login?returnTo=/cart", "")
	loc, _ := url.Parse(rec.Header().Get("Location"))
	q := loc.Query()
	code := u.IssueCode(q.Get("code_challenge"), q.Get("nonce"), "user-7", testkit.RedirectURI)
	rec = do("GET", "/v1/auth/oidc/callback?code="+code+"&state="+q.Get("state"), "")
	if rec.Code != 302 || rec.Header().Get("Location") != testkit.WebOrigin+"/cart" || cookie == guest {
		t.Fatalf("callback: %d %s", rec.Code, rec.Header().Get("Location"))
	}
	keys := rdb.Keys(context.Background(), "bff:sess:*").Val()
	if len(keys) != 1 {
		t.Fatalf("sessions in valkey = %v; the guest session must be gone", keys)
	}
	if rec := do("GET", "/v1/me", ""); !strings.Contains(rec.Body.String(), `"authenticated":true`) {
		t.Fatalf("me: %s", rec.Body)
	}
	if rec := do("POST", "/v1/auth/sign-out", ""); rec.Code != 204 {
		t.Fatalf("sign-out: %d", rec.Code)
	}
	if n := len(rdb.Keys(context.Background(), "bff:sess:*").Val()); n != 0 {
		t.Fatalf("%d sessions left after sign-out", n)
	}
}
