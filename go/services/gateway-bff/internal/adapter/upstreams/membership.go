package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type membershipImpl struct{ c httpclient.Client }

var _ port.MembershipClient = (*membershipImpl)(nil)

// NewMembership returns the payments client for the shopper's membership (S323).
func NewMembership(c httpclient.Client) port.MembershipClient { return &membershipImpl{c: c} }

func (p *membershipImpl) Tiers(ctx context.Context, bearer string) ([]port.UpMembershipTier, error) {
	var out []port.UpMembershipTier
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/memberships/tiers", Bearer: bearer}, &out)
	return out, err
}

func (p *membershipImpl) Me(ctx context.Context, bearer string) (port.UpMyMembership, error) {
	var out port.UpMyMembership
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/memberships/me", Bearer: bearer}, &out)
	return out, err
}

func (p *membershipImpl) Join(ctx context.Context, bearer, key string, req port.UpMembershipJoin) (port.UpMyMembership, error) {
	var out port.UpMyMembership
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/memberships/me/join", Bearer: bearer, JSON: req,
		Header: http.Header{"Idempotency-Key": {key}}, RetryUnsafe: true, Slow: true}, &out)
	return out, err
}

func (p *membershipImpl) Change(ctx context.Context, bearer, key string, req port.UpMembershipJoin) (port.UpMyMembership, error) {
	var out port.UpMyMembership
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/memberships/me/change", Bearer: bearer, JSON: req,
		Header: http.Header{"Idempotency-Key": {key}}, RetryUnsafe: true, Slow: true}, &out)
	return out, err
}

func (p *membershipImpl) Cancel(ctx context.Context, bearer string, refund bool) (port.UpMyMembership, error) {
	var out port.UpMyMembership
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/memberships/me/cancel", Bearer: bearer,
		JSON: map[string]bool{"refund": refund}, RetryUnsafe: true, Slow: true}, &out)
	return out, err
}

func (p *membershipImpl) Resume(ctx context.Context, bearer string) (port.UpMyMembership, error) {
	var out port.UpMyMembership
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/memberships/me/resume", Bearer: bearer, RetryUnsafe: true}, &out)
	return out, err
}

func (p *membershipImpl) Uses(ctx context.Context, bearer string, limit int) ([]port.UpMembershipUse, error) {
	var out []port.UpMembershipUse
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/memberships/me/uses", Query: url.Values{"limit": {strconv.Itoa(limit)}}, Bearer: bearer}, &out)
	return out, err
}

func (p *membershipImpl) Savings(ctx context.Context, bearer string, req port.UpMembershipSavingsRequest) (port.UpMembershipWaiverQuote, error) {
	var out port.UpMembershipWaiverQuote
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/memberships/me/savings", Bearer: bearer, JSON: req}, &out)
	return out, err
}
