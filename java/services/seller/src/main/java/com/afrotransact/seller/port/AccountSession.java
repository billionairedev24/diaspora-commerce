package com.afrotransact.seller.port;

import java.time.Instant;

/**
 * Client secret for Stripe's embedded Connect components.
 *
 * @param localTest true when the session comes from the local Stripe stand-in (local profile without a Stripe key):
 *                  there is no Stripe to load, and Seller Center shows its labelled local verification instead
 */
public record AccountSession(String accountId, String clientSecret, Instant expiresAt, boolean localTest) {
}
