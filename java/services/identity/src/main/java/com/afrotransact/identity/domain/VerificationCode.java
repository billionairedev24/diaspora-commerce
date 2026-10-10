package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A hashed one-time code. The plain code exists only in the message sent to the user.
 *
 * @param codeHash  hex SHA-256 of {@code id + ":" + code}
 * @param attempts  wrong guesses so far
 */
public record VerificationCode(UUID id, UUID userId, VerificationPurpose purpose, String destination,
                               String codeHash, Instant createdAt, Instant expiresAt, Instant consumedAt,
                               int attempts) {

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean consumed() {
        return consumedAt != null;
    }

    public VerificationCode failedAttempt() {
        return new VerificationCode(id, userId, purpose, destination, codeHash, createdAt, expiresAt, consumedAt,
                attempts + 1);
    }

    public VerificationCode consume(Instant now) {
        return new VerificationCode(id, userId, purpose, destination, codeHash, createdAt, expiresAt, now, attempts);
    }
}
