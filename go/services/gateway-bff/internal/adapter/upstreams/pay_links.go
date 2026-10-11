package upstreams

import (
	"context"
	"fmt"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// payLinkScope is the client-credentials scope orders requires for pay-now links.
const payLinkScope = "orders:pay-link"

type payLinksImpl struct {
	c      httpclient.Client
	tokens port.ServiceTokens
}

var _ port.PayLinkClient = (*payLinksImpl)(nil)

// NewPayLinks returns the orders client for pay-now links (service token, token in the body).
func NewPayLinks(c httpclient.Client, tokens port.ServiceTokens) port.PayLinkClient {
	return &payLinksImpl{c: c, tokens: tokens}
}

func (p *payLinksImpl) Lookup(ctx context.Context, orderNumber, token string) (port.UpPayLink, error) {
	return p.post(ctx, orderNumber, "lookup", token)
}

func (p *payLinksImpl) Confirm(ctx context.Context, orderNumber, token string) (port.UpPayLink, error) {
	return p.post(ctx, orderNumber, "confirm", token)
}

func (p *payLinksImpl) post(ctx context.Context, orderNumber, action, token string) (port.UpPayLink, error) {
	var out port.UpPayLink
	bearer, err := p.tokens.Token(ctx, payLinkScope)
	if err != nil {
		return out, fmt.Errorf("pay link token: %w", err)
	}
	// Lookup and confirm are idempotent at orders (confirm only places an authorised order once).
	_, err = call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/service/pay-links/" + esc(orderNumber) + "/" + action,
		Bearer: bearer, JSON: map[string]string{"token": token}, RetryUnsafe: true}, &out)
	return out, err
}
