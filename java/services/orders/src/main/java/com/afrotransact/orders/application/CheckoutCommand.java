package com.afrotransact.orders.application;

import java.util.List;

/**
 * {@code POST /v1/checkout}.
 *
 * @param idempotencyKey  required; the same key returns the same order / saga result
 * @param customerBearer  the shopper's access token, relayed to cart and identity (never stored)
 * @param addressId       saved address to deliver to
 * @param selections      delivery choice per cart package
 * @param lines           optional subset of cart lines to check out (default: every available line)
 * @param couponCode      optional coupon, validated and redeemed with offers-service
 */
public record CheckoutCommand(String idempotencyKey, String customerBearer, String addressId,
                              List<CheckoutInput.Selection> selections, List<SelectedLine> lines,
                              String couponCode) {

    public CheckoutCommand {
        selections = selections == null ? List.of() : List.copyOf(selections);
        lines = lines == null ? List.of() : List.copyOf(lines);
        couponCode = couponCode == null || couponCode.isBlank() ? null : couponCode.trim();
    }

    /** One selected cart line. */
    public record SelectedLine(String variantAtin, int quantity) {
    }
}
