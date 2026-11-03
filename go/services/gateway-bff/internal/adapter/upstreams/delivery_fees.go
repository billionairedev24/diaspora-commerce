package upstreams

import (
	"context"
	"encoding/json"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// publicDeliveryFeesImpl calls delivery-runs' public delivery fee rules (tag Delivery fees).
type publicDeliveryFeesImpl struct{ c httpclient.Client }

var _ port.PublicDeliveryFees = (*publicDeliveryFeesImpl)(nil)

// NewPublicDeliveryFees returns the delivery-runs client for the public delivery fee rules.
func NewPublicDeliveryFees(c httpclient.Client) port.PublicDeliveryFees {
	return &publicDeliveryFeesImpl{c: c}
}

func (p *publicDeliveryFeesImpl) Rules(ctx context.Context, currency string) (json.RawMessage, error) {
	res, err := call(ctx, p.c, httpclient.Request{Path: "/v1/public/delivery-fees", Query: url.Values{"currency": {currency}}}, nil)
	if err != nil {
		return nil, err
	}
	body := json.RawMessage(res.Body)
	if !json.Valid(body) {
		body = json.RawMessage("{}")
	}
	return body, nil
}
