package com.afrotransact.identity.application;

import java.util.Set;

/**
 * @param clientIds       OAuth clients allowed to call the first-party sign-in API and redeem login tickets
 *                        (client-credentials token with scope {@code auth:first-party}); {@code gateway-bff}
 * @param nativeClientIds public native-app clients ({@code customer-app}, {@code driver-app}) the BFF may request a
 *                        login ticket for, so the app gets its own authorization code (PKCE) after an in-app sign-in
 */
public record FirstPartySignInSettings(Set<String> clientIds, Set<String> nativeClientIds) {

    public FirstPartySignInSettings {
        clientIds = clientIds == null ? Set.of() : Set.copyOf(clientIds);
        nativeClientIds = nativeClientIds == null ? Set.of() : Set.copyOf(nativeClientIds);
    }

    public FirstPartySignInSettings(Set<String> clientIds) {
        this(clientIds, Set.of());
    }
}
