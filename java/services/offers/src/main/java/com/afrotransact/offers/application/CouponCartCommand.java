package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/**
 * A cart or order to apply a coupon to. Lines reference offers; prices, sellers, categories and fulfilment are
 * read from the offers, never trusted from the caller.
 *
 * @param customerId required for service callers; customers always act for themselves
 * @param orderId    required when redeeming
 */
public record CouponCartCommand(String code, String customerId, String orderId, String currency,
                                List<Line> lines, long shippingMinor, boolean firstOrder) {

    public CouponCartCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** An offer and quantity in the cart. */
    public record Line(UUID offerId, int quantity) {
    }
}
