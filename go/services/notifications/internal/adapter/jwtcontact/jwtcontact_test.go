package jwtcontact_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/golang-jwt/jwt/v5"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/jwtcontact"
)

func TestResolveFromTokenOfAuthenticatedActor(t *testing.T) {
	tok, _ := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{"sub": "staff-1", "email": "ops@afrotransact.com",
		"phone_number": "+15550001234"}).SignedString([]byte("k"))
	r := jwtcontact.New("", "")
	var email, phone string
	var err error
	h := r.Capture(http.HandlerFunc(func(_ http.ResponseWriter, req *http.Request) {
		ctx := auth.WithActor(req.Context(), auth.Actor{Subject: "staff-1"})
		email, phone, err = r.Resolve(ctx)
	}))
	req := httptest.NewRequestWithContext(context.Background(), "POST", "/", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	h.ServeHTTP(httptest.NewRecorder(), req)
	if err != nil || email != "ops@afrotransact.com" || phone != "+15550001234" {
		t.Fatalf("%s %s %v", email, phone, err)
	}

	h = r.Capture(http.HandlerFunc(func(_ http.ResponseWriter, req *http.Request) {
		_, _, err = r.Resolve(auth.WithActor(req.Context(), auth.Actor{Subject: "someone-else"}))
	}))
	h.ServeHTTP(httptest.NewRecorder(), req)
	if !errors.Is(err, jwtcontact.ErrNoContact) {
		t.Fatal("token of another subject must not be used")
	}
}

func TestLocalFallback(t *testing.T) {
	email, _, err := jwtcontact.New("local@afrotransact.local", "").Resolve(context.Background())
	if err != nil || email != "local@afrotransact.local" {
		t.Fatal(email, err)
	}
	if _, _, err := jwtcontact.New("", "").Resolve(context.Background()); err == nil {
		t.Fatal("no fallback must fail")
	}
}
