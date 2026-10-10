package com.afrotransact.identity.domain;

/** Why a one-time code was issued. */
public enum VerificationPurpose {
    /** Confirm the email address after sign-up (email). */
    EMAIL_VERIFICATION,
    /** Second factor at sign-in (SMS). */
    SIGN_IN_SMS,
    /** Confirm a phone number before enabling SMS codes (SMS). */
    SMS_ENROLMENT
}
