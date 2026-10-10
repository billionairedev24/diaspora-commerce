package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a first-party sign-in step: either a second factor is needed ({@link Status#MFA_REQUIRED}, continue
 * with the challenge) or the sign-in is complete ({@link Status#AUTHENTICATED}, redeem the one-time login ticket on
 * {@code /oauth2/authorize}).
 */
public record SignInStep(Status status, UUID challengeId, Instant challengeExpiresAt, SecondFactorChallenge factors,
                         UUID userId, String loginTicket, Instant loginTicketExpiresAt, String trustedDeviceToken,
                         Instant trustedDeviceExpiresAt) {

    public enum Status {
        MFA_REQUIRED,
        AUTHENTICATED
    }

    static SignInStep mfaRequired(UUID challengeId, Instant expiresAt, SecondFactorChallenge factors) {
        return new SignInStep(Status.MFA_REQUIRED, challengeId, expiresAt, factors, null, null, null, null, null);
    }

    static SignInStep authenticated(UUID userId, String ticket, Instant ticketExpiresAt, String trustedDeviceToken,
                                    Instant trustedDeviceExpiresAt) {
        return new SignInStep(Status.AUTHENTICATED, null, null, null, userId, ticket, ticketExpiresAt,
                trustedDeviceToken, trustedDeviceExpiresAt);
    }
}
