package com.afrotransact.offers.port;

/** offers-service's own client-credentials access token (identity), for service-to-service calls. */
public interface ServiceTokenProvider {

    /** A valid bearer token (cached until shortly before it expires). */
    String token();
}
