package com.afrotransact.module;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The signed-in person on whose behalf one module calls another in-process (the in-process equivalent of relaying the
 * person's bearer token over HTTP). It carries exactly what the validated token carries: subject, principal type,
 * organisation, roles and permissions plus scopes.
 *
 * <p><b>It cannot be forged.</b> The interface is sealed and its only implementation is package-private, so the only way to get
 * one is {@link #fromAuthentication(Authentication)}, which accepts nothing but an already authenticated
 * {@link JwtAuthenticationToken} (what the resource-server filter put in the SecurityContext after validating the signature, issuer,
 * audience and expiry). It never reads request parameters, headers or free strings. A token of a service (client credentials,
 * {@code pt=SERVICE}) is refused: a service acts as a {@link CallerPrincipal}, never as a person, and a person never becomes a
 * service. (A public record cannot hide its canonical constructor, hence a sealed interface rather than a record.)
 *
 * <p>The callee's published API takes the actor explicitly as a parameter and enters through
 * {@code ModuleEntry.runAsUser}; its application service then applies the same permission, organisation and ownership
 * checks as the HTTP controller. The calling module still passes through the bounded call gate under its own
 * {@link CallerPrincipal}, whose scopes decide only whether that module may act for users on that API at all.
 */
public sealed interface EndUserActor permits VerifiedEndUser {

    /** Account id (token subject). */
    String userId();

    /** The token's {@code pt} claim, upper case ({@code CUSTOMER}, {@code SELLER_MEMBER}, {@code STAFF}, ...); never {@code SERVICE}. */
    String principalType();

    /** The organisation the token acts for (claim {@code org}), empty for a plain customer. */
    Optional<String> organisationId();

    /** Every organisation the token names ({@code org} and, when present, {@code orgs}). */
    Set<String> organisationIds();

    /** Role keys, as in the token. */
    Set<String> roles();

    /** Permissions and scopes as the HTTP path sees them: the {@code perms} claim plus the space-separated {@code scope} claim. */
    Set<String> scopes();

    /** True when the actor holds {@code scope} (or {@code *}, which only a local super admin has). */
    default boolean hasScope(String scope) {
        return scopes().contains(scope) || scopes().contains("*");
    }

    /** Refuses with status 403 {@code auth.missing-permission} unless the actor holds {@code scope}. */
    default void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw new ModuleApiException(403, "auth.missing-permission", "Missing permission " + scope);
        }
    }

    /** Refuses with status 403 {@code auth.org-mismatch} unless the actor belongs to organisation {@code organisationId}. */
    default void requireOrganisation(String organisationId) {
        if (organisationId == null || !organisationIds().contains(organisationId)) {
            throw new ModuleApiException(403, "auth.org-mismatch", "The signed-in user does not act for this organisation");
        }
    }

    /**
     * The same person acting as a shopper ({@code X-Acting-As: CUSTOMER}): subject kept, organisation dropped, only {@code account.*}
     * roles kept, permissions kept (a staff member keeps just {@code mcp:shopper}). It can only narrow; services, partner apps and
     * AI agents keep their type. Mirrors {@code ActingAsActorResolver} exactly (a test holds the two together).
     */
    EndUserActor asCustomer();

    /**
     * The actor of the request being served, built from its authenticated security context.
     *
     * @param authentication what {@code SecurityContextHolder.getContext().getAuthentication()} holds on the request thread
     * @throws ModuleApiException 401 {@code auth.unauthenticated} when there is no validated JWT authentication; 403
     *                            {@code auth.service-token} for a service token
     */
    static EndUserActor fromAuthentication(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
                || token.getToken().getSubject() == null || token.getToken().getSubject().isBlank()) {
            throw new ModuleApiException(401, "auth.unauthenticated", "No authenticated user");
        }
        Jwt jwt = token.getToken();
        String type = Optional.ofNullable(jwt.getClaimAsString("pt")).orElse("CUSTOMER").trim().toUpperCase(Locale.ROOT);
        if ("SERVICE".equals(type)) {
            throw new ModuleApiException(403, "auth.service-token",
                    "A service token cannot act as an end user; use the caller principal");
        }
        Set<String> scopes = new HashSet<>(strings(jwt.getClaim("perms")));
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            scopes.addAll(List.of(scope.trim().split("\\s+")));
        }
        Set<String> orgs = new LinkedHashSet<>();
        String org = jwt.getClaimAsString("org");
        if (org != null && !org.isBlank()) {
            orgs.add(org);
        }
        orgs.addAll(strings(jwt.getClaim("orgs")));
        return new VerifiedEndUser(jwt.getSubject(), type, Optional.ofNullable(org == null || org.isBlank() ? null : org),
                orgs, new HashSet<>(strings(jwt.getClaim("roles"))), scopes);
    }

    private static List<String> strings(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
