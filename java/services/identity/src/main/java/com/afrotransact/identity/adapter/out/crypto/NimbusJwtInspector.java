package com.afrotransact.identity.adapter.out.crypto;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.afrotransact.identity.domain.TokenInspection;
import com.afrotransact.identity.port.JwtInspector;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Decodes a pasted JWT and checks its signature against the keys this server publishes. The token and its signature
 * are only held in local variables: nothing is stored, logged or returned. Only RS256 is accepted.
 */
public class NimbusJwtInspector implements JwtInspector {

    private final JWKSource<SecurityContext> keys;

    public NimbusJwtInspector(JWKSource<SecurityContext> keys) {
        this.keys = keys;
    }

    @Override
    public TokenInspection inspect(String token, String expectedIssuer, Instant now) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException notAJwt) {
            return TokenInspection.malformed("This is not a signed JWT (three base64url parts with JSON header and claims)");
        }
        List<String> problems = new ArrayList<>();
        String kid = jwt.getHeader().getKeyID();

        boolean signatureValid = false;
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            problems.add("The algorithm is " + jwt.getHeader().getAlgorithm() + "; this server signs with RS256 only");
        } else {
            signatureValid = verify(jwt, kid, problems);
        }

        boolean issuerValid = expectedIssuer.equals(claims.getIssuer());
        if (!issuerValid) {
            problems.add("The issuer is " + (claims.getIssuer() == null ? "missing" : claims.getIssuer())
                    + ", not " + expectedIssuer);
        }
        Date exp = claims.getExpirationTime();
        Boolean expired = exp == null ? null : !exp.toInstant().isAfter(now);
        if (Boolean.TRUE.equals(expired)) {
            problems.add("The token expired at " + exp.toInstant());
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && nbf.toInstant().isAfter(now)) {
            problems.add("The token is not valid before " + nbf.toInstant());
        }
        Date iat = claims.getIssueTime();
        boolean valid = signatureValid && issuerValid && !Boolean.TRUE.equals(expired)
                && (nbf == null || !nbf.toInstant().isAfter(now));
        Map<String, Object> claimMap = claims.toJSONObject();
        return new TokenInspection(true, jwt.getHeader().toJSONObject(), claimMap, scopes(claimMap), kid,
                iat == null ? null : iat.toInstant(), exp == null ? null : exp.toInstant(), expired, signatureValid,
                issuerValid, valid, problems);
    }

    private boolean verify(SignedJWT jwt, String kid, List<String> problems) {
        try {
            JWKMatcher.Builder match = new JWKMatcher.Builder();
            if (kid != null) {
                match.keyID(kid);
            }
            List<JWK> candidates = keys.get(new JWKSelector(match.build()), null);
            if (candidates.isEmpty()) {
                problems.add(kid == null ? "The token names no key id and no key is published"
                        : "No published key has the id " + kid + " (rotated out and retired, or another issuer)");
                return false;
            }
            for (JWK candidate : candidates) {
                if (candidate instanceof RSAKey rsa && jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) {
                    return true;
                }
            }
            problems.add("The signature does not verify against the published key"
                    + (candidates.size() > 1 ? "s" : ""));
            return false;
        } catch (JOSEException ex) {
            problems.add("The signature could not be checked: " + ex.getMessage());
            return false;
        }
    }

    private static List<String> scopes(Map<String, Object> claims) {
        // Spring Authorization Server writes "scope" as an array; RFC 9068 and other issuers use a space-separated string.
        for (String name : List.of("scope", "scp")) {
            Object scope = claims.get(name);
            if (scope instanceof String s && !s.isBlank()) {
                return Arrays.asList(s.trim().split("\\s+"));
            }
            if (scope instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }
}
