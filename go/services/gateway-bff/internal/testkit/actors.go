package testkit

import (
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
)

// actorOf resolves the caller the way the real services do (go/pkg/auth, platform-security): the token's actor
// (pt, org, roles), acting as a shopper when the request says X-Acting-As: CUSTOMER.
func actorOf(r *http.Request) auth.Actor {
	return auth.ActingAs(auth.LocalActor(r.Header.Get("Authorization")), r.Header.Get(auth.ActingAsHeader))
}

// customerSubject is the signed-in shopper's id, or "" when the caller is not acting as a customer (cart then
// treats it as a guest, like the real cart service).
func customerSubject(r *http.Request) string {
	if a := actorOf(r); a.Type == "CUSTOMER" {
		return a.Subject
	}
	return ""
}

// customerOnly answers 401 without a token and 403 (code) unless the caller acts as a customer, like the real
// payments (payments.customer-only), orders (orders.customer-only) and bookings customer endpoints.
func customerOnly(code string, fn http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		if actorOf(r).Type != "CUSTOMER" {
			problem(w, http.StatusForbidden, code, "Only shoppers can do this", nil)
			return
		}
		fn(w, r)
	}
}
