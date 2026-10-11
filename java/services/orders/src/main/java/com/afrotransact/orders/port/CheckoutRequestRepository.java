package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.Optional;

/** Idempotency of {@code POST /v1/checkout}: one row per (customer, Idempotency-Key). */
public interface CheckoutRequestRepository {

    /**
     * Inserts the request unless the key (or, rarely, the order number) is taken. Commits on its own so a concurrent
     * retry sees it. The request does not hold the customer's open-checkout slot (late pre-order adds).
     */
    Claim claim(CheckoutRequest request);

    /**
     * A storefront checkout: like {@link #claim} but the request also takes the customer's single open-checkout slot
     * (unique index). When another checkout of the customer holds it, nothing is inserted and {@link Claim#open()}
     * names that checkout.
     */
    Claim claimCheckout(CheckoutRequest request);

    /** The customer's checkout that holds the open-checkout slot (started or awaiting payment), if any. */
    Optional<CheckoutRequest> findOpen(String customerId);

    /**
     * Serialises late adds of one customer for the rest of the current transaction (a transaction-scoped advisory
     * lock), so the duplicate check and the claim cannot interleave.
     */
    void lockLateAdds(String customerId);

    /** Records the (window, items) guard of a late add. */
    void markLateAddGuard(String orderId, String guard);

    /** The newest live (started, awaiting payment or placed) late add with this guard created at or after since. */
    Optional<CheckoutRequest> recentLateAdd(String customerId, String guard, Instant since);

    Optional<CheckoutRequest> find(String customerId, String idempotencyKey);

    Optional<CheckoutRequest> findByOrderId(String orderId);

    Optional<CheckoutRequest> findByNumber(String orderNumber);

    /** Records the PaymentIntent the shopper must confirm. */
    void markAwaitingPayment(String orderId, String paymentId, String clientSecret);

    /** Placed or failed: also frees the customer's open-checkout slot. */
    void markOutcome(String orderId, String status, String failureCode, String failureMessage);

    /** Removes a claim whose workflow never started (so the same key can be retried). */
    void release(String customerId, String idempotencyKey);

    /** One checkout attempt. */
    record CheckoutRequest(String customerId, String idempotencyKey, String fingerprint, String orderId,
                           String orderNumber, String workflowId, String status, String paymentId,
                           String clientSecret, String failureCode, String failureMessage, Instant createdAt) {
    }

    /**
     * Result of a claim: {@code claimed} for a new key; otherwise {@code existing} holds the earlier attempt of the
     * same key, {@code open} the customer's other open checkout ({@link #claimCheckout}), or both are null when only
     * the generated order number collided (generate another and retry).
     */
    record Claim(boolean claimed, CheckoutRequest existing, CheckoutRequest open) {

        public Claim(boolean claimed, CheckoutRequest existing) {
            this(claimed, existing, null);
        }
    }
}
