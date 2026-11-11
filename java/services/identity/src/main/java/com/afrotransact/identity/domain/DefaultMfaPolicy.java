package com.afrotransact.identity.domain;

import java.time.Instant;

/**
 * Default rules: a code is asked only of people who enrolled a factor; passkey sign-ins are already phishing-resistant
 * MFA; a trusted browser skips the prompt for 30 days; staff and portal switches force enrolment, never an unsolicited
 * code.
 */
public class DefaultMfaPolicy implements MfaPolicy {

    @Override
    public boolean secondFactorRequired(MfaEnrolment enrolment, boolean trustedBrowser, boolean phishingResistant) {
        return enrolment.anyFactor() && !phishingResistant && !trustedBrowser;
    }

    @Override
    public boolean enrolmentRequired(boolean mandatory, MfaEnrolment enrolment, boolean phishingResistant) {
        return mandatory && !enrolment.anyFactor() && !phishingResistant;
    }

    @Override
    public boolean mayDisable(boolean mandatory) {
        return !mandatory;
    }

    @Override
    public boolean mayRemove(boolean mandatory, MfaEnrolment enrolment, MfaFactor factor) {
        if (!mandatory) {
            return true;
        }
        return switch (factor) {
            case TOTP -> enrolment.totp() && enrolment.factorCount() > 1;
            case SMS -> enrolment.sms() && enrolment.factorCount() > 1;
            case EMAIL_OTP -> enrolment.emailOtp() && enrolment.factorCount() > 1;
            case RECOVERY_CODE -> false;
        };
    }

    @Override
    public boolean reminderDue(MfaEnrolment enrolment, boolean mandatory, Instant lastDismissed, Instant now) {
        if (enrolment.anyFactor() || mandatory) {
            return false;
        }
        return lastDismissed == null || !lastDismissed.plus(SETUP_REMINDER_INTERVAL).isAfter(now);
    }
}
