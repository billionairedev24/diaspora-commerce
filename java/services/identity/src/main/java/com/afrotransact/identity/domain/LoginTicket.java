package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One-time proof that a first-party sign-in finished (all factors done). The BFF exchanges it once, within
 * {@link #TTL}, on {@code /oauth2/authorize?login_ticket=…}; only its SHA-256 hash is stored.
 */
public record LoginTicket(String tokenHash, UUID userId, String clientId, SignInMethod method, Instant createdAt,
                          Instant expiresAt, Instant usedAt) {

    public static final Duration TTL = Duration.ofSeconds(60);

    /** Redeemable by {@code client} at {@code now}; the caller has already marked it used (single use). */
    public boolean redeemableBy(String client, Instant now) {
        return clientId.equals(client) && expiresAt.isAfter(now);
    }
}
