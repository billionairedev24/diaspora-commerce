package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turning on a second factor for a given account. Used by the signed-in security settings, the optional "Secure your
 * account" step after sign-up, and the forced enrolment a person must finish after their password when MFA is
 * mandatory for them and nothing is set up yet (no session exists at that point, so callers pass the user id they
 * already authenticated with the first factor).
 */
public interface SecondFactorEnrolment {

    /** Starts (or restarts) authenticator-app enrolment. */
    TotpSetup startTotp(UUID userId);

    /** The authenticator setup in progress (secret not confirmed yet), so a mistyped code can retry the same QR. */
    Optional<TotpSetup> pendingTotp(UUID userId);

    /** Confirms the authenticator with a code; returns recovery codes when the person had none, else an empty list. */
    List<String> confirmTotp(UUID userId, String code);

    /**
     * Sends a first code to the account's verified email to prove the mailbox works before email codes are turned on;
     * rate limited (1 per 30 s, 5 per hour). Returns the code's expiry.
     */
    Instant startEmailOtp(UUID userId);

    /** Confirms the first emailed code and turns email codes on; returns recovery codes when the person had none. */
    List<String> confirmEmailOtp(UUID userId, String code);

    /** "Skip for now": the gentle reminder stays quiet until the reminder interval has passed. */
    void dismissSetupPrompt(UUID userId);
}
