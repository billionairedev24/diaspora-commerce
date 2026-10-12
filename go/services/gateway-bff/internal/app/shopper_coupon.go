package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// priceCoupon validates the shopper's coupon against the cart (offers-service) and fills discount fields on the summary.
func (s *checkoutPaymentServiceImpl) priceCoupon(ctx context.Context, c *domain.Caller, sum *domain.CheckoutSummary,
	packageDelivery map[string]string, code string) {
	sum.DiscountMinor, sum.ShippingDiscountMinor = 0, 0
	sum.Coupon = nil
	code = strings.TrimSpace(code)
	if code == "" || s.d.Coupons == nil || !c.Authenticated() {
		return
	}
	lines := []port.CouponValidateLine{}
	for _, p := range sum.Packages {
		for _, l := range p.Lines {
			if strings.TrimSpace(l.OfferID) == "" {
				continue
			}
			lines = append(lines, port.CouponValidateLine{OfferID: l.OfferID, Quantity: l.Quantity})
		}
	}
	if len(lines) == 0 {
		sum.Coupon = &domain.CheckoutCoupon{Code: code, Applicable: false, Message: "This coupon can't be used on this order."}
		return
	}
	shipping := int64(0)
	for _, p := range sum.Packages {
		shipping += packageShippingFee(p, packageDelivery)
	}
	req := port.CouponValidateRequest{
		Code: code, CustomerID: c.Subject, Currency: strings.ToUpper(firstNonEmpty(sum.Currency, domain.Currency)),
		Lines: lines, ShippingMinor: shipping, FirstOrder: s.shopperFirstOrder(ctx, c),
	}
	q, err := s.d.Coupons.Validate(ctx, c.BearerToken, req)
	if err != nil {
		s.d.log().WarnContext(ctx, "coupon validate unavailable", "error", err)
		sum.Coupon = &domain.CheckoutCoupon{Code: code, Applicable: false, Message: "We couldn't check that code right now. Try again or continue without it."}
		return
	}
	coupon := &domain.CheckoutCoupon{
		Code: q.Code, Applicable: q.Applicable, Message: q.Message,
		DiscountMinor: q.DiscountMinor, ShippingDiscountMinor: q.ShippingDiscountMinor,
	}
	if q.Rejection != "" {
		coupon.Rejection = strPtr(q.Rejection)
	}
	if q.Applicable {
		coupon.Funding = strings.ToLower(q.Funding)
		coupon.FundedByOrg = q.FundedByOrg
		sum.DiscountMinor = q.DiscountMinor
		sum.ShippingDiscountMinor = q.ShippingDiscountMinor
		for _, l := range q.Lines {
			if l.DiscountMinor <= 0 {
				continue
			}
			coupon.OfferDiscounts = append(coupon.OfferDiscounts, domain.CheckoutCouponOfferDiscount{
				OfferID: l.LineRef, SellerOrgID: l.SellerOrgID, DiscountMinor: l.DiscountMinor,
			})
		}
		coupon.SellerShares = couponSellerShares(sum, q)
	}
	sum.Coupon = coupon
}

func couponSellerShares(sum *domain.CheckoutSummary, q port.CouponQuote) []domain.CheckoutCouponSellerShare {
	sellerNames := map[string]string{}
	for _, p := range sum.Packages {
		sellerNames[p.Seller.ID] = p.Seller.Name
	}
	bySeller := map[string]int64{}
	for _, l := range q.Lines {
		if l.SellerOrgID == "" || l.DiscountMinor <= 0 {
			continue
		}
		bySeller[l.SellerOrgID] += l.DiscountMinor
	}
	funding := strings.ToLower(q.Funding)
	out := make([]domain.CheckoutCouponSellerShare, 0, len(bySeller))
	for id, amt := range bySeller {
		out = append(out, domain.CheckoutCouponSellerShare{
			SellerOrgID: id, SellerName: sellerNames[id], DiscountMinor: amt, Funding: funding,
		})
	}
	return out
}

func (s *checkoutPaymentServiceImpl) shopperFirstOrder(ctx context.Context, c *domain.Caller) bool {
	items, _, err := s.d.Orders.List(ctx, c.BearerToken, "", 8)
	if err != nil {
		return false
	}
	for _, o := range items {
		if strings.TrimSpace(o.PlacedAt) != "" {
			return false
		}
	}
	return true
}

func offerDiscounts(c *domain.CheckoutCoupon) map[string]int64 {
	out := map[string]int64{}
	if c == nil || !c.Applicable {
		return out
	}
	for _, l := range c.OfferDiscounts {
		out[l.OfferID] += l.DiscountMinor
	}
	return out
}

func recalcCheckoutTotal(sum *domain.CheckoutSummary) {
	ship := sum.ShippingMinor - sum.ShippingDiscountMinor
	if ship < 0 {
		ship = 0
	}
	disc := sum.DiscountMinor
	if disc > sum.SubtotalMinor {
		disc = sum.SubtotalMinor
	}
	sum.TotalMinor = sum.SubtotalMinor - disc + ship + sum.TaxMinor
}
