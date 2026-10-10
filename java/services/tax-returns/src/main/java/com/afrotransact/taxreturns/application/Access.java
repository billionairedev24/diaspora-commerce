package com.afrotransact.taxreturns.application;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** Actor checks shared by the use cases: customers act on their own orders, sellers on their organisation's. */
@Component
class Access {

    private final ActorResolver actors;
    private final PermissionChecker permissions;

    Access(ActorResolver actors, PermissionChecker permissions) {
        this.actors = actors;
        this.permissions = permissions;
    }

    Actor current() {
        return actors.current();
    }

    /** The order's customer, or staff with returns:manage acting for them. */
    Actor customerOrStaff(String customerId) {
        Actor a = actors.current();
        if (a.type() == PrincipalType.CUSTOMER) {
            if (!a.subject().equals(customerId)) {
                throw new ForbiddenActionException("returns.not-your-order", "This order belongs to someone else");
            }
            return a;
        }
        return staff(TaxReturnsPermissions.RETURNS_MANAGE);
    }

    /** The customer, the seller organisation (returns:manage) or staff (returns:manage). */
    Actor viewer(String customerId, String sellerOrgId) {
        Actor a = actors.current();
        if (a.type() == PrincipalType.CUSTOMER) {
            return customerOrStaff(customerId);
        }
        return permissions.requireForOrg(TaxReturnsPermissions.RETURNS_MANAGE, sellerOrgId);
    }

    /** Members of {@code orgId} or staff with {@code permission}. */
    Actor orgOrStaff(String permission, String orgId) {
        return permissions.requireForOrg(permission, orgId);
    }

    /** Staff or a service principal with {@code permission}. */
    Actor staff(String permission) {
        Actor a = permissions.require(permission);
        if (a.type() != PrincipalType.STAFF && a.type() != PrincipalType.SERVICE) {
            throw new ForbiddenActionException("returns.staff-only", "Only AfroTransact staff can do this");
        }
        return a;
    }

    boolean isStaff(Actor a) {
        return a.type() == PrincipalType.STAFF || a.type() == PrincipalType.SERVICE;
    }

    static String describe(Actor a) {
        return a.type().name() + ":" + a.subject();
    }
}
