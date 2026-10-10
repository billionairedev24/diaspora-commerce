package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.Optional;

/** Idempotency of {@code POST /v1/checkout}: one row per (customer, Idempotency-Key). */
public interface CheckoutRequestRepository {

    /**
     * Inserts the request unless the key (or, rarely, the order number) is taken. Commits on its own so a concurrent
     * retry sees it.
     */
    Claim claim(CheckoutRequest request);

    Optional<CheckoutRequest> find(String customerId, String idempotencyKey);

    Optional<CheckoutRequest> findByOrderId(String orderId);

    Optional<CheckoutRequest> findByNumber(String orderNumber);

    /** Records the PaymentIntent the shopper must confirm. */
    void markAwaitingPayment(String orderId, String paymentId, String clientSecret);

    void markOutcome(String orderId, String status, String failureCode, String failureMessage);

    /** Removes a claim whose workflow never started (so the same key can be retried). */
    void release(String customerId, String idempotencyKey);

    /** One checkout attempt. */
    record CheckoutRequest(String customerId, String idempotencyKey, String fingerprint, String orderId,
                           String orderNumber, String workflowId, String status, String paymentId,
                           String clientSecret, String failureCode, String failureMessage, Instant createdAt) {
    }

    /**
     * Result of a claim: {@code claimed} for a new key; otherwise {@code existing} holds the earlier attempt, or is
     * null when only the generated order number collided (generate another and retry).
     */
    record Claim(boolean claimed, CheckoutRequest existing) {
    }
}
