package com.afrotransact.platform.security;

import java.util.Optional;
import java.util.Set;

/**
 * The authenticated caller as seen by a service.
 *
 * @param subject     stable user / client id
 * @param type        principal type
 * @param orgId       seller or provider organisation id when the actor acts for one
 * @param roles       role keys, for example {@code platform.super_admin} or {@code seller.owner}
 * @param permissions effective permissions, for example {@code catalog:write} or {@code delivery:operate}
 */
public record Actor(String subject, PrincipalType type, Optional<String> orgId, Set<String> roles,
                    Set<String> permissions) {

    public Actor {
        orgId = orgId == null ? Optional.empty() : orgId;
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    public boolean can(String permission) {
        return permissions.contains(permission) || permissions.contains("*");
    }
}
