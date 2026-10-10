package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A pending passkey registration (S016): the WebAuthn challenge issued to a signed-in account over the API, good for
 * one registration within {@link #TTL}.
 *
 * @param challenge  base64url WebAuthn challenge
 * @param userHandle base64url WebAuthn user handle of the account
 * @param rpId       relying party id the options were issued for
 * @param label      name the user gave the passkey (may be set again when completing)
 */
public record PasskeyRegistration(UUID id, UUID userId, String challenge, String userHandle, String rpId,
                                  String label, Instant createdAt, Instant expiresAt, Instant completedAt) {

    public static final Duration TTL = Duration.ofMinutes(5);

    public static PasskeyRegistration start(UUID userId, String challenge, String userHandle, String rpId,
                                            String label, Instant now) {
        return new PasskeyRegistration(UUID.randomUUID(), userId, challenge, userHandle, rpId, label, now,
                now.plus(TTL), null);
    }

    /** Still usable by {@code accountId}: its own, not completed, not expired. */
    public boolean usableBy(UUID accountId, Instant now) {
        return userId.equals(accountId) && completedAt == null && expiresAt.isAfter(now);
    }
}
