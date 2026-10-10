package upstreams

import (
	"context"
	"fmt"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Service-token scopes of the checkout quote (identity's gateway-bff client must be granted them).
const (
	scopeShipFromRead = "sellers:ship-from.read"
	scopeFeesRead     = "payments:fees.read"
)

type localDeliveryImpl struct {
	seller, geo, payments httpclient.Client
	tokens                port.ServiceTokens
}

var _ port.LocalDeliveryClient = (*localDeliveryImpl)(nil)

// NewLocalDelivery returns the checkout client for AfroTransact Delivery from the seller's store (S255): seller
// GET /v1/ship-from-locations/{id}, geo POST /v1/distance and payments POST /v1/delivery-pricing/quote.
func NewLocalDelivery(seller, geo, payments httpclient.Client, tokens port.ServiceTokens) port.LocalDeliveryClient {
	return &localDeliveryImpl{seller: seller, geo: geo, payments: payments, tokens: tokens}
}

func (l *localDeliveryImpl) token(ctx context.Context, scope string) (string, error) {
	if l.tokens == nil {
		return "", nil
	}
	t, err := l.tokens.Token(ctx, scope)
	if err != nil {
		return "", fmt.Errorf("local delivery token %s: %w", scope, err)
	}
	return t, nil
}

func (l *localDeliveryImpl) Location(ctx context.Context, id string) (port.PickupLocationFacts, error) {
	var out port.PickupLocationFacts
	bearer, err := l.token(ctx, scopeShipFromRead)
	if err != nil {
		return out, err
	}
	if _, err := call(ctx, l.seller, httpclient.Request{Path: "/v1/ship-from-locations/" + url.PathEscape(id), Bearer: bearer}, &out); err != nil {
		return out, err
	}
	out.PickupEnabled = out.Pickup != nil && out.Pickup.Enabled
	return out, nil
}

func (l *localDeliveryImpl) Distance(ctx context.Context, from, to port.GeoPlace) (port.RoadDistance, error) {
	var out port.RoadDistance
	_, err := call(ctx, l.geo, httpclient.Request{Method: http.MethodPost, Path: "/v1/distance", RetryUnsafe: true,
		JSON: map[string]any{"from": from, "to": to}}, &out)
	return out, err
}

func (l *localDeliveryImpl) Quote(ctx context.Context, sellerOrgID string, miles float64, packages int) (port.LocalDeliveryQuote, error) {
	var out port.LocalDeliveryQuote
	bearer, err := l.token(ctx, scopeFeesRead)
	if err != nil {
		return out, err
	}
	_, err = call(ctx, l.payments, httpclient.Request{Method: http.MethodPost, Path: "/v1/delivery-pricing/quote", Bearer: bearer, RetryUnsafe: true,
		JSON: map[string]any{"sellerOrgId": sellerOrgID, "distanceMiles": miles, "packages": max(1, packages)}}, &out)
	return out, err
}
