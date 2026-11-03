package upstreams

import (
	"context"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type checkoutCouponsImpl struct{ c httpclient.Client }

var _ port.CheckoutCouponsClient = (*checkoutCouponsImpl)(nil)

// NewCheckoutCoupons prices coupons at checkout (offers POST /v1/coupons/validate) with the shopper's bearer.
func NewCheckoutCoupons(c httpclient.Client) port.CheckoutCouponsClient {
	return &checkoutCouponsImpl{c: c}
}

func (o *checkoutCouponsImpl) Validate(ctx context.Context, bearer string, req port.CouponValidateRequest) (port.CouponQuote, error) {
	var out port.CouponQuote
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/coupons/validate", Bearer: bearer, JSON: req}, &out)
	return out, err
}
