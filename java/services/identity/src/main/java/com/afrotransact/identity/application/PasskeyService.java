package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.Passkey;

/**
 * Passkeys (WebAuthn) for the signed-in user. Registration and passkey sign-in use Spring Security's WebAuthn
 * endpoints on the authorization server ({@code /webauthn/register/options}, {@code /webauthn/register},
 * {@code /webauthn/authenticate/options}, {@code /login/webauthn}); this service lists and removes them.
 */
public interface PasskeyService {

    List<Passkey> list();

    /** Cannot remove the last sign-in method. */
    void delete(String credentialId);
}
