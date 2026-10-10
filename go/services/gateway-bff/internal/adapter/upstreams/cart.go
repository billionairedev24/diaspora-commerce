package upstreams

import (
	"context"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// GuestCartHeader carries the guest cart id between the BFF, the cart service and the app.
const GuestCartHeader = "X-Guest-Cart"

type cartImpl struct{ c httpclient.Client }

var _ port.CartClient = (*cartImpl)(nil)

// NewCart returns the cart-service client.
func NewCart(c httpclient.Client) port.CartClient { return &cartImpl{c: c} }

func (k *cartImpl) do(ctx context.Context, o port.CartOwner, method, path string, body any) (port.UpstreamCart, error) {
	h := http.Header{}
	if o.GuestID != "" {
		h.Set(GuestCartHeader, o.GuestID)
	}
	var out port.UpstreamCart
	res, err := call(ctx, k.c, httpclient.Request{Method: method, Path: path, Header: h, Bearer: o.BearerToken, JSON: body}, &out)
	if err != nil {
		return out, err
	}
	out.GuestID = res.Header.Get(GuestCartHeader)
	return out, nil
}

func item(atin string) string { return "/v1/cart/items/" + url.PathEscape(atin) }

func (k *cartImpl) Get(ctx context.Context, o port.CartOwner) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodGet, "/v1/cart", nil)
}

func (k *cartImpl) Add(ctx context.Context, o port.CartOwner, atin string, qty int) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodPost, "/v1/cart/items", map[string]any{"variantAtin": atin, "quantity": qty})
}

func (k *cartImpl) SetQuantity(ctx context.Context, o port.CartOwner, atin string, qty int) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodPut, item(atin), map[string]any{"quantity": qty})
}

func (k *cartImpl) Remove(ctx context.Context, o port.CartOwner, atin string) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodDelete, item(atin), nil)
}

func (k *cartImpl) SaveForLater(ctx context.Context, o port.CartOwner, atin string) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodPost, item(atin)+"/save-for-later", nil)
}

func (k *cartImpl) MoveToCart(ctx context.Context, o port.CartOwner, atin string) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodPost, "/v1/cart/saved/"+url.PathEscape(atin)+"/move-to-cart", nil)
}

func (k *cartImpl) Merge(ctx context.Context, o port.CartOwner) (port.UpstreamCart, error) {
	return k.do(ctx, o, http.MethodPost, "/v1/cart/merge", nil)
}
