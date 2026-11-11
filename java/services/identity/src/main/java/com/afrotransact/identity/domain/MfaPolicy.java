package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;

/** When a second factor is asked, when enrolment is forced, and whether a factor may be turned off. */
public interface MfaPolicy {

    Duration TRUSTED_BROWSER_TTL = Duration.ofDays(30);

    /** How long a skipped "Secure your account" prompt stays quiet before it is offered again. */
    Duration SETUP_REMINDER_INTERVAL = Duration.ofDays(14);

    /**
     * A code is asked ONLY when a factor is enrolled and the browser is neither trusted nor a passkey sign-in
     * (already phishing-resistant multi-factor). Never because of staff or portal rules: those force enrolment.
     */
    boolean secondFactorRequired(MfaEnrolment enrolment, boolean trustedBrowser, boolean phishingResistant);

    /**
     * Enrolment must be completed before a session is given: MFA is mandatory for the person, nothing is enrolled
     * and the first factor was not a passkey.
     */
    boolean enrolmentRequired(boolean mandatory, MfaEnrolment enrolment, boolean phishingResistant);

    /** Everyone but mandatory accounts may turn two-step verification off. */
    boolean mayDisable(boolean mandatory);

    /**
     * Whether one enrolled factor may be turned off now. Always, unless MFA is mandatory for the person: then only
     * while another code-based factor stays on, so the account never drops to "no second factor".
     */
    boolean mayRemove(boolean mandatory, MfaEnrolment enrolment, MfaFactor factor);

    /** Whether the gentle "secure your account" reminder is due again. */
    boolean reminderDue(MfaEnrolment enrolment, boolean mandatory, Instant lastDismissed, Instant now);
}
