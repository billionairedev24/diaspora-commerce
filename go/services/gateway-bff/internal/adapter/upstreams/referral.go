package upstreams

import (
	"context"
	"net/url"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type referralImpl struct{ c httpclient.Client }

var _ port.ReferralClient = (*referralImpl)(nil)

// NewReferral returns the payments client for the shopper's referral code, offer, friends and earnings (customer token).
func NewReferral(c httpclient.Client) port.ReferralClient { return &referralImpl{c: c} }

func (p *referralImpl) Overview(ctx context.Context, bearer string) (port.UpReferralOverview, error) {
	var out port.UpReferralOverview
	// the first call creates the code: it is safe to repeat
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/referrals/me", Bearer: bearer}, &out)
	return out, err
}

func (p *referralImpl) Friends(ctx context.Context, bearer string, limit int) ([]port.UpMyReferral, error) {
	var out []port.UpMyReferral
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/referrals/me/friends", Query: url.Values{"limit": {strconv.Itoa(limit)}},
		Bearer: bearer}, &out)
	return out, err
}
