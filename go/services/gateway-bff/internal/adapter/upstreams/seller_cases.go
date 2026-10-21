package upstreams

import (
	"context"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type sellerCasesImpl struct{ returns, claims httpclient.Client }

var _ port.SellerCasesClient = (*sellerCasesImpl)(nil)

// NewSellerCases returns the SellerCasesClient over tax-returns and trust-ops.
func NewSellerCases(returns, claims httpclient.Client) port.SellerCasesClient {
	return &sellerCasesImpl{returns: returns, claims: claims}
}

func (s *sellerCasesImpl) Returns(ctx context.Context, bearer, status string) ([]port.UpRma, error) {
	q := url.Values{"limit": {"25"}}
	if status != "" {
		q.Set("status", status)
	}
	res, err := call(ctx, s.returns, httpclient.Request{Path: "/v1/returns", Query: q, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.UpRma](res.Body)
	return items, err
}

func (s *sellerCasesImpl) Claims(ctx context.Context, bearer, orgID, status string) ([]port.UpClaim, error) {
	q := url.Values{"size": {"25"}}
	if status != "" {
		q.Set("status", status)
	}
	res, err := call(ctx, s.claims, httpclient.Request{Path: "/v1/claims/seller/" + url.PathEscape(orgID), Query: q, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.UpClaim](res.Body)
	return items, err
}
