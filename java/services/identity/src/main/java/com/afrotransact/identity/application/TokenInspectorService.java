package com.afrotransact.identity.application;

import java.util.Map;

import com.afrotransact.identity.domain.TokenInspection;

/**
 * Token inspector and the published OIDC documents (S291), {@code identity:admin}. A pasted token is decoded and
 * verified in memory: it is never stored, never logged and not part of the result.
 */
public interface TokenInspectorService {

    int MAX_TOKEN_LENGTH = 16_384;

    TokenInspection inspect(String token);

    /** The OIDC discovery document and the JWKS (public keys only). */
    Documents documents();

    record Documents(Map<String, Object> discovery, Map<String, Object> jwks) {
    }
}
