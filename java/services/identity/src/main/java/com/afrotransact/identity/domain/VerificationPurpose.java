package com.afrotransact.identity.domain;

/** Why a one-time code was issued. */
public enum VerificationPurpose {
    /** Second factor at sign-in for people who enrolled email codes (email). */
    SIGN_IN_EMAIL,
    /** Confirm the first email code when turning on email codes as a second factor (email). */
    EMAIL_OTP_ENROLMENT,
    /** Confirm the email address after sign-up (email). */
    EMAIL_VERIFICATION,
    /** Second factor at sign-in (SMS). */
    SIGN_IN_SMS,
    /** Confirm a phone number before enabling SMS codes (SMS). */
    SMS_ENROLMENT,
    /** Confirm the account's phone number so notifications may text it (SMS, independent of MFA). */
    PHONE_VERIFICATION,
    /** Set a new password after "Forgot password?" (email, and SMS to a verified phone: one code for both). */
    PASSWORD_RESET
}
