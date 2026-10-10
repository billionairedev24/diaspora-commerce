package identity_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/identity"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestResolverUsesClientCredentialsAndPlannedPaths(t *testing.T) {
	var tokens atomic.Int32
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		id, secret, ok := r.BasicAuth()
		_ = r.ParseForm()
		if !ok || id != "notifications-service" || secret != "s3cret" || r.Form.Get("grant_type") != "client_credentials" ||
			r.Form.Get("scope") != "users:read" {
			http.Error(w, "bad client", http.StatusUnauthorized)
			return
		}
		tokens.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"svc-token","token_type":"Bearer","expires_in":300}`))
	})
	authed := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Authorization") != "Bearer svc-token" {
				http.Error(w, "unauthenticated", http.StatusUnauthorized)
				return
			}
			h(w, r)
		}
	}
	mux.HandleFunc("GET /v1/internal/users/{id}/contact", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "u-1" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"userId": "u-1", "firstName": "Ada", "email": "ada@example.com",
			"phone": "+15550001111", "phoneVerified": false, "locale": "fr-CA", "timeZone": "America/Toronto", "market": "CA",
			"pushTokens":  []map[string]string{{"token": "t", "platform": "fcm"}},
			"preferences": map[string]bool{"email": true, "push": false}})
	}))
	mux.HandleFunc("GET /v1/internal/orgs/{id}/owners", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") == "org_boom" {
			http.Error(w, "oops", http.StatusInternalServerError)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"orgId": r.PathValue("id"), "ownerUserIds": []string{"u-1", "u-2"}})
	}))
	srv := httptest.NewServer(mux)
	defer srv.Close()

	res := identity.New(identity.Config{BaseURL: srv.URL + "/", TokenURL: srv.URL + "/oauth2/token", ClientID: "notifications-service",
		ClientSecret: "s3cret", Scopes: []string{"users:read"}}, &http.Client{Timeout: 2 * time.Second})
	ctx := context.Background()
	c, err := res.Contact(ctx, "u-1")
	if err != nil {
		t.Fatal(err)
	}
	if c.FirstName != "Ada" || c.Email != "ada@example.com" || c.Phone != "" || c.Market != "ca" || c.Locale != "fr-CA" ||
		len(c.PushTokens) != 1 || !slices.Equal(c.Disabled, []domain.Channel{domain.ChannelPush}) {
		t.Fatalf("%+v (unverified phone dropped, push switched off)", c)
	}
	if _, err := res.Contact(ctx, "u-404"); !errors.Is(err, domain.ErrRecipientNotFound) {
		t.Fatalf("404: %v", err)
	}
	owners, err := res.OrgOwners(ctx, "org_1")
	if err != nil || !slices.Equal(owners, []string{"u-1", "u-2"}) {
		t.Fatalf("%v %v", owners, err)
	}
	if _, err := res.OrgOwners(ctx, "org_boom"); err == nil || errors.Is(err, domain.ErrRecipientNotFound) {
		t.Fatalf("5xx must be a transient error: %v", err)
	}
	if tokens.Load() != 1 {
		t.Fatalf("token must be cached, fetched %d times", tokens.Load())
	}
}

func TestResolverBadCredentialsIsTransient(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, `{"error":"invalid_client"}`, http.StatusUnauthorized)
	}))
	defer srv.Close()
	res := identity.New(identity.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token", ClientID: "x", ClientSecret: "y"},
		&http.Client{Timeout: time.Second})
	if _, err := res.Contact(context.Background(), "u-1"); err == nil || errors.Is(err, domain.ErrRecipientNotFound) {
		t.Fatalf("%v", err)
	}
}
