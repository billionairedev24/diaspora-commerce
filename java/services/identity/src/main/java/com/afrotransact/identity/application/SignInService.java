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
     * What is asked after the first factor. Codes are asked ONLY of people who enrolled a factor; when MFA is
     * mandatory for someone with none, the result says {@code enrolmentRequired} (no code is sent).
     *
     * @param trustedBrowserToken value of the "trust this browser" cookie, if any
     * @param passkey             true when the first factor was a passkey (already phishing-resistant MFA)
     */
    SecondFactorChallenge challenge(UUID userId, Optional<String> trustedBrowserToken, boolean passkey);

    /** Emails a sign-in code to the verified account email; only for people who enrolled email codes. */
    Instant sendEmailCode(UUID userId);

    /**
     * Emails the code straight away when email is the person's only code-based factor (nothing else could be used
     * to answer); returns whether one was sent. Never throws on resend throttling.
     */
    boolean sendEmailCodeIfDefault(UUID userId);

    /** Texts a sign-in code (consent to OTP is implied by enrolment; quiet hours do not apply). */
    Instant sendSmsCode(UUID userId);

    boolean verifySecondFactor(UUID userId, MfaFactor factor, String code);

    /** Whether the gentle "secure your account" reminder is due for this person (nothing enrolled, not mandatory). */
    boolean setupPromptDue(UUID userId);

    /** Remembers this browser for 30 days; returns the cookie value. */
    String trustBrowser(UUID userId, String userAgent);
}
