package com.afrotransact.identity.config;

import java.util.List;

import com.nimbusds.jose.jwk.JWK;

/** The signing key from the configuration (first, with its private half) and the configured previous public keys. */
record ConfiguredSigningKeys(List<JWK> keys) {

    ConfiguredSigningKeys {
        keys = List.copyOf(keys);
    }
}
