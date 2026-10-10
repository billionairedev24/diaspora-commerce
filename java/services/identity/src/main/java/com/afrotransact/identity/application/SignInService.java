package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.MfaFactor;

/** Interactive sign-in on the authorization server (form, social and passkey logins, then MFA). */
public interface SignInService {

    /** Looks an account up by email or by id (passkey and session principals use the id). */
    Optional<SignInAccount> findAccount(String emailOrId);

    /**
     * @param trustedBrowserToken value of the "trust this browser" cookie, if any
     * @param passkey             true when the first factor was a passkey (already phishing-resistant MFA)
     */
    SecondFactorChallenge challenge(UUID userId, Optional<String> trustedBrowserToken, boolean passkey);

    /** Texts a sign-in code (consent to OTP is implied by enrolment; quiet hours do not apply). */
    Instant sendSmsCode(UUID userId);

    boolean verifySecondFactor(UUID userId, MfaFactor factor, String code);

    /** Remembers this browser for 30 days; returns the cookie value. */
    String trustBrowser(UUID userId, String userAgent);
}
