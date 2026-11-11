package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.TrustedBrowser;

/**
 * Self-service two-step verification for the signed-in user: authenticator app and email codes (SMS only while its
 * feature flag is on). Turning a factor off needs the password; where MFA is mandatory (staff, portal switches) a
 * factor can only be switched off while another stays on.
 */
public interface MfaService {

    MfaStatus status();

    /** Starts (or restarts) authenticator-app enrolment. */
    TotpSetup startTotp();

    /** Sends a first code to the verified account email to turn on email codes; returns the code expiry. */
    Instant startEmailOtp();

    /** Confirms the emailed code and turns email codes on; returns recovery codes when the person had none. */
    List<String> confirmEmailOtp(String code);

    /** Turns email codes off; needs the account password (403 identity.mfa-required while mandatory and last). */
    void disableEmailOtp(String password);

    /** "Not now": hides the reminder to secure the account until the reminder interval has passed. */
    void dismissSetupPrompt();

    /** Confirms enrolment with a code; returns new recovery codes when the user had none. */
    List<String> confirmTotp(String code);

    /** Turns the authenticator off; needs the account password. */
    void disableTotp(String password);

    /** Texts a code to the account phone to confirm SMS sign-in codes; returns the code expiry. */
    Instant startSms();

    List<String> confirmSms(String code);

    void disableSms(String password);

    /** Replaces all recovery codes; the new ones are shown once. */
    List<String> regenerateRecoveryCodes();

    /** Browsers that skip the second factor ("Trust this browser"), unexpired, newest first. */
    List<TrustedBrowser> trustedBrowsers();

    /** Forgets one trusted browser (404 identity.trusted-browser-not-found). */
    void forgetTrustedBrowser(UUID browserId);

    /** Forgets every trusted browser of the account. */
    void forgetTrustedBrowsers();
}
