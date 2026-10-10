package com.afrotransact.bookings.domain;

/**
 * Cancelling at least {@code hoursBefore} hours before the start refunds {@code shareBps} (basis points) of the
 * deposit.
 */
public record RefundTier(int hoursBefore, int shareBps) {

    public RefundTier {
        if (hoursBefore < 0) {
            throw new BookingsRuleViolation("bookings.invalid-policy", "Hours before must not be negative");
        }
        if (shareBps < 0 || shareBps > 10_000) {
            throw new BookingsRuleViolation("bookings.invalid-policy", "Refund share must be 0–100 %");
        }
    }
}
