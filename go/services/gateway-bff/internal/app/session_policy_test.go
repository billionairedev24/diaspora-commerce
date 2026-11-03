package app

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

type policyClient struct {
	policies map[domain.Audience]domain.SessionPolicy
	err      error
	calls    int
}

func (p *policyClient) Policies(context.Context) (map[domain.Audience]domain.SessionPolicy, error) {
	p.calls++
	return p.policies, p.err
}

func newPolicies(c *policyClient) (SessionPolicies, *time.Time) {
	now := t0
	return NewSessionPolicies(c, SessionPoliciesOptions{Clock: func() time.Time { return now }}), &now
}

func TestPoliciesAreCachedForSixtySeconds(t *testing.T) {
	c := &policyClient{policies: map[domain.Audience]domain.SessionPolicy{
		domain.AudienceStaff: {Idle: 10 * time.Minute, Absolute: 4 * time.Hour, Warn: 30 * time.Second}}}
	p, now := newPolicies(c)
	if got := p.Policy(context.Background(), domain.AudienceStaff); got.Idle != 10*time.Minute {
		t.Fatalf("identity's policy must apply, got %+v", got)
	}
	*now = now.Add(59 * time.Second)
	p.Policy(context.Background(), domain.AudienceStaff)
	if c.calls != 1 {
		t.Fatalf("calls within 60 s = %d", c.calls)
	}
	c.policies[domain.AudienceStaff] = domain.SessionPolicy{Idle: 5 * time.Minute, Absolute: time.Hour}
	*now = now.Add(2 * time.Second)
	if got := p.Policy(context.Background(), domain.AudienceStaff); got.Idle != 5*time.Minute || c.calls != 2 {
		t.Fatalf("after 60 s the change must be read: %+v, %d calls", got, c.calls)
	}
}

func TestAnAudienceIdentityDoesNotSendUsesItsDefault(t *testing.T) {
	c := &policyClient{policies: map[domain.Audience]domain.SessionPolicy{}}
	p, _ := newPolicies(c)
	if got := p.Policy(context.Background(), domain.AudienceSeller); got != domain.DefaultSessionPolicy(domain.AudienceSeller) {
		t.Fatalf("got %+v", got)
	}
}

func TestUnusableValuesFromIdentityFallBackToTheDefault(t *testing.T) {
	c := &policyClient{policies: map[domain.Audience]domain.SessionPolicy{
		domain.AudienceStaff: {Idle: 0, Absolute: time.Hour}, domain.AudienceSeller: {Idle: time.Hour, Absolute: time.Hour, Warn: time.Hour}}}
	p, _ := newPolicies(c)
	for _, a := range []domain.Audience{domain.AudienceStaff, domain.AudienceSeller} {
		if got := p.Policy(context.Background(), a); got != domain.DefaultSessionPolicy(a) {
			t.Fatalf("%s: %+v", a, got)
		}
	}
}

func TestIdentityDownFromTheStartAppliesTheBuiltInDefaults(t *testing.T) {
	c := &policyClient{err: errors.New("identity down")}
	p, now := newPolicies(c)
	for _, a := range domain.Audiences {
		if got := p.Policy(context.Background(), a); got != domain.DefaultSessionPolicy(a) {
			t.Fatalf("%s: %+v", a, got)
		}
	}
	if got := p.Policy(context.Background(), domain.AudienceStaff); got.Idle != 15*time.Minute || got.Absolute != 8*time.Hour {
		t.Fatalf("staff must fall back to the short default, got %+v", got)
	}
	// A failed read is retried after 10 s, not on every request.
	*now = now.Add(5 * time.Second)
	p.Policy(context.Background(), domain.AudienceStaff)
	if c.calls != 1 {
		t.Fatalf("retried too early: %d calls", c.calls)
	}
	*now = now.Add(6 * time.Second)
	p.Policy(context.Background(), domain.AudienceStaff)
	if c.calls != 2 {
		t.Fatalf("not retried after 10 s: %d calls", c.calls)
	}
}

func TestIdentityDownAppliesTheStricterOfLastPolicyAndDefault(t *testing.T) {
	c := &policyClient{policies: map[domain.Audience]domain.SessionPolicy{
		domain.AudienceStaff:  {Idle: 2 * time.Hour, Absolute: 3 * time.Hour, Warn: time.Minute},
		domain.AudienceSeller: {Idle: 5 * time.Minute, Absolute: 24 * time.Hour, Warn: 10 * time.Second}}}
	p, now := newPolicies(c)
	p.Policy(context.Background(), domain.AudienceStaff)
	c.err = errors.New("identity down")
	*now = now.Add(2 * time.Minute)
	if got := p.Policy(context.Background(), domain.AudienceStaff); got.Idle != 15*time.Minute || got.Absolute != 3*time.Hour {
		t.Fatalf("a lenient last policy must not outlive the default: %+v", got)
	}
	if got := p.Policy(context.Background(), domain.AudienceSeller); got.Idle != 5*time.Minute || got.Absolute != 12*time.Hour || got.Warn != 10*time.Second {
		t.Fatalf("a stricter last policy must stay: %+v", got)
	}
	c.err = nil
	*now = now.Add(time.Minute)
	if got := p.Policy(context.Background(), domain.AudienceStaff); got.Idle != 2*time.Hour {
		t.Fatalf("identity back: %+v", got)
	}
}

func TestNoClientServesTheDefaults(t *testing.T) {
	p := NewSessionPolicies(nil, SessionPoliciesOptions{})
	if got := p.Policy(context.Background(), domain.AudienceDriver); got.Idle != 14*time.Hour {
		t.Fatalf("got %+v", got)
	}
}
