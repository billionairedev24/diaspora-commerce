package com.afrotransact.identity.adapter.out.seller;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Identity's own service access token for calls to other services: identity is the issuer, so it signs a short-lived
 * client-credentials-shaped token itself (pt SERVICE, the scopes as permissions) instead of asking its own token
 * endpoint. Cached until shortly before it expires.
 */
public class SelfIssuedServiceToken implements Supplier<String> {

    static final String SUBJECT = "identity-service";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration RENEW_BEFORE = Duration.ofMinutes(1);

    private final JwtEncoder encoder;
    private final String issuer;
    private final List<String> scopes;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public SelfIssuedServiceToken(JwtEncoder encoder, String issuer, List<String> scopes, Clock clock) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.scopes = List.copyOf(scopes);
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        Instant now = Instant.now(clock);
        if (token == null || now.isAfter(expiresAt.minus(RENEW_BEFORE))) {
            expiresAt = now.plus(TTL);
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(SUBJECT).issuedAt(now)
                    .expiresAt(expiresAt).claim("client_id", SUBJECT).claim("pt", "SERVICE").claim("roles", List.of())
                    .claim("perms", scopes).claim("scope", String.join(" ", scopes)).build();
            token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                    .getTokenValue();
        }
        return token;
    }
}
