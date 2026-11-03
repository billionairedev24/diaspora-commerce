// Package port declares the cart service's outbound interfaces.
package port

import (
	"context"
	"errors"

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

// LiveOffer is one offers-service offer as the cart needs it: seller, fulfilment, price, sellable units and whether
// it can be bought now (paused, out of stock, product not live or seller suppressed ⇒ not buyable).
type LiveOffer struct {
	OfferID     string
	VariantATIN string
	ProductATIN string
	SellerOrgID string
	SellerName  string
	SellerKind  string
	Fulfilment  string
	PriceMinor  int64
	Currency    string
	Available   int
	Buyable     bool
	// ShipFromLocationID is the seller pickup location of an AFROTRANSACT_DELIVERY offer (empty otherwise).
	ShipFromLocationID string
}

// ErrOfferNotFound is returned by OfferLookup when the offer is unknown or withdrawn.
var ErrOfferNotFound = errors.New("offer not found")

// OfferLookup reads one offer from the offers service (GET /v1/public/offers/{offerId}).
type OfferLookup interface {
	Offer(ctx context.Context, offerID string) (LiveOffer, error)
}

// PreorderEligibility asks delivery-runs (GET /v1/service/preorders/eligibility, S272) which variants are sold as a
// pre-order at the shopper's delivery location, in which window and stage, with the window's fees (minor units) and
// payment methods. Variants it does not answer for are missing (they ship now).
type PreorderEligibility interface {
	Eligibility(ctx context.Context, loc domain.Location, variantATINs []string) (map[string]domain.PreorderLine, error)
}

// DeliveryRuns lists the open delivery runs staff scheduled that cover a ZIP (delivery-runs
// GET /v1/public/runs/available, S261), earliest cutoff first. Public: no token.
type DeliveryRuns interface {
	OpenRuns(ctx context.Context, zip string) ([]domain.OpenRun, error)
}
