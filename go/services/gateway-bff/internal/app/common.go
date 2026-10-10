// Package app holds the BFF use cases: sessions and sign-in, site/home, location, search, product detail, cart,
// account and checkout. Each is an exported interface with an unexported implementation; constructors return the
// interface. Use cases compose upstream ports into the screen shapes of contracts/openapi/gateway-bff.yaml and
// translate upstream failures into the contract's problem codes.
package app

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Clock returns the current time (tests pin it).
type Clock func() time.Time

func (c Clock) now() time.Time {
	if c == nil {
		return time.Now()
	}
	return c()
}

// mapErr turns an upstream error into a contract problem. Known problems pass through; unavailability becomes
// 503 UPSTREAM_UNAVAILABLE; generic 4xx answers keep their meaning; anything else is a 502.
func mapErr(upstream string, err error) error {
	if err == nil {
		return nil
	}
	if _, ok := domain.AsProblem(err); ok {
		return err
	}
	if errors.Is(err, context.Canceled) {
		return err
	}
	if errors.Is(err, domain.ErrUpstreamUnavailable) || errors.Is(err, context.DeadlineExceeded) {
		return domain.UpstreamUnavailable(upstream).WithCause(err)
	}
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch p.Status {
		case http.StatusUnauthorized:
			return domain.Unauthenticated().WithCause(err)
		case http.StatusForbidden:
			return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "You can't do that.").WithCause(err)
		case http.StatusNotFound:
			return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that.").WithCause(err)
		case http.StatusTooManyRequests:
			pr := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many requests", "Please wait a moment and try again.")
			pr.RetryAfter = max(1, p.RetryAfter)
			return pr.WithCause(err)
		case http.StatusUnprocessableEntity, http.StatusBadRequest:
			if len(p.Fields) > 0 {
				return domain.ValidationProblem(p.Fields).WithCause(err)
			}
			detail := p.Detail
			if detail == "" {
				detail = "One or more fields are invalid."
			}
			pr := domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeValidationFailed, "Validation failed", detail)
			return pr.WithCause(err)
		case http.StatusConflict:
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Conflict", firstNonEmpty(p.Detail, "That conflicts with the current state.")).WithCause(err)
		}
		return domain.UpstreamError(upstream, p.Status).WithCause(err)
	}
	return domain.UpstreamError(upstream, 0).WithCause(err)
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func ptr[T any](v T) *T { return &v }

func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// randomID returns n random bytes base64url-encoded (session ids, OIDC state/nonce/verifier, challenge ids).
func randomID(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// SellerDirectory names sellers and decides fulfilment until the seller and offers services expose them.
type SellerDirectory struct {
	RetailOrgID string
	Names       map[string]string
}

// PlatformSellerName is AfroTransact's first-party retail business.
const PlatformSellerName = "AfroTransact Retail"

// Describe returns the seller (kind, display name) and fulfilment for a product owner — the same rule the cart
// service applies: AfroTransact Retail (PLATFORM) is fulfilled by AfroTransact, marketplace sellers ship themselves.
func (d SellerDirectory) Describe(ownerKind, sellerOrgID string) (domain.Seller, string) {
	if strings.EqualFold(ownerKind, domain.SellerKindPlatform) || (sellerOrgID != "" && sellerOrgID == d.RetailOrgID) {
		id := sellerOrgID
		if id == "" {
			id = "afrotransact"
		}
		return domain.Seller{ID: id, Name: PlatformSellerName, Kind: domain.SellerKindPlatform, City: ""}, domain.FulfilledByAfroTransact
	}
	name := d.Names[sellerOrgID]
	if name == "" {
		name = "Marketplace seller"
	}
	id := sellerOrgID
	if id == "" {
		id = "seller"
	}
	return domain.Seller{ID: id, Name: name, Kind: domain.SellerKindSeller, City: ""}, domain.ShipsFromSeller
}

func normFulfilment(f string) string {
	if f == domain.FulfilledByAfroTransact {
		return f
	}
	return domain.ShipsFromSeller
}

func normSellerKind(k string) string {
	if k == domain.SellerKindPlatform {
		return k
	}
	return domain.SellerKindSeller
}

// offerFromPublic maps an offers-service offer (with its delivery promise) onto the contract's Offer.
func offerFromPublic(o port.PublicOffer, delivery domain.DeliveryPromise) domain.Offer {
	kind := normSellerKind(o.Seller.Kind)
	qty := max(0, o.AvailableQty)
	status := o.StockStatus
	if status != domain.InStock && status != domain.LowStock && status != domain.OutOfStock {
		status = domain.StockStatusFor(qty)
	}
	var list *int64
	if o.ListPriceMinor != nil && *o.ListPriceMinor > o.PriceMinor {
		list = o.ListPriceMinor
	}
	return domain.Offer{OfferID: o.OfferID, Seller: domain.Seller{ID: firstNonEmpty(o.Seller.ID, "seller"),
		Name: firstNonEmpty(o.Seller.Name, sellerFallback(kind)), Kind: kind, City: o.Seller.City},
		PriceMinor: o.PriceMinor, ListPriceMinor: list, Currency: firstNonEmpty(strings.ToUpper(o.Currency), domain.Currency),
		Fulfilment: normFulfilment(o.Fulfilment), AvailableQty: qty, StockStatus: status, Delivery: delivery}
}

// withOffers puts the offers-service view on product cards: offer count and lowest price across sellers, and the
// buy box winner (offer id, price, seller, fulfilment, stock) so "Add to cart" on a card adds the winning offer.
// Cards keep the search data when offers is not wired, cannot answer, or has no offer for the product.
func withOffers(ctx context.Context, offers port.StorefrontOffersClient, cards []domain.ProductSummary) []domain.ProductSummary {
	if offers == nil || len(cards) == 0 {
		return cards
	}
	atins := make([]string, 0, len(cards))
	for _, c := range cards {
		atins = append(atins, c.ATIN)
	}
	boxes, err := offers.ProductSummaries(ctx, atins)
	if err != nil {
		return cards
	}
	for i, c := range cards {
		bb, ok := boxes[c.ATIN]
		if !ok || bb.OfferCount == 0 {
			continue
		}
		c.OfferCount = bb.OfferCount
		if bb.MinPriceMinor != nil {
			c.MinOfferPriceMinor = *bb.MinPriceMinor
		}
		if w := bb.BuyBox; w != nil {
			o := offerFromPublic(*w, c.Delivery)
			c.OfferID, c.PriceMinor, c.ListPriceMinor, c.Currency = o.OfferID, o.PriceMinor, o.ListPriceMinor, o.Currency
			c.SellerName, c.SellerKind, c.SellerCity, c.Fulfilment = o.Seller.Name, o.Seller.Kind, o.Seller.City, o.Fulfilment
			c.AvailableQty, c.StockStatus = o.AvailableQty, o.StockStatus
			c.InStock, c.LowStock = o.StockStatus != domain.OutOfStock, o.StockStatus == domain.LowStock
			c.MinOfferPriceMinor = min(c.MinOfferPriceMinor, c.PriceMinor)
			if c.MinOfferPriceMinor == 0 {
				c.MinOfferPriceMinor = c.PriceMinor
			}
		}
		cards[i] = c
	}
	return cards
}

// summaryFromHit maps a search-service hit to a product card.
func summaryFromHit(h port.SearchHit, est domain.Estimator, now time.Time) domain.ProductSummary {
	qty := max(0, h.AvailableQty)
	if !h.InStock {
		qty = 0
	}
	status := domain.StockStatusFor(qty)
	if h.InStock && qty == 0 {
		// In stock without a count (seller-managed stock): show as available.
		status = domain.InStock
	}
	if h.LowStock && qty > 0 {
		status = domain.LowStock
	}
	fulfilment := h.Fulfilment
	if fulfilment != domain.FulfilledByAfroTransact {
		fulfilment = domain.ShipsFromSeller
	}
	kind := h.SellerKind
	if kind != domain.SellerKindPlatform {
		kind = domain.SellerKindSeller
	}
	currency := h.Currency
	if currency == "" {
		currency = domain.Currency
	}
	return domain.ProductSummary{
		ATIN: h.ATIN, Title: h.Title, Brand: h.Brand, Community: "", Department: domain.DepartmentOf(h.CategoryPath),
		Unit: "", Rating: min(5, max(0, h.Rating)), ReviewCount: max(0, h.ReviewCount), ImageURL: strPtr(h.ImageURL),
		OfferID: h.ATIN, PriceMinor: h.PriceMinor, ListPriceMinor: nil, Currency: strings.ToUpper(currency),
		SellerName: firstNonEmpty(h.SellerName, sellerFallback(kind)), SellerKind: kind, SellerCity: "",
		Fulfilment: fulfilment, AvailableQty: qty, InStock: status != domain.OutOfStock, LowStock: status == domain.LowStock,
		StockStatus: status, Delivery: est.Promise(fulfilment, h.DeliveryDays, now), OfferCount: max(1, h.OfferCount),
		MinOfferPriceMinor: h.PriceMinor, Sponsor: nil,
	}
}

func sellerFallback(kind string) string {
	if kind == domain.SellerKindPlatform {
		return PlatformSellerName
	}
	return "Marketplace seller"
}

// optionsLabel renders variant options ("Size: 5 kg · Colour: Red") in a stable order.
func optionsLabel(opts map[string]string) string {
	if len(opts) == 0 {
		return ""
	}
	keys := make([]string, 0, len(opts))
	for k := range opts {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		if v := strings.TrimSpace(opts[k]); v != "" {
			parts = append(parts, v)
		}
	}
	return strings.Join(parts, " · ")
}
