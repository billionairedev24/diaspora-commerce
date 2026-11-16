package com.afrotransact.ai.application;

import java.util.Set;

import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.web.error.ForbiddenActionException;

/**
 * The authenticated caller of an AI endpoint.
 *
 * @param subject       user or client id (budgets and memory are keyed by it)
 * @param principalType principal type from the token ({@code CUSTOMER}, {@code SELLER_MEMBER}, {@code STAFF} …)
 * @param orgId         organisation the caller acts for, may be null
 * @param permissions   effective permissions
 * @param bearerToken   the incoming access token, relayed to downstream services in other processes (null in the local profile)
 * @param user          the person of the request acting as a shopper, captured from the validated security context on the request
 *                      thread; Lola's worker thread has no security context, so this is what in-process readers act as. Null when
 *                      none could be established (a service token, no authentication, a test)
 */
public record Caller(String subject, String principalType, String orgId, Set<String> permissions,
                     String bearerToken, EndUserActor user) {

    /** A caller without a captured person (service callers, tests). */
    public Caller(String subject, String principalType, String orgId, Set<String> permissions, String bearerToken) {
        this(subject, principalType, orgId, permissions, bearerToken, null);
    }

    /** The same caller with the person of the request captured. */
    public Caller withUser(EndUserActor person) {
        return new Caller(subject, principalType, orgId, permissions, bearerToken, person);
    }

    /** The token and the person Lola's readers act as. */
    public Shopper shopper() {
        return new Shopper(bearerToken, user);
    }

    public Caller {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean can(String permission) {
        return permissions.contains(permission) || permissions.contains("*");
    }

    public void require(String permission) {
        if (!can(permission)) {
            throw new ForbiddenActionException("auth.missing-permission", "Missing permission " + permission);
        }
    }

    @Override
    public String toString() {
        return "Caller[subject=" + subject + ", type=" + principalType + ", token=" + (bearerToken == null ? "none"
                : "***") + "]";
    }
}
