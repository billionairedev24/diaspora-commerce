package com.afrotransact.identity.port;

import java.util.Map;

/** The OIDC discovery document and the JWKS this server publishes (public keys only). */
public interface DiscoveryReader {

    Map<String, Object> discovery();

    Map<String, Object> jwks();
}
