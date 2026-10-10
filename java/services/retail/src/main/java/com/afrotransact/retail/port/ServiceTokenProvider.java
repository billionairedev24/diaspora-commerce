package com.afrotransact.retail.port;

import java.util.Optional;

/** Client-credentials access token of retail-service (client id {@code retail-service}), cached until near expiry. */
public interface ServiceTokenProvider {

    /** The bearer token, or empty when no client secret is configured (local profile, security off). */
    Optional<String> token();
}
