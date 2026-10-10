package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A pending first-party sign-in step, identified by an unguessable id the BFF hands back.
 *
 * @param userId           account (MFA) or null (passkey: known only after the assertion)
 * @param clientId         the first-party client that started it; only that client may continue it
 * @param passkeyChallenge base64url WebAuthn challenge (passkey only)
 * @param rpId             WebAuthn relying party id (passkey only)
 */
public record SignInChallenge(UUID id, ChallengeKind kind, UUID userId, String clientId, String passkeyChallenge,
                              String rpId, int attempts, Instant createdAt, Instant expiresAt, Instant completedAt) {

    /** Wrong codes allowed per MFA challenge before the password has to be entered again. */
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration MFA_TTL = Duration.ofMinutes(10);
    public static final Duration PASSKEY_TTL = Duration.ofMinutes(5);

    public static SignInChallenge mfa(UUID userId, String clientId, Instant now) {
        return new SignInChallenge(UUID.randomUUID(), ChallengeKind.MFA, userId, clientId, null, null, 0, now,
                now.plus(MFA_TTL), null);
    }

    public static SignInChallenge passkey(String clientId, String challenge, String rpId, Instant now) {
        return new SignInChallenge(UUID.randomUUID(), ChallengeKind.PASSKEY, null, clientId, challenge, rpId, 0, now,
                now.plus(PASSKEY_TTL), null);
    }

    /** Still usable by {@code client}: not completed, not expired, attempts left. */
    public boolean usableBy(String client, Instant now) {
        return completedAt == null && expiresAt.isAfter(now) && attempts < MAX_ATTEMPTS && clientId.equals(client);
    }

    public int attemptsLeft() {
        return Math.max(0, MAX_ATTEMPTS - attempts);
    }

    public SignInChallenge failedAttempt() {
        return new SignInChallenge(id, kind, userId, clientId, passkeyChallenge, rpId, attempts + 1, createdAt,
                expiresAt, completedAt);
    }

    public SignInChallenge completed(Instant now) {
        return new SignInChallenge(id, kind, userId, clientId, passkeyChallenge, rpId, attempts, createdAt, expiresAt,
                now);
    }
}
