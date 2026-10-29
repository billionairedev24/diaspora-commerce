package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.Attribution;

/**
 * {@code POST /v1/checkout}.
 *
 * @param idempotencyKey  required; the same key returns the same order / saga result
 * @param customerBearer  the shopper's access token, relayed to cart and identity (never stored)
 * @param addressId       saved address to deliver to
 * @param selections      delivery choice per cart package
 * @param lines           optional subset of cart lines to check out (default: every available line)
 * @param couponCode      optional coupon, validated and redeemed with offers-service
 * @param attribution     storefront attribution {src, cr, po} (S272), stored on the order; null when none
 * @param savePaymentMethod the shopper pays with a new method from Stripe's Payment Element and keeps it for future
 *                          purchases: the PaymentIntent is created with setup_future_usage=off_session (S282)
 * @param useCredit         pay with the shopper's AfroTransact credit first (S320): credit is spent up to the order
 *                          total in the same step that creates the payment, and the card pays only the rest; an order
 *                          that credit pays in full needs no card
 */
public record CheckoutCommand(String idempotencyKey, String customerBearer, String addressId,
                              List<CheckoutInput.Selection> selections, List<SelectedLine> lines,
                              String couponCode, Attribution attribution, boolean savePaymentMethod,
                              boolean useCredit) {

    /** A checkout that does not use credit. */
    public CheckoutCommand(String idempotencyKey, String customerBearer, String addressId,
                           List<CheckoutInput.Selection> selections, List<SelectedLine> lines, String couponCode,
                           Attribution attribution, boolean savePaymentMethod) {
        this(idempotencyKey, customerBearer, addressId, selections, lines, couponCode, attribution,
                savePaymentMethod, false);
    }

    /** A checkout that does not save the payment method. */
    public CheckoutCommand(String idempotencyKey, String customerBearer, String addressId,
                           List<CheckoutInput.Selection> selections, List<SelectedLine> lines, String couponCode,
                           Attribution attribution) {
        this(idempotencyKey, customerBearer, addressId, selections, lines, couponCode, attribution, false);
    }

    /** A checkout without attribution. */
    public CheckoutCommand(String idempotencyKey, String customerBearer, String addressId,
                           List<CheckoutInput.Selection> selections, List<SelectedLine> lines, String couponCode) {
        this(idempotencyKey, customerBearer, addressId, selections, lines, couponCode, null);
    }

    public CheckoutCommand {
        selections = selections == null ? List.of() : List.copyOf(selections);
        lines = lines == null ? List.of() : List.copyOf(lines);
        couponCode = couponCode == null || couponCode.isBlank() ? null : couponCode.trim();
    }

    /** One selected cart line. */
    public record SelectedLine(String variantAtin, int quantity) {
    }
}
