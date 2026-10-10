package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;

/** Self-service two-step verification for the signed-in user. Staff cannot turn MFA off. */
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
}
