package com.afrotransact.identity.domain;

import java.time.Duration;

/** When a second factor is required and whether it may be turned off. */
public interface MfaPolicy {

    Duration TRUSTED_BROWSER_TTL = Duration.ofDays(30);

    /**
     * @param staff           holds a platform staff role (MFA is mandatory)
     * @param enrolment       enrolled factors
     * @param trustedBrowser  the browser presented a valid "trust this browser" token
     * @param phishingResistant the first factor was a passkey (already multi-factor)
     */
    boolean secondFactorRequired(boolean staff, MfaEnrolment enrolment, boolean trustedBrowser,
                                 boolean phishingResistant);

    /** Staff cannot disable MFA; everyone else can. */
    boolean mayDisable(boolean staff);

    /**
     * Whether one enrolled factor may be turned off now. Everyone but staff may; staff may switch methods (turn one
     * off) only while another enrolled factor stays on, so their account never drops to "no second factor".
     */
    boolean mayRemove(boolean staff, MfaEnrolment enrolment, MfaFactor factor);
}
