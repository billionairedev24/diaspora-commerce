package com.afrotransact.platform.security.relationship;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;

/** RBAC-only mode (FGA disabled): delegates to {@link PermissionChecker}, ignoring relations. */
class RbacResourceAccessCheckerImpl implements ResourceAccessChecker {

    private final PermissionChecker permissions;

    RbacResourceAccessCheckerImpl(PermissionChecker permissions) {
        this.permissions = permissions;
    }

    @Override
    public Actor requireOwned(String permission, String relation, FgaObject resource, String ownerOrgId) {
        return permissions.requireForOrg(permission, ownerOrgId);
    }

    @Override
    public Actor requireForOrg(String permission, String relation, String orgId) {
        return permissions.requireForOrg(permission, orgId);
    }

    @Override
    public Actor require(String permission, String relation, FgaObject resource,
                         RelationshipTuple... contextualTuples) {
        return permissions.require(permission);
    }

    @Override
    public boolean relationshipsEnabled() {
        return false;
    }
}
