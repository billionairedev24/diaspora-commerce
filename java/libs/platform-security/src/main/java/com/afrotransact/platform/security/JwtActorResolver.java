package com.afrotransact.platform.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.afrotransact.platform.web.error.ForbiddenActionException;

/** Reads the actor from identity-service JWT claims: sub, pt, org, roles, perms, scope. */
public class JwtActorResolver implements ActorResolver {

    @Override
    public Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new ForbiddenActionException("auth.unauthenticated", "No authenticated actor");
        }
        Jwt jwt = token.getToken();
        Set<String> perms = new HashSet<>(strings(jwt.getClaim("perms")));
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            perms.addAll(List.of(scope.split(" ")));
        }
        String type = Optional.ofNullable(jwt.getClaimAsString("pt")).orElse("CUSTOMER");
        return new Actor(jwt.getSubject(),
                PrincipalType.valueOf(type.toUpperCase(Locale.ROOT)),
                Optional.ofNullable(jwt.getClaimAsString("org")),
                new HashSet<>(strings(jwt.getClaim("roles"))),
                perms);
    }

    private static List<String> strings(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
