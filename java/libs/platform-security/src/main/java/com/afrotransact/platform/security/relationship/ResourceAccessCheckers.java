package com.afrotransact.platform.security.relationship;

import com.afrotransact.platform.security.PermissionChecker;

/** Factories for the two {@link ResourceAccessChecker} modes (used by the auto-configuration and by tests). */
public final class ResourceAccessCheckers {

    private ResourceAccessCheckers() {
    }

    /** FGA disabled: today's RBAC behaviour. */
    public static ResourceAccessChecker rbacOnly(PermissionChecker permissions) {
        return new RbacResourceAccessCheckerImpl(permissions);
    }

    /** FGA enabled: RBAC permission AND relationship; {@code enforce=false} only logs denials (shadow mode). */
    public static ResourceAccessChecker withRelationships(PermissionChecker permissions,
                                                          RelationshipChecker relationships,
                                                          FgaSubjectResolver subjects, boolean enforce) {
        return new RelationshipResourceAccessCheckerImpl(permissions, relationships, subjects, enforce);
    }
}
