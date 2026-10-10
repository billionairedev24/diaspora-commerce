package com.afrotransact.identity.application;

import java.time.Instant;

/** Public (unauthenticated) account endpoints: sign-up, email verification, resend. */
public interface AccountService {

    SignUpResult signUp(SignUpCommand command);

    /** Confirms the email with the 6-digit code. */
    void verifyEmail(String email, String code);

    /** Sends a new code (at most 1 per 30 seconds and 5 per hour); returns its expiry. */
    Instant resendVerification(String email);
}
