package com.afrotransact.identity.adapter.in.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.port.ServiceTokenVerifier;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PlatformSecurityProperties;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Verifies the bearer token of the request with identity's own decoder (signature, expiry, issuer) and returns it as a
 * SERVICE actor when it is a client-credentials token ({@code pt=SERVICE}). Only active with security off: with security
 * on the resource-server chain has verified the token already and the normal actor resolver is right.
 */
@Component
class BearerServiceTokenVerifier implements ServiceTokenVerifier {

    private static final String BEARER = "Bearer ";

    private final PlatformSecurityProperties security;
    private final JwtDecoder decoder;

    BearerServiceTokenVerifier(PlatformSecurityProperties security, JwtDecoder decoder) {
        this.security = security;
        this.decoder = decoder;
    }

    @Override
    public Optional<Actor> verifiedService() {
        if (security.enabled()) {
            return Optional.empty();
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) {
            return Optional.empty();
        }
        String header = servlet.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(header.substring(BEARER.length()).trim());
            if (!"SERVICE".equalsIgnoreCase(jwt.getClaimAsString("pt")) || jwt.getSubject() == null) {
                return Optional.empty();
            }
            Set<String> perms = new HashSet<>(strings(jwt.getClaim("perms")));
            String scope = jwt.getClaimAsString("scope");
            if (scope != null && !scope.isBlank()) {
                perms.addAll(List.of(scope.trim().split("\\s+")));
            }
            return Optional.of(new Actor(jwt.getSubject(), PrincipalType.SERVICE, Optional.empty(),
                    new HashSet<>(strings(jwt.getClaim("roles"))), perms));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> strings(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
