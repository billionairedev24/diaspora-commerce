package app

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SessionPolicies serves the sign-out policy per audience.
type SessionPolicies interface {
	// Policy is the policy of an audience. It never fails and never means "no timeout": when identity cannot be
	// read, the stricter of the last value read and the built-in default applies until identity answers again.
	Policy(ctx context.Context, a domain.Audience) domain.SessionPolicy
}

// SessionPoliciesOptions configure the cache.
type SessionPoliciesOptions struct {
	// TTL is how long a policy read stays fresh (default 60 s): a staff change reaches every replica within it.
	TTL time.Duration
	// RetryAfter is how long to wait after a failed read before asking identity again (default 10 s).
	RetryAfter time.Duration
	// FetchTimeout bounds one read of identity, which requests wait for (default 2 s).
	FetchTimeout time.Duration
	Clock        Clock
	Log          *slog.Logger
}

type sessionPoliciesImpl struct {
	client port.SessionPolicyClient
	o      SessionPoliciesOptions

	mu         sync.Mutex
	current    map[domain.Audience]domain.SessionPolicy
	lastGood   map[domain.Audience]domain.SessionPolicy
	validUntil time.Time
}

var _ SessionPolicies = (*sessionPoliciesImpl)(nil)

// NewSessionPolicies returns the cached policy reader. A nil client serves the built-in defaults only.
func NewSessionPolicies(client port.SessionPolicyClient, o SessionPoliciesOptions) SessionPolicies {
	if o.TTL <= 0 {
		o.TTL = time.Minute
	}
	if o.RetryAfter <= 0 {
		o.RetryAfter = 10 * time.Second
	}
	if o.FetchTimeout <= 0 {
		o.FetchTimeout = 2 * time.Second
	}
	if o.Log == nil {
		o.Log = slog.Default()
	}
	return &sessionPoliciesImpl{client: client, o: o}
}

func (p *sessionPoliciesImpl) Policy(ctx context.Context, a domain.Audience) domain.SessionPolicy {
	now := p.o.Clock.now()
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.current == nil || now.After(p.validUntil) {
		p.refresh(ctx, now)
	}
	if pol, ok := p.current[a]; ok {
		return pol
	}
	return domain.DefaultSessionPolicy(a)
}

// refresh reads identity once (callers wait on the lock, so one request asks for all) and swaps the served policy.
func (p *sessionPoliciesImpl) refresh(ctx context.Context, now time.Time) {
	var fetched map[domain.Audience]domain.SessionPolicy
	var err error
	if p.client != nil {
		fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), p.o.FetchTimeout)
		fetched, err = p.client.Policies(fctx)
		cancel()
	}
	if p.client == nil || err != nil {
		if err != nil {
			p.o.Log.WarnContext(ctx, "session policy unavailable; applying the stricter of the last policy and the defaults", "error", err)
		}
		p.current = failSafe(p.lastGood)
		p.validUntil = now.Add(p.o.RetryAfter)
		return
	}
	served := make(map[domain.Audience]domain.SessionPolicy, len(domain.Audiences))
	for _, a := range domain.Audiences {
		pol, ok := fetched[a]
		if !ok || !pol.Valid() {
			if ok {
				p.o.Log.WarnContext(ctx, "identity sent an unusable session policy; using the default", "audience", string(a))
			}
			pol = domain.DefaultSessionPolicy(a)
		}
		served[a] = pol
	}
	p.current, p.lastGood = served, served
	p.validUntil = now.Add(p.o.TTL)
}

// failSafe is the stricter of the last policy read and the default, per audience.
func failSafe(lastGood map[domain.Audience]domain.SessionPolicy) map[domain.Audience]domain.SessionPolicy {
	out := make(map[domain.Audience]domain.SessionPolicy, len(domain.Audiences))
	for _, a := range domain.Audiences {
		pol := domain.DefaultSessionPolicy(a)
		if last, ok := lastGood[a]; ok {
			pol = pol.Shorter(last)
		}
		out[a] = pol
	}
	return out
}
