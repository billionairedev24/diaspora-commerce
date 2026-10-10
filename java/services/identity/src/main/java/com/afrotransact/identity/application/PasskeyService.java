package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.Passkey;

/**
 * Passkeys (WebAuthn) for the signed-in user. The hosted pages register and sign in with Spring Security's WebAuthn
 * endpoints ({@code /webauthn/register/options}, {@code /webauthn/register}, {@code /webauthn/authenticate/options},
 * {@code /login/webauthn}); the first-party API and the mobile apps register with a bearer token through
 * {@link #startRegistration} / {@link #completeRegistration} (S016). All share one relying party and credential store.
 */
public interface PasskeyService {

    List<Passkey> list();

    /** Creation options for a new passkey of the signed-in account (good for five minutes, one registration). */
    PasskeyRegistrationView startRegistration(String label);

    /**
     * Verifies the {@code PublicKeyCredential} (JSON from {@code navigator.credentials.create} or the platform passkey
     * API) and stores it. 422 {@code identity.passkey-rejected} when the attestation fails; 409
     * {@code identity.passkey-registration-expired} when the registration expired, was used or belongs to someone else.
     */
    Passkey completeRegistration(UUID registrationId, String credentialJson, String label);

    /** Renames one of the account's passkeys (1–64 characters). */
    Passkey rename(String credentialId, String label);

    /** Cannot remove the last sign-in method. */
    void delete(String credentialId);
}
