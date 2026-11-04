package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// SessionPolicyClient reads the effective sign-out policy per audience from identity
// (GET /v1/internal/session-policy, with the gateway-bff service token, scope sessions:policy.read).
type SessionPolicyClient interface {
	// Policies returns the policy of every audience identity knows. An error means identity could not be read.
	Policies(ctx context.Context) (map[domain.Audience]domain.SessionPolicy, error)
}
