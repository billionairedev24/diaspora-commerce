// Package jwtcontact implements port.ContactResolver from the caller's (already verified) access token: the
// identity service puts the user's email and phone_number claims in it. With auth disabled (local profile) it
// falls back to configured addresses.
package jwtcontact

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/golang-jwt/jwt/v5"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Resolver is the ContactResolver plus the middleware that captures the bearer token for it.
type Resolver interface {
	port.ContactResolver
	// Capture stores the request's bearer token in the context. Mount it after auth.Middleware.
	Capture(next http.Handler) http.Handler
}

type resolverImpl struct {
	fallbackEmail string
	fallbackPhone string
}

var _ Resolver = (*resolverImpl)(nil)

// New returns a Resolver. fallbackEmail/fallbackPhone are used only when no bearer token is present (auth off).
func New(fallbackEmail, fallbackPhone string) Resolver {
	return &resolverImpl{fallbackEmail: fallbackEmail, fallbackPhone: fallbackPhone}
}

type tokenKey struct{}

func (r *resolverImpl) Capture(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if tok, ok := strings.CutPrefix(req.Header.Get("Authorization"), "Bearer "); ok && tok != "" {
			req = req.WithContext(context.WithValue(req.Context(), tokenKey{}, tok))
		}
		next.ServeHTTP(w, req)
	})
}

type claims struct {
	jwt.RegisteredClaims
	Email string `json:"email"`
	Phone string `json:"phone_number"`
}

// ErrNoContact is returned when the token carries no usable contact.
var ErrNoContact = errors.New("caller has no email or phone on record")

func (r *resolverImpl) Resolve(ctx context.Context) (string, string, error) {
	tok, _ := ctx.Value(tokenKey{}).(string)
	if tok == "" {
		if r.fallbackEmail == "" && r.fallbackPhone == "" {
			return "", "", ErrNoContact
		}
		return r.fallbackEmail, r.fallbackPhone, nil
	}
	// The signature was verified by auth.Middleware; here we only read the contact claims and make sure the
	// token belongs to the authenticated actor.
	var c claims
	if _, _, err := jwt.NewParser().ParseUnverified(tok, &c); err != nil {
		return "", "", errors.Join(ErrNoContact, err)
	}
	if a, ok := auth.FromContext(ctx); !ok || a.Subject != c.Subject {
		return "", "", ErrNoContact
	}
	if c.Email == "" && c.Phone == "" {
		return "", "", ErrNoContact
	}
	return c.Email, c.Phone, nil
}
