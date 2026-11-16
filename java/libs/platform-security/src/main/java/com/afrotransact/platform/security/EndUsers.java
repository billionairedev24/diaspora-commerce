package com.afrotransact.platform.security;

import java.time.Instant;
import java.util.List;

import com.afrotransact.module.EndUserActor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The signed-in person of the request being served, as an {@link EndUserActor} for in-process calls made on their behalf.
 * Call it on the request thread, before the call crosses to another module's thread: only there is the SecurityContext.
 *
 * <p>With security on (every deployed environment) the actor comes from the validated JWT authentication in the SecurityContext
 * and from nothing else. With {@code at.security.enabled=false}, which {@code SecurityEnabledGuard} allows only in the local, test
 * and it profiles, nothing validates tokens and {@link LocalActorResolver} decides who the caller is; this class then builds the
 * authentication that resolver implies (a person's unsigned token or the local super admin) and passes it through the same
 * {@link EndUserActor#fromAuthentication} door, so there is still one way to get an actor.
 */
public class EndUsers {

    private final boolean securityEnabled;

    public EndUsers(boolean securityEnabled) {
        this.securityEnabled = securityEnabled;
    }

    /** The person of this request, exactly as their token says. */
    public EndUserActor current() {
        return EndUserActor.fromAuthentication(authentication());
    }

    /** The person of this request acting as a shopper ({@code X-Acting-As: CUSTOMER}, which checkout sends to identity and cart). */
    public EndUserActor currentAsCustomer() {
        return current().asCustomer();
    }

    private Authentication authentication() {
        if (securityEnabled) {
            return SecurityContextHolder.getContext().getAuthentication();
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        String header = attrs instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader(HttpHeaders.AUTHORIZATION) : null;
        return localAuthentication(LocalActorResolver.actorFor(header));
    }

    /** What the local profile (security off) treats as authenticated: the actor the local resolver gives the request. */
    static Authentication localAuthentication(Actor actor) {
        Jwt jwt = Jwt.withTokenValue("local-development").header("alg", "none").subject(actor.subject())
                .claim("pt", actor.type().name()).claim("roles", List.copyOf(actor.roles()))
                .claim("perms", List.copyOf(actor.permissions()))
                .claims(c -> actor.orgId().ifPresent(org -> c.put("org", org)))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }
}
