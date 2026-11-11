package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a first-party sign-in step: a second factor is needed ({@link Status#MFA_REQUIRED}, the person enrolled
 * one), MFA is mandatory for them and nothing is set up yet ({@link Status#MFA_ENROLMENT_REQUIRED}: no code was sent;
 * they must enrol an authenticator or email codes with the same challenge), or the sign-in is complete
 * ({@link Status#AUTHENTICATED}, redeem the one-time login ticket on {@code /oauth2/authorize}).
 *
 * @param mfaSetupSuggested authenticated people with no factor who have not dismissed the reminder lately: the app may
 *                          show a gentle, dismissable "Secure your account" prompt (never blocking)
 */
public record SignInStep(Status status, UUID challengeId, Instant challengeExpiresAt, SecondFactorChallenge factors,
                         UUID userId, String loginTicket, Instant loginTicketExpiresAt, String trustedDeviceToken,
                         Instant trustedDeviceExpiresAt, boolean mfaSetupSuggested) {

    public enum Status {
        MFA_REQUIRED,
        MFA_ENROLMENT_REQUIRED,
        AUTHENTICATED
    }

    static SignInStep mfaRequired(UUID challengeId, Instant expiresAt, SecondFactorChallenge factors) {
        return new SignInStep(Status.MFA_REQUIRED, challengeId, expiresAt, factors, null, null, null, null, null,
                false);
    }

    static SignInStep enrolmentRequired(UUID challengeId, Instant expiresAt) {
        return new SignInStep(Status.MFA_ENROLMENT_REQUIRED, challengeId, expiresAt, SecondFactorChallenge.enrol(),
                null, null, null, null, null, false);
    }

    static SignInStep authenticated(UUID userId, String ticket, Instant ticketExpiresAt, String trustedDeviceToken,
                                    Instant trustedDeviceExpiresAt, boolean mfaSetupSuggested) {
        return new SignInStep(Status.AUTHENTICATED, null, null, null, userId, ticket, ticketExpiresAt,
                trustedDeviceToken, trustedDeviceExpiresAt, mfaSetupSuggested);
    }
}
