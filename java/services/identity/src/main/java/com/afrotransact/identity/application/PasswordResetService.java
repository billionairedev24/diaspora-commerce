package com.afrotransact.identity.application;

import java.time.Duration;

/**
 * Forgotten password (S300), for the hosted pages ({@code /login/forgot}) and the public API
 * ({@code /v1/password-reset}).
 *
 * <ul>
 *   <li>{@link #request}: always the same answer whether or not the email has an account (no enumeration). An account
 *   with a password gets a 6-digit code (10 minutes, single use, hashed at rest) by email and, when its phone is
 *   verified, by text message; an account that only signs in with Google / Apple / Facebook gets an email saying to
 *   use that provider. Every request counts towards per-email and per-IP limits.</li>
 *   <li>{@link #complete}: the code plus a new password (sign-up policy). Wrong, expired, used or unknown codes all
 *   fail the same way and count towards a lockout. On success every refresh token, browser session and trusted
 *   browser of the account is revoked (signed out everywhere, so staff and enrolled users verify their second factor
 *   again) and a "your password was changed" email is sent.</li>
 * </ul>
 * Both steps are audited ({@code sign_in_audit}, method PASSWORD_RESET).
 */
public interface PasswordResetService {

    /** Error code of a wrong, expired, used or unknown code (one answer for all, field {@code code}). */
    String INVALID_CODE = "identity.invalid-reset-code";
    /** Error code of a new password the policy rejects (fields {@code newPassword}, {@code confirmPassword}). */
    String INVALID_PASSWORD = "identity.invalid-password-reset";
    /** Error code (HTTP 429 + Retry-After) while the email or the IP address is locked out. */
    String LOCKED = "identity.reset-locked";

    /** Throws {@code RateLimitExceeded} ({@link #LOCKED}) when over the limits; never reveals whether the email exists. */
    void request(PasswordResetRequest request);

    /**
     * Sets the new password; throws {@code IdentityRuleViolation} ({@link #INVALID_CODE} or {@link #INVALID_PASSWORD})
     * or {@code RateLimitExceeded} ({@link #LOCKED}).
     */
    PasswordResetCompleted complete(PasswordResetCompletion completion);

    /** How long a reset code is valid. */
    Duration codeTimeToLive();
}
