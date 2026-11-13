package com.afrotransact.platform.security.relationship;

import java.util.Arrays;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;

/**
 * RBAC-only mode (FGA disabled): delegates to {@link PermissionChecker}, ignoring relations, but still verifies the
 * owner organisation of a resource for non-staff callers (docs/engineering/tenant-isolation.md).
 */
class RbacResourceAccessCheckerImpl implements ResourceAccessChecker {

    private static final String ORG_PREFIX = FgaObject.ORGANIZATION + ":";

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
        Actor actor = permissions.require(permission);
        if (actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE) {
            return actor;
        }
        // Second layer without OpenFGA: an org member (or customer/driver) may only reach a resource whose owner
        // organisation is stated by the caller's contextual tuples and equals the caller's own org claim. An unknown
        // owner fails closed.
        String key = resource.key();
        boolean ownedByCaller = actor.orgId().isPresent() && Arrays.stream(contextualTuples)
                .filter(t -> "org".equals(t.relation()) && key.equals(t.object())
                        && t.user().startsWith(ORG_PREFIX))
                .map(t -> t.user().substring(ORG_PREFIX.length()))
                .anyMatch(owner -> owner.equals(actor.orgId().get()));
        if (!ownedByCaller) {
            throw new ForbiddenActionException("auth.wrong-org",
                    "Actor does not belong to the organisation that owns " + key);
        }
        return actor;
    }

    @Override
    public boolean relationshipsEnabled() {
        return false;
    }
}
