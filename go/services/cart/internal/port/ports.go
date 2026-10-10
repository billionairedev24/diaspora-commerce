// Package port declares the cart service's outbound interfaces.
package port

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

// CartRepository stores carts with optimistic concurrency: Save fails with domain.ErrConflict when the stored
// version differs from cart.Version, and increments the version on success.
type CartRepository interface {
	Load(ctx context.Context, owner domain.Owner) (domain.Cart, error)
	Save(ctx context.Context, cart *domain.Cart) error
	Delete(ctx context.Context, owner domain.Owner) error
	Ping(ctx context.Context) error
}

// OfferReader returns the live offer (price, seller, fulfilment) for variant ATINs; missing keys are not live.
type OfferReader interface {
	Offers(ctx context.Context, variantATINs []string) (map[string]domain.Offer, error)
}

// AvailabilityReader returns sellable quantities per variant ATIN (inventory SKU).
type AvailabilityReader interface {
	Available(ctx context.Context, variantATINs []string) (map[string]int, error)
}
