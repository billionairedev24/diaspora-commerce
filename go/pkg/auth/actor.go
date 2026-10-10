// Package auth verifies identity-service JWTs and exposes the calling Actor to handlers.
package auth

import (
	"context"
	"slices"
)

// Actor is the authenticated caller (mirrors com.afrotransact.platform.security.Actor in Java).
type Actor struct {
	Subject     string
	Type        string // CUSTOMER, SELLER_MEMBER, PROVIDER_MEMBER, STAFF, DRIVER, PARTNER_APP, AI_AGENT, SERVICE
	OrgID       string
	Roles       []string
	Permissions []string
}

// Can reports whether the actor holds permission (or the "*" wildcard granted to local super admins).
func (a Actor) Can(permission string) bool {
	return slices.Contains(a.Permissions, permission) || slices.Contains(a.Permissions, "*")
}

// IsStaff reports whether the actor is platform staff or another service.
func (a Actor) IsStaff() bool { return a.Type == "STAFF" || a.Type == "SERVICE" }

type ctxKey struct{}

// WithActor stores the actor in the context.
func WithActor(ctx context.Context, a Actor) context.Context {
	return context.WithValue(ctx, ctxKey{}, a)
}

// FromContext returns the actor, if the request was authenticated.
func FromContext(ctx context.Context) (Actor, bool) {
	a, ok := ctx.Value(ctxKey{}).(Actor)
	return a, ok
}

// LocalSuperAdmin is the actor used when authentication is disabled in the local profile.
func LocalSuperAdmin() Actor {
	return Actor{Subject: "local-dev", Type: "STAFF", Roles: []string{"platform.super_admin"}, Permissions: []string{"*"}}
}
