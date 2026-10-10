package com.afrotransact.identity.application;

/**
 * Second-factor requirement after the first factor succeeded.
 *
 * @param smsHint masked destination such as {@code •••• 0142}
 */
public record SecondFactorChallenge(boolean required, boolean totp, boolean sms, String smsHint, boolean recovery) {

    public static SecondFactorChallenge none() {
        return new SecondFactorChallenge(false, false, false, null, false);
    }
}
