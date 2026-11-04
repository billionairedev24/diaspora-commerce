package port

import (
	"context"
	"encoding/json"
)

// PublicDeliveryFees reads what delivery costs from delivery-runs (GET /v1/public/delivery-fees?currency=, no
// sign-in): the staff-managed fee rules by method (ADR 0005 §3). The storefront estimates carrier shipping from the
// carrier rule so the cart shows what orders will charge.
type PublicDeliveryFees interface {
	// Rules answers {items: [{id, method, currency, version, bands, feeMinor, handlingMinor, fallbackMinor,
	// freeAboveMinor, ...}]}.
	Rules(ctx context.Context, currency string) (json.RawMessage, error)
}
