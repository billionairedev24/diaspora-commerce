package com.afrotransact.ai.application;

import java.util.Set;

import com.afrotransact.platform.web.error.ForbiddenActionException;

/**
 * The authenticated caller of an AI endpoint.
 *
 * @param subject       user or client id (budgets and memory are keyed by it)
 * @param principalType principal type from the token ({@code CUSTOMER}, {@code SELLER_MEMBER}, {@code STAFF} …)
 * @param orgId         organisation the caller acts for, may be null
 * @param permissions   effective permissions
 * @param bearerToken   the incoming access token, relayed to downstream services (null in the local profile)
 */
public record Caller(String subject, String principalType, String orgId, Set<String> permissions,
                     String bearerToken) {

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
