package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.SigningKey;

/**
 * JWKS signing keys (S291). Rotation makes a new key sign new tokens while the old key stays published until the
 * longest access token issued under it has expired, then retires it. Every method but {@link #seedIfEmpty} needs
 * {@code identity:admin}; rotation needs a reason and is written to the access change log.
 */
public interface SigningKeyAdminService {

    /** Newest first; keys past their retirement date are retired on the way. */
    List<SigningKey> list();

    SigningKey rotate(String reason);

    /**
     * System call at start-up (no actor): when no key is stored yet, the configured key becomes the active one, so the
     * tokens already issued keep verifying. Does nothing when keys exist.
     */
    void seedIfEmpty(String kid, String publicKeyPem, String privateKeyPem);
}
