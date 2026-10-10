package com.afrotransact.seller.port;

import java.time.Instant;

/** Client secret for Stripe's embedded Connect components. */
public record AccountSession(String accountId, String clientSecret, Instant expiresAt) {
}
