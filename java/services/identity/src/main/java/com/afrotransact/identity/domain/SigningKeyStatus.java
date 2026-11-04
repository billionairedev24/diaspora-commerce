package com.afrotransact.identity.domain;

/** ACTIVE signs new tokens; RETIRING is still published so tokens signed with it verify; RETIRED is gone from the JWKS. */
public enum SigningKeyStatus {
    ACTIVE,
    RETIRING,
    RETIRED
}
