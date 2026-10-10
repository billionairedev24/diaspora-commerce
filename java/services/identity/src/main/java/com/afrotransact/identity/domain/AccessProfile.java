package com.afrotransact.identity.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything needed to compute a user's effective access.
 *
 * @param user         the account
 * @param roleKeys     account and platform roles held directly
 * @param directGrants permissions granted directly to the user
 * @param memberships  organisation memberships with their role keys
 * @param roles        role definitions (with permissions) for every role key referenced above
 */
public record AccessProfile(UserAccount user, Set<String> roleKeys, Set<String> directGrants,
                            List<OrgMembership> memberships, Map<String, Role> roles) {

    public AccessProfile {
        roleKeys = Set.copyOf(roleKeys);
        directGrants = Set.copyOf(directGrants);
        memberships = List.copyOf(memberships);
        roles = Map.copyOf(roles);
    }

    public boolean holdsRole(String key) {
        return roleKeys.contains(key);
    }
}
