package com.afrotransact.identity.port;

import java.util.Set;
import java.util.UUID;

/** Roles and direct permission grants held by users. */
public interface UserAccessRepository {

    Set<String> roleKeys(UUID userId);

    /** @return true when the role was newly granted */
    boolean grantRole(UUID userId, String roleKey, String grantedBy);

    /** @return true when the user held the role */
    boolean revokeRole(UUID userId, String roleKey);

    Set<String> directGrants(UUID userId);

    boolean grantPermission(UUID userId, String permissionKey, String grantedBy);

    boolean revokePermission(UUID userId, String permissionKey);
}
