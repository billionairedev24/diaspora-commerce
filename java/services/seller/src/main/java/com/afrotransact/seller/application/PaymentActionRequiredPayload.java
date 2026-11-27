package com.afrotransact.seller.application;

import java.time.Instant;

/**
 * seller.billing.payment_action_required: a plan charge needs the seller to confirm it (3-D Secure off-session). The
 * owners confirm it in Plan & billing before {@code confirmBy}; after that it counts as a failed attempt.
 */
public record PaymentActionRequiredPayload(String orgId, String invoiceId, String invoiceNumber, long amountCents,
                                           String currency, Instant confirmBy, Instant occurredAt) {
}
