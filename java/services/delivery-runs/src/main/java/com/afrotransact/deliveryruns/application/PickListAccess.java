package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/**
 * Who may read a seller's pick lists (S264): the seller's own organisation members (the same {@code orders:read}
 * permission and organisation relationship that open the seller's order queue; with OpenFGA on, membership comes from
 * the relationship store, not the token's {@code org} claim), and staff or services with {@code delivery-runs:read}
 * naming the organisation.
 */
@Component
class PickListAccess {

    static final String SELLER_PERMISSION = "orders:read";
    static final String ORG_VIEW = "can_view";

    private final ActorResolver actors;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker relationships;

    PickListAccess(ActorResolver actors, PermissionChecker permissions, ResourceAccessChecker relationships) {
        this.actors = actors;
        this.permissions = permissions;
        this.relationships = relationships;
    }

    /** The organisation whose pick lists the caller may read ({@code requested} is required for staff). */
    String sellerOrg(String requested) {
        Actor actor = actors.current();
        String asked = requested == null || requested.isBlank() ? null : requested.trim();
        if (actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE) {
            permissions.require(DeliveryRunsPermissions.READ);
            if (asked == null) {
                throw new RuleViolation("delivery-runs.org-required", "Name the seller organisation (orgId)");
            }
            return asked;
        }
        String org = actor.orgId().orElseThrow(() -> new ForbiddenActionException("delivery-runs.no-org",
                "Only seller organisations have run pick lists"));
        if (asked != null && !asked.equals(org)) {
            throw new ForbiddenActionException("auth.wrong-org", "Not your organisation");
        }
        relationships.requireForOrg(SELLER_PERMISSION, ORG_VIEW, org);
        return org;
    }

    /** Staff and services reading every seller's lists of a run. */
    void operator() {
        permissions.require(DeliveryRunsPermissions.READ);
    }
}
