package upstreams

import (
	"context"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type orderPaymentsImpl struct{ c httpclient.Client }

var _ port.OrderPaymentsClient = (*orderPaymentsImpl)(nil)

// NewOrderPayments returns the payments client for an order's charged and refunded amounts.
func NewOrderPayments(c httpclient.Client) port.OrderPaymentsClient { return &orderPaymentsImpl{c: c} }

func (p *orderPaymentsImpl) ByOrder(ctx context.Context, bearer, orderID string) (port.UpOrderPayment, error) {
	var out port.UpOrderPayment
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/payments", Query: url.Values{"orderId": {orderID}}, Bearer: bearer}, &out)
	return out, err
}
