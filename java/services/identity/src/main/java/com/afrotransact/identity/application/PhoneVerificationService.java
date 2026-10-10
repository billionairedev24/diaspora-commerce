package com.afrotransact.identity.application;

/**
 * S234: the signed-in user confirms their account phone number with a 6-digit SMS code, independently of MFA, so
 * notifications-service may text it. Codes are hashed, expire after 10 minutes, allow 5 wrong attempts and are
 * rate limited (1 per 30 seconds, 5 per hour). Changing the phone number resets the verification.
 */
public interface PhoneVerificationService {

    /** Texts a code to the account's phone ({@code identity.verification.requested}, channel sms). */
    SmsCodeSent start();

    /** Checks the code and marks the current phone number verified. */
    PhoneVerificationView confirm(String code);
}
