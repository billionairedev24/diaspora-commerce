package com.afrotransact.identity.domain;

/**
 * What a user has enrolled. Only enrolled factors are ever asked for a code at sign-in: a person with none signs in
 * with their password alone (unless a portal switch or the staff rule makes enrolment mandatory first).
 *
 * @param totp            authenticator app confirmed
 * @param sms             SMS codes enabled for {@code smsPhone} (always false while the SMS feature flag is off)
 * @param smsPhone        E.164 phone that receives sign-in codes
 * @param recoveryCodesLeft unused recovery codes
 * @param passkeys        registered passkeys
 * @param emailOtp        email codes turned on (sent to the verified account email)
 */
public record MfaEnrolment(boolean totp, boolean sms, String smsPhone, int recoveryCodesLeft, int passkeys,
                           boolean emailOtp) {

    public MfaEnrolment(boolean totp, boolean sms, String smsPhone, int recoveryCodesLeft, int passkeys) {
        this(totp, sms, smsPhone, recoveryCodesLeft, passkeys, false);
    }

    /** A code-based second factor is enrolled (passkeys are a first factor of their own and are not counted). */
    public boolean anyFactor() {
        return totp || sms || emailOtp;
    }

    /** How many code-based factors are on. */
    public int factorCount() {
        return (totp ? 1 : 0) + (sms ? 1 : 0) + (emailOtp ? 1 : 0);
    }
}
