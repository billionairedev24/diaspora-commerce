package com.afrotransact.identity.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Social sign-in (Google OIDC; Apple and Facebook when enabled). Resolves the account for provider claims:
 * an already linked identity; else link to the signed-in user ("link Google" in Login & security); else link to the
 * account with the same provider-verified email; else create a customer account that skips email verification.
 */
public interface FederatedLoginService {

    /** @return the account id that becomes the session principal and token {@code sub} */
    UUID signIn(FederatedProfile profile, Optional<UUID> signedInUser);
}
