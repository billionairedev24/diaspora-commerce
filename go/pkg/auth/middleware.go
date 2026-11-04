package auth

import (
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
)

// Middleware authenticates requests. With a nil verifier (local profile) every request runs as LocalSuperAdmin, except
// customers, drivers and organisation members sending their token, who act as themselves (LocalActor).
// When optional is true, anonymous requests pass through without an actor (public reads such as search).
// A person's request with ActingAsHeader "CUSTOMER" (storefront, customer app) acts as a shopper (ActingAs).
func Middleware(v Verifier, optional bool) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if v == nil {
				actor := ActingAs(LocalActor(r.Header.Get("Authorization")), r.Header.Get(ActingAsHeader))
				next.ServeHTTP(w, r.WithContext(WithActor(r.Context(), actor)))
				return
			}
			h := r.Header.Get("Authorization")
			token, found := strings.CutPrefix(h, "Bearer ")
			if !found || token == "" {
				if optional {
					next.ServeHTTP(w, r)
					return
				}
				problem.Write(w, r, problem.New(http.StatusUnauthorized, "auth.unauthenticated", "Bearer token required"))
				return
			}
			actor, err := v.Verify(r.Context(), token)
			if err != nil {
				problem.Write(w, r, problem.New(http.StatusUnauthorized, "auth.unauthenticated", "Invalid or expired token"))
				return
			}
			next.ServeHTTP(w, r.WithContext(WithActor(r.Context(), ActingAs(actor, r.Header.Get(ActingAsHeader)))))
		})
	}
}

// Require wraps a handler so it only runs for actors holding permission.
func Require(permission string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		a, ok := FromContext(r.Context())
		if !ok {
			problem.Write(w, r, problem.New(http.StatusUnauthorized, "auth.unauthenticated", "Bearer token required"))
			return
		}
		if !a.Can(permission) {
			problem.Write(w, r, problem.New(http.StatusForbidden, "auth.missing-permission", "Missing permission "+permission))
			return
		}
		next(w, r)
	}
}
