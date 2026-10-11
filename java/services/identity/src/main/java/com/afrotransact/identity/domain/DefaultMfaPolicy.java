package com.afrotransact.identity.domain;

/**
 * Default rules: passkey sign-ins are already phishing-resistant MFA; a trusted browser skips the prompt for
 * 30 days; staff always need a second factor (SMS to the phone on file when nothing else is enrolled).
 */
public class DefaultMfaPolicy implements MfaPolicy {

    @Override
    public boolean secondFactorRequired(boolean staff, MfaEnrolment enrolment, boolean trustedBrowser,
                                        boolean phishingResistant) {
        if (phishingResistant || trustedBrowser) {
            return false;
        }
        return staff || enrolment.anyFactor();
    }

    @Override
    public boolean mayDisable(boolean staff) {
        return !staff;
    }

    @Override
    public boolean mayRemove(boolean staff, MfaEnrolment enrolment, MfaFactor factor) {
        if (!staff) {
            return true;
        }
        return switch (factor) {
            case TOTP -> enrolment.totp() && enrolment.sms();
            case SMS -> enrolment.sms() && enrolment.totp();
            case RECOVERY_CODE -> false;
        };
    }
}
