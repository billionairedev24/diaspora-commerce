package com.afrotransact.platform.security.relationship;

import com.afrotransact.platform.security.Actor;

/**
 * Combined authorisation: the RBAC permission (exactly as {@code PermissionChecker}) AND, when
 * {@code at.security.fga.enabled=true}, the actor's relationship to the resource in OpenFGA.
 *
 * <p>With FGA disabled every method falls back to today's RBAC behaviour, so services can adopt this API before
 * OpenFGA is switched on in an environment. With FGA enabled the JWT {@code org} claim is no longer trusted for
 * ownership: membership comes from the relationship store.
 *
 * <p>Failures throw {@code ForbiddenActionException} (403, codes {@code auth.missing-permission},
 * {@code auth.wrong-org} or {@code auth.relationship-denied}) or {@link RelationshipCheckUnavailableException} (503).
 */
public interface ResourceAccessChecker {

    /**
     * Permission AND {@code relation} on {@code resource}, whose owner the service reads from its own database and
     * passes as a contextual tuple. RBAC fallback: {@code requireForOrg(permission, ownerOrgId)}.
     */
    Actor requireOwned(String permission, String relation, FgaObject resource, String ownerOrgId);

    /**
     * Permission AND {@code relation} on {@code organization:<orgId>} (org-level actions such as creating an offer).
     * RBAC fallback: {@code requireForOrg(permission, orgId)}.
     */
    Actor requireForOrg(String permission, String relation, String orgId);

    /**
     * Permission AND {@code relation} on {@code resource} using only stored tuples plus {@code contextualTuples}.
     * RBAC fallback: {@code require(permission)} only — prefer {@link #requireOwned} for org-owned resources.
     */
    Actor require(String permission, String relation, FgaObject resource, RelationshipTuple... contextualTuples);

    /** True when relationship checks run (FGA enabled), false in RBAC-only mode. */
    boolean relationshipsEnabled();
}
