package com.afrotransact.identity.port;

import java.util.Optional;
import java.util.UUID;

/** WebAuthn assertion ceremony for discoverable credentials (passkeys), outside a browser session. */
public interface PasskeyAssertions {

    /** A new request: random challenge for this relying party. */
    PasskeyRequest newRequest();

    /**
     * Verifies a {@code PublicKeyCredential} (JSON as produced by {@code navigator.credentials.get}, base64url
     * fields) against the request: signature, challenge, origin, RP id, user presence and verification.
     *
     * @return the account id of the passkey's owner; empty when the assertion is rejected
     */
    Optional<UUID> verify(PasskeyRequest request, String credentialJson);

    /**
     * @param challenge   base64url challenge
     * @param rpId        relying party id
     * @param optionsJson {@code PublicKeyCredentialRequestOptions} for {@code navigator.credentials.get}
     */
    record PasskeyRequest(String challenge, String rpId, String optionsJson) {
    }
}
