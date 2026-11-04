package com.afrotransact.identity.application;

/**
 * Unified sign-up (customers, sellers, providers).
 *
 * @param intent          shop | sell | provide — chooses which applicant role is added server-side
 * @param invitationToken optional organisation invitation token from the invite link; the email must match, the
 *                        account starts verified (the link proved the mailbox) and joins the organisation
 * @param referralCode    optional referral code of the friend who shared AfroTransact (S322); not validated here, the
 *                        referral program attaches it when it knows the code
 * @param deviceId        the sign-up browser's device cookie, or null; only a keyed hash leaves identity
 * @param clientAddress   the caller's network address as the server saw it, or null; only a keyed hash of its
 *                        network leaves identity
 */
public record SignUpCommand(String firstName, String lastName, String email, String phone, String phoneRegion,
                            String password, String confirmPassword, boolean termsAccepted, boolean smsConsent,
                            String intent, String invitationToken, String referralCode, String deviceId,
                            String clientAddress) {

    public boolean invited() {
        return invitationToken != null && !invitationToken.isBlank();
    }
}
