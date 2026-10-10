package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A hosted payment link of an order's payment (S272 late pre-order adds): the shopper pays on Stripe's page and the
 * resulting PaymentIntent is attached to the payment when its first webhook arrives.
 *
 * @param stripeSessionId Checkout Session id (cs_…)
 * @param url             the page the shopper opens (sent by SMS)
 * @param expiresAt       when the link stops accepting payments
 */
public record PaymentLink(UUID paymentId, String stripeSessionId, String url, Instant expiresAt, Instant createdAt) {

    public PaymentLink {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(stripeSessionId, "stripeSessionId");
        Objects.requireNonNull(url, "url");
    }
}
