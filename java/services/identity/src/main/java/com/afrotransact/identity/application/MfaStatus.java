package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.MfaEnrolment;

/**
 * Two-step verification settings for the Login & security page.
 *
 * @param mayDisable    two-step verification may be turned off altogether (never for staff)
 * @param mayRemoveTotp the authenticator app may be turned off now (staff: only while SMS codes stay on)
 * @param mayRemoveSms  SMS codes may be turned off now (staff: only while the authenticator app stays on)
 */
public record MfaStatus(MfaEnrolment enrolment, boolean required, boolean mayDisable, boolean mayRemoveTotp,
                        boolean mayRemoveSms) {
}
