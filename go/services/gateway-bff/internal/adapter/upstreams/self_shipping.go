package upstreams

import (
	"context"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type selfShippingImpl struct{ c httpclient.Client }

var _ port.SelfShippingClient = (*selfShippingImpl)(nil)

// NewSelfShipping returns the orders client for the sellers that ship themselves (public read, no sign-in).
func NewSelfShipping(c httpclient.Client) port.SelfShippingClient { return &selfShippingImpl{c: c} }

func (s *selfShippingImpl) Offered(ctx context.Context, currency string, orgIDs []string) (map[string]int64, error) {
	out := map[string]int64{}
	if len(orgIDs) == 0 {
		return out, nil
	}
	var body struct {
		Items []struct {
			SellerOrgID string `json:"sellerOrgId"`
			Enabled     bool   `json:"enabled"`
			ChargeMinor int64  `json:"chargeMinor"`
		} `json:"items"`
	}
	if _, err := call(ctx, s.c, httpclient.Request{Path: "/v1/public/self-shipping",
		Query: url.Values{"orgs": {strings.Join(orgIDs, ",")}, "currency": {currency}}}, &body); err != nil {
		return nil, err
	}
	for _, it := range body.Items {
		// A charge outside orders' own ceiling is a corrupt answer, never a price.
		if it.Enabled && it.ChargeMinor >= 0 && it.ChargeMinor <= maxShippingFeeMinor {
			out[it.SellerOrgID] = it.ChargeMinor
		}
	}
	return out, nil
}
