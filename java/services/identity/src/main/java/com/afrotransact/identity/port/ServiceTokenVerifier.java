package com.afrotransact.identity.port;

import java.util.Optional;

import com.afrotransact.platform.security.Actor;

/**
 * The service caller (a client-credentials token of an OAuth client) of the current request, verified against this
 * server's own signing keys. Needed where the shared security chain does not verify tokens (core on the local profile,
 * {@code at.security.enabled=false}): there every caller resolves to the local super admin, which would hide the BFF
 * from the first-party sign-in API.
 */
public interface ServiceTokenVerifier {

    /** The verified service actor of the current request; empty when there is none (or the chain verifies tokens itself). */
    Optional<Actor> verifiedService();
}
