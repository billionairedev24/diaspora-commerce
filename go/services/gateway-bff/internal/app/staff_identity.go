package app

import (
	"context"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffIdentityService is the identity admin console's token inspector and published documents (Developer tools →
// Identity & access, S291; contracts/openapi/gateway-bff-staff.yaml, tag Identity & access). The tables of that module are
// ordinary server-driven resources (staff_resources_identity.go).
type StaffIdentityService interface {
	// InspectToken decodes a pasted JWT and says whether its signature and issuer check out. The token goes to identity in
	// the request body only: it is not stored, cached or logged here, and it is not part of the answer.
	InspectToken(ctx context.Context, c *domain.Caller, token string) (map[string]any, error)
	// Documents are the OIDC discovery document and the JWKS (public keys only).
	Documents(ctx context.Context, c *domain.Caller) (map[string]any, error)
}

// maxInspectedToken matches identity's limit.
const maxInspectedToken = 16384

type staffIdentityImpl struct{ r *staffResolver }

var _ StaffIdentityService = (*staffIdentityImpl)(nil)

// NewStaffIdentityService returns the token inspector use case.
func NewStaffIdentityService(d StaffDeps) StaffIdentityService {
	return &staffIdentityImpl{r: newStaffResolver(d)}
}

func (s *staffIdentityImpl) InspectToken(ctx context.Context, c *domain.Caller, token string) (map[string]any, error) {
	sc, err := s.r.require(ctx, c, domain.IdentityModule, domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	if !sc.access.Can(domain.PermIdentityAdmin) {
		return nil, needsPermission(domain.PermIdentityAdmin)
	}
	if n := utf8.RuneCountInString(token); n == 0 || n > maxInspectedToken {
		return nil, domain.ValidationProblem(map[string]string{"token": "Paste a token of up to 16,384 characters"})
	}
	out := map[string]any{}
	if err := sc.post(ctx, sc.up.Identity, "/v1/admin/identity/token-inspector", map[string]any{"token": token}, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *staffIdentityImpl) Documents(ctx context.Context, c *domain.Caller) (map[string]any, error) {
	sc, err := s.r.require(ctx, c, domain.IdentityModule, domain.LevelView)
	if err != nil {
		return nil, err
	}
	out := map[string]any{}
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/identity/discovery", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}
