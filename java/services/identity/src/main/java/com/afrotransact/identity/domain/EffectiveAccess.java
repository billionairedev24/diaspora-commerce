package com.afrotransact.identity.domain;

import java.util.Optional;
import java.util.Set;

/**
 * Resolved access carried in tokens.
 *
 * @param principalType {@code pt} claim
 * @param orgId         {@code org} claim, present while acting for an organisation
 * @param roles         {@code roles} claim
 * @param permissions   {@code perms} claim: union of role permissions and direct grants
 * @param staff         holds at least one platform staff role
 */
public record EffectiveAccess(PrincipalType principalType, Optional<String> orgId, Set<String> roles,
                              Set<String> permissions, boolean staff) {

    public EffectiveAccess {
        orgId = orgId == null ? Optional.empty() : orgId;
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
