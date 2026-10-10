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
