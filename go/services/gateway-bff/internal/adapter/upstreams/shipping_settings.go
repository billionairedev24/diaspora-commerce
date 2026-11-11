package upstreams

import (
	"context"
	"fmt"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// maxShippingFeeMinor mirrors orders' ShippingSettings.MAX_FEE_MINOR: a larger value is a corrupt answer, never a price.
const maxShippingFeeMinor = 10_000

type shippingSettingsImpl struct{ c httpclient.Client }

var _ port.ShippingSettingsReader = (*shippingSettingsImpl)(nil)

// NewShippingSettings returns the orders client for the public shipping settings.
func NewShippingSettings(c httpclient.Client) port.ShippingSettingsReader {
	return &shippingSettingsImpl{c: c}
}

func (s *shippingSettingsImpl) Current(ctx context.Context, currency string) (domain.ShippingSettings, error) {
	var out struct {
		Currency                     string `json:"currency"`
		Version                      int64  `json:"version"`
		FlatFeeMinor                 *int64 `json:"flatFeeMinor"`
		FreeAboveMinor               *int64 `json:"freeAboveMinor"`
		SellerCarrierShippingEnabled bool   `json:"sellerCarrierShippingEnabled"`
	}
	if _, err := call(ctx, s.c, httpclient.Request{Path: "/v1/public/shipping-settings", Query: url.Values{"currency": {currency}}}, &out); err != nil {
		return domain.ShippingSettings{}, err
	}
	// A missing or absurd fee is an error, not zero: the caller keeps its last known value (or shows no price).
	if out.FlatFeeMinor == nil || *out.FlatFeeMinor < 0 || *out.FlatFeeMinor > maxShippingFeeMinor {
		return domain.ShippingSettings{}, fmt.Errorf("orders shipping settings: no usable flatFeeMinor")
	}
	if out.FreeAboveMinor != nil && *out.FreeAboveMinor <= 0 {
		return domain.ShippingSettings{}, fmt.Errorf("orders shipping settings: freeAboveMinor must be above 0")
	}
	return domain.ShippingSettings{Currency: out.Currency, Version: out.Version, FlatFeeMinor: *out.FlatFeeMinor,
		FreeAboveMinor: out.FreeAboveMinor, SellerCarrierShippingEnabled: out.SellerCarrierShippingEnabled}, nil
}
