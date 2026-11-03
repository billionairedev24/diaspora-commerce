package upstreams

import (
	"context"
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// sessionPolicyScope is the scope of the gateway-bff client that identity requires for the policy read.
const sessionPolicyScope = "sessions:policy.read"

type sessionPolicyImpl struct {
	c      httpclient.Client
	tokens port.ServiceTokens
}

var _ port.SessionPolicyClient = (*sessionPolicyImpl)(nil)

// NewSessionPolicy returns the identity client for the sign-out policy.
func NewSessionPolicy(c httpclient.Client, tokens port.ServiceTokens) port.SessionPolicyClient {
	return &sessionPolicyImpl{c: c, tokens: tokens}
}

type upSessionPolicies struct {
	Audiences []struct {
		Audience               string `json:"audience"`
		IdleTimeoutMinutes     int64  `json:"idleTimeoutMinutes"`
		AbsoluteTimeoutMinutes int64  `json:"absoluteTimeoutMinutes"`
		WarnSeconds            int64  `json:"warnSeconds"`
	} `json:"audiences"`
}

func (s *sessionPolicyImpl) Policies(ctx context.Context) (map[domain.Audience]domain.SessionPolicy, error) {
	bearer, err := s.tokens.Token(ctx, sessionPolicyScope)
	if err != nil {
		return nil, fmt.Errorf("session policy token: %w", err)
	}
	var out upSessionPolicies
	if _, err := call(ctx, s.c, httpclient.Request{Path: "/v1/internal/session-policy", Bearer: bearer}, &out); err != nil {
		return nil, err
	}
	policies := make(map[domain.Audience]domain.SessionPolicy, len(out.Audiences))
	for _, a := range out.Audiences {
		audience, ok := domain.ParseAudience(a.Audience)
		if !ok {
			continue
		}
		policies[audience] = domain.SessionPolicy{Idle: time.Duration(a.IdleTimeoutMinutes) * time.Minute,
			Absolute: time.Duration(a.AbsoluteTimeoutMinutes) * time.Minute, Warn: time.Duration(a.WarnSeconds) * time.Second}
	}
	return policies, nil
}
