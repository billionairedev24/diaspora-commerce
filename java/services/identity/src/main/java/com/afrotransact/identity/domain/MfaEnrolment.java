package com.afrotransact.identity.domain;

/**
 * What a user has enrolled.
 *
 * @param totp            authenticator app confirmed
 * @param sms             SMS codes enabled for {@code smsPhone}
 * @param smsPhone        E.164 phone that receives sign-in codes
 * @param recoveryCodesLeft unused recovery codes
 * @param passkeys        registered passkeys
 */
public record MfaEnrolment(boolean totp, boolean sms, String smsPhone, int recoveryCodesLeft, int passkeys) {

    public boolean anyFactor() {
        return totp || sms;
    }
}
