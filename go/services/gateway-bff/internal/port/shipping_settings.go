package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// ShippingSettingsReader reads the staff-set shipping settings from orders (GET /v1/public/shipping-settings, no
// sign-in): the one flat delivery fee, its free-above threshold and the seller carrier switch (listing rules D16). Orders
// charges by the same record, so the estimate the shopper sees is what is charged.
type ShippingSettingsReader interface {
	Current(ctx context.Context, currency string) (domain.ShippingSettings, error)
}
