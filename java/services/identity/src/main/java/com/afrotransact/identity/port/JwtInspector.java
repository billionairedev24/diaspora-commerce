package com.afrotransact.identity.port;

import java.time.Instant;

import com.afrotransact.identity.domain.TokenInspection;

/** Decodes a JWT and checks its signature against the published keys, without trusting or keeping it. */
public interface JwtInspector {

    /** Never throws for a bad token: the result says what is wrong. */
    TokenInspection inspect(String token, String expectedIssuer, Instant now);
}
