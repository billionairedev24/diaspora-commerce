package com.afrotransact.identity.application;

/**
 * What is asked after the first factor succeeded. A code is asked ({@code required}) only for enrolled factors;
 * {@code enrolmentRequired} means MFA is mandatory for this person and nothing is enrolled yet: no code is sent, they
 * must set up a factor before they get a session.
 *
 * @param smsHint   masked destination such as {@code •••• 0142} (only while SMS is switched on)
 * @param emailHint masked email such as {@code a••••@example.com}
 */
public record SecondFactorChallenge(boolean required, boolean totp, boolean sms, String smsHint, boolean recovery,
                                    boolean emailOtp, String emailHint, boolean enrolmentRequired) {

    public SecondFactorChallenge(boolean required, boolean totp, boolean sms, String smsHint, boolean recovery) {
        this(required, totp, sms, smsHint, recovery, false, null, false);
    }

    public static SecondFactorChallenge none() {
        return new SecondFactorChallenge(false, false, false, null, false);
    }

    public static SecondFactorChallenge enrol() {
        return new SecondFactorChallenge(false, false, false, null, false, false, null, true);
    }

    /** Something must happen before the session is given: a code, or a forced enrolment. */
    public boolean pending() {
        return required || enrolmentRequired;
    }

    /** The factor to offer first: authenticator, then email, then SMS, then a recovery code. */
    public String defaultFactor() {
        return totp ? "TOTP" : emailOtp ? "EMAIL_OTP" : sms ? "SMS" : "RECOVERY_CODE";
    }

    /** Email is the only way to receive a code, so it is sent without asking. */
    public boolean emailIsDefault() {
        return required && emailOtp && !totp && !sms;
    }
}
