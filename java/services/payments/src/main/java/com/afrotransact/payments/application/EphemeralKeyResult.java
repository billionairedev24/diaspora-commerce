package com.afrotransact.payments.application;

import java.time.Instant;

/**
 * What the mobile PaymentSheet needs to show the shopper's saved methods (S241).
 *
 * @param customerId         the shopper's Stripe Customer ({@code cus_…})
 * @param ephemeralKeySecret secret of the ephemeral key (hand to the app only, never log)
 * @param expiresAt          when the key stops working
 */
public record EphemeralKeyResult(String customerId, String ephemeralKeySecret, Instant expiresAt) {

    @Override
    public String toString() {
        return "EphemeralKeyResult[customerId=" + customerId + ", ephemeralKeySecret=***, expiresAt=" + expiresAt
                + "]";
    }
}
