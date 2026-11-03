package upstreams

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

type scopeTokens struct {
	scopes []string
	err    error
}

func (s *scopeTokens) Token(_ context.Context, scope string) (string, error) {
	s.scopes = append(s.scopes, scope)
	return "svc-token", s.err
}

func TestSessionPolicyReadsIdentityWithTheServiceScope(t *testing.T) {
	var auth, path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth, path = r.Header.Get("Authorization"), r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"audiences":[{"audience":"staff","idleTimeoutMinutes":10,"absoluteTimeoutMinutes":240,"warnSeconds":30},` +
			`{"audience":"somebody-new","idleTimeoutMinutes":1,"absoluteTimeoutMinutes":2,"warnSeconds":0}]}`))
	}))
	t.Cleanup(srv.Close)
	tokens := &scopeTokens{}
	c := NewSessionPolicy(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Timeout: 2 * time.Second}), tokens)

	got, err := c.Policies(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if path != "/v1/internal/session-policy" || auth != "Bearer svc-token" || len(tokens.scopes) != 1 || tokens.scopes[0] != "sessions:policy.read" {
		t.Fatalf("request: %s %s scopes=%v", path, auth, tokens.scopes)
	}
	want := domain.SessionPolicy{Idle: 10 * time.Minute, Absolute: 4 * time.Hour, Warn: 30 * time.Second}
	if len(got) != 1 || got[domain.AudienceStaff] != want {
		t.Fatalf("policies = %+v (unknown audiences are ignored)", got)
	}
}

func TestSessionPolicyErrorsWhenIdentityOrTheTokenFail(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusForbidden) }))
	t.Cleanup(srv.Close)
	hc := httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Timeout: 2 * time.Second})
	if _, err := NewSessionPolicy(hc, &scopeTokens{}).Policies(context.Background()); err == nil {
		t.Fatal("a 403 from identity must be an error, so the BFF falls back to the defaults")
	}
	if _, err := NewSessionPolicy(hc, &scopeTokens{err: errors.New("no token")}).Policies(context.Background()); err == nil {
		t.Fatal("no service token must be an error")
	}
}
