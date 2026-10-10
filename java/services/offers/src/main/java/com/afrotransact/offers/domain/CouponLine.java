package com.afrotransact.offers.domain;

/**
 * One cart line as the coupon rules see it. Prices come from the offer, never from the client.
 *
 * @param lineRef reference echoed back in the per-line allocation (the offer id)
 */
public record CouponLine(String lineRef, String atin, String sellerOrgId, String categoryId, Fulfilment fulfilment,
                         long unitPriceMinor, int quantity) {

    public CouponLine {
        if (quantity < 1) {
            throw new OfferRuleViolation("Quantity must be at least 1");
        }
        if (unitPriceMinor < 0) {
            throw new OfferRuleViolation("Unit price cannot be negative");
        }
    }

    public long totalMinor() {
        return unitPriceMinor * quantity;
    }
}
