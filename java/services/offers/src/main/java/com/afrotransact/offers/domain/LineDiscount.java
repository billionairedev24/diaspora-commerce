package com.afrotransact.offers.domain;

/** The part of a discount allocated to one line (used to charge seller-funded discounts to the right payout). */
public record LineDiscount(String lineRef, String sellerOrgId, long discountMinor) {
}
