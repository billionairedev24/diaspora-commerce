package com.afrotransact.orders.application;

import io.temporal.activity.ActivityInterface;

/**
 * The "never charge more than shown" step of the checkout saga: after the draft is final (tax quoted, coupon applied)
 * and before any payment exists. A separate interface so the saga's main activities keep their shape.
 */
@ActivityInterface
public interface CheckoutTotalActivities {

    /**
     * Compares the draft's total with the one the shopper was shown ({@link CheckoutInput#taxGuard()}, set by the
     * gateway from its signed tax quote lock). A higher total fails the checkout with the non-retryable
     * {@link CheckoutCodes#TOTAL_CHANGED}, the new numbers attached as details, so nothing is charged and the shopper
     * confirms the new total first. A lower or equal total passes. Either way the estimate-versus-final comparison is
     * recorded ({@code checkout_tax_estimate_vs_final_*}).
     */
    void checkShownTotal(CheckoutInput input, OrderDraft draft);
}
