package com.afrotransact.catalog.port;

/** catalog-service's own client-credentials access token (identity), for service-to-service calls. */
public interface ServiceTokens {

    /** A valid bearer token, or null when no client secret is configured (local profile without identity). */
    String token();
}
