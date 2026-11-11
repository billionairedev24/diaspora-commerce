package com.afrotransact.identity.domain;

/**
 * Second factors a user can use at sign-in. {@link #SMS} is only offered while {@code at.identity.mfa.sms-enabled}
 * is on (off by default); {@link #EMAIL_OTP} sends a code to the account's verified email.
 */
public enum MfaFactor {
    TOTP,
    SMS,
    EMAIL_OTP,
    RECOVERY_CODE
}
