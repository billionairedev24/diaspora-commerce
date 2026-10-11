package auth

import (
	"slices"
	"strings"

	"github.com/golang-jwt/jwt/v5"
)

// actsAsThemselves are the principal types that keep their own identity when authentication is off: their data is
// keyed by who they are (a shopper's cart and orders, a driver's routes, an organisation's shipments).
var actsAsThemselves = []string{"CUSTOMER", "DRIVER", "SELLER_MEMBER", "PROVIDER_MEMBER"}

// LocalActor is the actor of a request when authentication is disabled (local profile, nil verifier): the local super
// admin, unless the request carries an identity-issued token of a customer, a driver or a seller / provider member,
// who then acts as themselves (and their organisation) with the token's own permissions. The claims are read without
// checking the signature: with authentication off every caller is already the super admin, so reading the token can
// only lower what the caller may do, never raise it.
// Mirrors com.afrotransact.platform.security.LocalActorResolver.
func LocalActor(authorization string) Actor {
	if len(authorization) < 7 || !strings.EqualFold(authorization[:7], "Bearer ") {
		return LocalSuperAdmin()
	}
	var c jwt.MapClaims
	if _, _, err := jwt.NewParser().ParseUnverified(strings.TrimSpace(authorization[7:]), &c); err != nil {
		return LocalSuperAdmin()
	}
	pt := strings.ToUpper(strings.TrimSpace(claimString(c["pt"])))
	sub := claimString(c["sub"])
	if !slices.Contains(actsAsThemselves, pt) || strings.TrimSpace(sub) == "" {
		return LocalSuperAdmin()
	}
	perms := append(claimStrings(c["perms"]), claimStrings(c["scope"])...)
	perms = slices.DeleteFunc(perms, func(p string) bool { return p == "*" })
	return Actor{Subject: sub, Type: pt, OrgID: claimString(c["org"]), Roles: claimStrings(c["roles"]), Permissions: perms}
}

func claimString(v any) string {
	s, _ := v.(string)
	return s
}

// claimStrings reads a list claim, or a space-separated string claim (scope).
func claimStrings(v any) []string {
	switch t := v.(type) {
	case []any:
		out := make([]string, 0, len(t))
		for _, x := range t {
			if s, ok := x.(string); ok {
				out = append(out, s)
			}
		}
		return out
	case string:
		return strings.Fields(t)
	default:
		return nil
	}
}
