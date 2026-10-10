package com.afrotransact.taxreturns.domain.returns;

/**
 * A seller's own return policy (seller-fulfilled shipments).
 *
 * @param orgId             seller organisation
 * @param windowDays        return window after delivery (overrides the category window)
 * @param restockingFeeBps  restocking fee for no-fault returns, basis points of the item amount (0 = none)
 * @param acceptsReturns    false: only seller-fault returns (damaged, defective, wrong item …) are accepted
 * @param refundAtFirstScan refund as soon as the carrier scans the parcel instead of after inspection
 * @param returnAddress     where seller-fulfilled returns are shipped
 * @param version           optimistic-lock version
 */
public record SellerReturnPolicy(String orgId, int windowDays, int restockingFeeBps, boolean acceptsReturns,
                                 boolean refundAtFirstScan, ReturnAddress returnAddress, long version) {

    public SellerReturnPolicy {
        if (windowDays < 0 || windowDays > 365) {
            throw new IllegalArgumentException("windowDays must be between 0 and 365");
        }
        if (restockingFeeBps < 0 || restockingFeeBps > 10_000) {
            throw new IllegalArgumentException("restockingFeeBps must be between 0 and 10000");
        }
    }
}
