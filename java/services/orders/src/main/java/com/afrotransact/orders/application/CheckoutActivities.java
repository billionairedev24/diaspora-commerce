package com.afrotransact.orders.application;

import java.util.List;

import io.temporal.activity.ActivityInterface;

/**
 * Side-effecting steps of the checkout saga. Business refusals are thrown as non-retryable
 * {@code ApplicationFailure}s whose type is the {@link CheckoutCodes} code; upstream outages are retried.
 */
@ActivityInterface
public interface CheckoutActivities {

    /** Validates the cart snapshot and address (eligible market), snapshots prices and computes tax. */
    CheckoutPlan prepare(CheckoutInput input);

    /**
     * Prices the shopper's coupon with offers-service before anything is reserved (estimated shipping); a coupon
     * that does not apply fails the checkout with {@link CheckoutCodes#COUPON_NOT_APPLICABLE}.
     */
    void validateCoupon(CheckoutInput input, CheckoutPlan plan);

    /**
     * Redeems the coupon (Idempotency-Key = order id) on the reserved draft and applies the per-line and shipping
     * discounts. Returns the discounted draft carrying the redemption.
     */
    OrderDraft redeemCoupon(CheckoutInput input, OrderDraft draft);

    /**
     * Compensation: releases the order's coupon redemption. Without a known redemption id the redeem is replayed with
     * the same key and body to find it (nothing to release when it was never redeemed).
     */
    void releaseCoupon(CheckoutInput input, OrderDraft draft, String redemptionId);

    /** Reserves AfroTransact-fulfilled lines (all or nothing) and splits packages into shipments per node. */
    OrderDraft reserve(CheckoutInput input, CheckoutPlan plan);

    /** Creates the PaymentIntent (manual capture) with one payments line per order line. */
    PaymentRef createPayment(OrderDraft draft);

    /** Persists the order and its shipments as PENDING_PAYMENT. Idempotent. */
    void persistPending(OrderDraft draft, PaymentRef payment);

    /** Payment authorised: confirm the reservation, mark the order PLACED and emit orders.order.placed. */
    void place(String orderId, boolean reserved);

    /** Removes the ordered lines from the customer's cart. */
    void clearCart(String customerId, List<String> variantAtins);

    /** Compensation: give the reserved stock back. */
    void releaseReservation(String orderId, String reason);

    /** Compensation: cancel the PaymentIntent (looked up by order when its id is unknown). */
    void cancelPayment(String orderId, String paymentId, String reason);

    /** Compensation: void the order (CANCELLED with reason) and record the checkout failure. */
    void abandon(String orderId, String code, String message);
}
