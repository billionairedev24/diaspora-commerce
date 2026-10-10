package com.afrotransact.identity.application;

import java.util.Set;

/**
 * @param clientIds OAuth clients allowed to call the first-party sign-in API and redeem login tickets
 *                  (client-credentials token with scope {@code auth:first-party}); {@code gateway-bff}
 */
public record FirstPartySignInSettings(Set<String> clientIds) {

    public FirstPartySignInSettings {
        clientIds = clientIds == null ? Set.of() : Set.copyOf(clientIds);
    }
}
