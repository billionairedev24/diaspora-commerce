package com.afrotransact.identity.port;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.Passkey;

/** WebAuthn credentials registered through Spring Security's {@code /webauthn/register} endpoints. */
public interface PasskeyStore {

    List<Passkey> list(UUID userId);

    boolean delete(UUID userId, String credentialId);
}
