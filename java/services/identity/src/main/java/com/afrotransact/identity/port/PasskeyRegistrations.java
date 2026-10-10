package com.afrotransact.identity.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.Passkey;

/**
 * WebAuthn registration ceremony outside a browser session (S016): the first-party API and the mobile apps (platform
 * passkeys: Face ID, Touch ID, fingerprint) register a passkey for the signed-in account with a bearer token.
 */
public interface PasskeyRegistrations {

    /**
     * Creation options for {@code accountId} (its WebAuthn user handle is created on first use, as on the hosted page):
     * discoverable credential, user verification required, the account's existing passkeys excluded.
     */
    RegistrationRequest newRegistration(UUID accountId, String displayName);

    /**
     * Verifies a {@code PublicKeyCredential} from {@code navigator.credentials.create} (JSON, base64url fields) against
     * the request — challenge, origin, RP id, user presence and verification, algorithm — and stores the credential
     * with {@code label}. Empty when the attestation is rejected.
     */
    Optional<Passkey> register(RegistrationRequest request, String credentialJson, String label);

    /** Renames one of the account's passkeys; false when it is not the account's. */
    boolean rename(UUID accountId, String credentialId, String label);

    /**
     * @param challenge   base64url challenge
     * @param userHandle  base64url WebAuthn user handle of the account
     * @param rpId        relying party id
     * @param optionsJson {@code PublicKeyCredentialCreationOptions} for {@code navigator.credentials.create}
     */
    record RegistrationRequest(String challenge, String userHandle, String rpId, String optionsJson) {
    }
}
