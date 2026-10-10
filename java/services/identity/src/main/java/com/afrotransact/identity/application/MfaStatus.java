package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.MfaEnrolment;

/** Two-step verification settings for the Login & security page. */
public record MfaStatus(MfaEnrolment enrolment, boolean required, boolean mayDisable) {
}
