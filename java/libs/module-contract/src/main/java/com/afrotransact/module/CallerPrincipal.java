package com.afrotransact.module;

import java.util.Set;

/**
 * Who a module is when it calls another module in-process: the in-process equivalent of the OAuth client-credentials token it
 * would send over HTTP. The scopes are the ones identity grants that service's client; the called module's application
 * services check them exactly as they do for a token.
 *
 * @param module   the calling module, for example {@code catalog}
 * @param clientId the OAuth client id the call stands for, for example {@code catalog-service}; becomes the actor's subject
 * @param scopes   the granted scopes, for example {@code tax:classify}
 */
public record CallerPrincipal(String module, String clientId, Set<String> scopes) {

    public CallerPrincipal {
        if (module == null || module.isBlank() || clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("A caller principal needs a module and a client id");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
