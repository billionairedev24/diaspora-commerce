package com.afrotransact.identity.port;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Roles and direct permission grants held by users. */
public interface UserAccessRepository {

    Set<String> roleKeys(UUID userId);

    /** @return true when the role was newly granted */
    boolean grantRole(UUID userId, String roleKey, String grantedBy);

    /** @return true when the user held the role */
    boolean revokeRole(UUID userId, String roleKey);

    /** S248: markets each of the user's platform roles is scoped to; roles missing from the map are platform-wide. */
    Map<String, Set<String>> roleMarkets(UUID userId);

    /** @return true when the market was newly added to the role (the user must hold the role) */
    boolean addRoleMarket(UUID userId, String roleKey, String market, String grantedBy);

    /** @return true when the role was scoped to the market */
    boolean removeRoleMarket(UUID userId, String roleKey, String market);

    Set<String> directGrants(UUID userId);

    boolean grantPermission(UUID userId, String permissionKey, String grantedBy);

    boolean revokePermission(UUID userId, String permissionKey);
}
