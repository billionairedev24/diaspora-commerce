package com.afrotransact.identity.application;

/**
 * Unified sign-up (customers, sellers, providers).
 *
 * @param intent          shop | sell | provide — chooses which applicant role is added server-side
 * @param invitationToken optional organisation invitation token from the invite link; the email must match, the
 *                        account starts verified (the link proved the mailbox) and joins the organisation
 */
public record SignUpCommand(String firstName, String lastName, String email, String phone, String phoneRegion,
                            String password, String confirmPassword, boolean termsAccepted, boolean smsConsent,
                            String intent, String invitationToken) {

    public boolean invited() {
        return invitationToken != null && !invitationToken.isBlank();
    }
}
