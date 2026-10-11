package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.TrustedBrowser;

/**
 * Self-service two-step verification for the signed-in user. Staff cannot turn MFA off: they may switch methods,
 * turning one factor off only while another stays on.
 */
public interface MfaService {

    MfaStatus status();

    /** Starts (or restarts) authenticator-app enrolment. */
    TotpSetup startTotp();

    /** Confirms enrolment with a code; returns new recovery codes when the user had none. */
    List<String> confirmTotp(String code);

    void disableTotp();

    /** Texts a code to the account phone to confirm SMS sign-in codes; returns the code expiry. */
    Instant startSms();

    List<String> confirmSms(String code);

    void disableSms();

    /** Replaces all recovery codes; the new ones are shown once. */
    List<String> regenerateRecoveryCodes();

    /** Browsers that skip the second factor ("Trust this browser"), unexpired, newest first. */
    List<TrustedBrowser> trustedBrowsers();

    /** Forgets one trusted browser (404 identity.trusted-browser-not-found). */
    void forgetTrustedBrowser(UUID browserId);

    /** Forgets every trusted browser of the account. */
    void forgetTrustedBrowsers();
}
