package com.afrotransact.platform.security;

import java.text.ParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Local-profile resolver used when {@code at.security.enabled=false}: a staff super admin with every permission.
 *
 * <p>Customers, drivers and seller / provider members are the exception. Their data is keyed by who they are (a
 * shopper's cart, orders and returns; a driver's routes; an organisation's shipments, which only its own members may
 * confirm, pack and mark ready), so a request that carries an identity-issued token of a {@code CUSTOMER},
 * {@code DRIVER}, {@code SELLER_MEMBER} or {@code PROVIDER_MEMBER} acts as that person (and organisation), with the
 * token's own permissions. The claims are read without checking the signature: this resolver only runs with security
 * off, where every caller is already the super admin, so reading the token can only lower what the caller may do,
 * never raise it. Staff keep the super admin's permissions but act as themselves (their own subject and roles), so
 * rules about who did something, such as the two-step review where the final approver must be a different person than
 * the reviewer, work locally too. Services and calls without a usable token keep the super admin.
 */
public class LocalActorResolver implements ActorResolver {

    /** The actor every call gets locally unless it carries a customer, driver or organisation member's token. */
    public static final Actor LOCAL_SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));

    private static final Set<PrincipalType> ACT_AS_THEMSELVES = Set.of(PrincipalType.CUSTOMER, PrincipalType.DRIVER,
            PrincipalType.SELLER_MEMBER, PrincipalType.PROVIDER_MEMBER);
    private static final String BEARER = "Bearer ";

    @Override
    public Actor current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            return actorFor(servlet.getRequest().getHeader(HttpHeaders.AUTHORIZATION));
        }
        return LOCAL_SUPER_ADMIN;
    }

    /** The actor of an {@code Authorization} header value (null, blank or not a bearer token: the super admin). */
    public static Actor actorFor(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return LOCAL_SUPER_ADMIN;
        }
        try {
            JWTClaimsSet claims = JWTParser.parse(authorization.substring(BEARER.length()).trim()).getJWTClaimsSet();
            PrincipalType type = principalType(claims.getClaim("pt"));
            String subject = claims.getSubject();
            if (type == null || subject == null || subject.isBlank()) {
                return LOCAL_SUPER_ADMIN;
            }
            if (type == PrincipalType.STAFF) {
                Set<String> roles = new HashSet<>(strings(claims.getClaim("roles")));
                roles.addAll(LOCAL_SUPER_ADMIN.roles());
                return new Actor(subject, PrincipalType.STAFF, Optional.empty(), roles, Set.of("*"));
            }
            if (!ACT_AS_THEMSELVES.contains(type)) {
                return LOCAL_SUPER_ADMIN;
            }
            Set<String> perms = new HashSet<>(strings(claims.getClaim("perms")));
            perms.addAll(strings(claims.getClaim("scope")));
            perms.remove("*");
            Object org = claims.getClaim("org");
            return new Actor(subject, type, Optional.ofNullable(org == null ? null : org.toString()),
                    new HashSet<>(strings(claims.getClaim("roles"))), perms);
        } catch (ParseException | RuntimeException e) {
            return LOCAL_SUPER_ADMIN;
        }
    }

    private static PrincipalType principalType(Object claim) {
        if (claim == null) {
            return null;
        }
        try {
            return PrincipalType.valueOf(claim.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A list claim, or a space-separated string claim ({@code scope}). */
    private static List<String> strings(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        if (claim instanceof String s && !s.isBlank()) {
            return List.of(s.trim().split("\\s+"));
        }
        return List.of();
    }
}
