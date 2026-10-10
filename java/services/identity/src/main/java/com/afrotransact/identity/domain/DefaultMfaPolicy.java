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
}
