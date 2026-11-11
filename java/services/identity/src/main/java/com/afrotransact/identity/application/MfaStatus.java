package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.MfaEnrolment;

/**
 * Two-step verification settings for the Login & security page. Only enabled factors are listed: SMS is hidden
 * ({@code smsAvailable=false}, enrolment never reported) while the SMS feature flag is off.
 *
 * @param required        MFA is mandatory for this person (staff, or their portal's Auth setting): they must keep a
 *                        factor on and could not have signed in without one
 * @param mayDisable      two-step verification may be turned off altogether (never when {@code required})
 * @param mayRemoveTotp   the authenticator app may be turned off now (when required: only while another factor stays on)
 * @param mayRemoveSms    SMS codes may be turned off now
 * @param mayRemoveEmailOtp email codes may be turned off now
 * @param setupSuggested  show the gentle "Secure your account" reminder (nothing enrolled, not mandatory, and not
 *                        dismissed within the reminder interval)
 * @param smsAvailable    SMS codes are offered at all (feature flag)
 */
public record MfaStatus(MfaEnrolment enrolment, boolean required, boolean mayDisable, boolean mayRemoveTotp,
                        boolean mayRemoveSms, boolean mayRemoveEmailOtp, boolean setupSuggested,
                        boolean smsAvailable) {
}
