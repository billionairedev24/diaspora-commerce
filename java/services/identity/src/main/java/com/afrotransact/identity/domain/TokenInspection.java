package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the token inspector found out about a pasted JWT. The token itself and its signature are never part of this.
 *
 * @param wellFormed     the text is a signed JWT (three dot-separated parts with JSON header and claims)
 * @param signatureValid the signature verifies against a key this server publishes
 * @param issuerValid    the {@code iss} claim is this server's issuer
 * @param expired        the {@code exp} claim is in the past (null when there is none)
 * @param valid          well formed, signature and issuer valid and not expired
 * @param problems       one sentence per thing wrong
 */
public record TokenInspection(boolean wellFormed, Map<String, Object> header, Map<String, Object> claims,
                              List<String> scopes, String keyId, Instant issuedAt, Instant expiresAt,
                              Boolean expired, boolean signatureValid, boolean issuerValid, boolean valid,
                              List<String> problems) {

    public TokenInspection {
        header = header == null ? Map.of() : Map.copyOf(header);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        problems = List.copyOf(problems);
    }

    public static TokenInspection malformed(String problem) {
        return new TokenInspection(false, null, null, null, null, null, null, null, false, false, false,
                List.of(problem));
    }
}
