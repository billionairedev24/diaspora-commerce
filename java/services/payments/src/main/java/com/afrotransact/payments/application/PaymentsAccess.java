package com.afrotransact.payments.application;

import com.afrotransact.payments.domain.Requester;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** Authorisation rules shared by the payments use cases. */
@Component
class PaymentsAccess {

    private final PermissionChecker permissions;
    private final ActorResolver actors;

    PaymentsAccess(PermissionChecker permissions, ActorResolver actors) {
        this.permissions = permissions;
        this.actors = actors;
    }

    /** Intents, captures, refunds and delivery notices come from other services (SERVICE principals) only. */
    Actor service() {
        Actor actor = permissions.require(PaymentsPermissions.SERVICE);
        if (actor.type() != PrincipalType.SERVICE && !actor.permissions().contains("*")) {
            throw new ForbiddenActionException("payments.service-only",
                    "Payments are created and captured by the orders service, not by " + actor.type());
        }
        return actor;
    }

    /** Staff with payments:read, or a calling service. */
    Actor reader() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(PaymentsPermissions.SERVICE)) {
            return actor;
        }
        Actor reader = permissions.require(PaymentsPermissions.READ);
        if (!isStaff(reader)) {
            throw new ForbiddenActionException("payments.staff-only", "Payment splits and the ledger are staff views");
        }
        return reader;
    }

    /** Fee schedules: staff readers, calling services, or service clients holding only payments:fees.read. */
    Actor feeReader() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(PaymentsPermissions.FEES_READ)) {
            return actor;
        }
        return reader();
    }

    Actor configure() {
        Actor actor = permissions.require(PaymentsPermissions.CONFIGURE);
        if (!isStaff(actor)) {
            throw new ForbiddenActionException("payments.staff-only", "Only Super Admin and Finance configure fees");
        }
        return actor;
    }

    /** Connected accounts are registered by the seller service or by Finance. */
    Actor accountRegistrar() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(PaymentsPermissions.SERVICE)) {
            return actor;
        }
        return configure();
    }

    Actor approver() {
        Actor actor = permissions.require(PaymentsPermissions.PAYOUTS_APPROVE);
        if (!isStaff(actor)) {
            throw new ForbiddenActionException("payments.staff-only", "Payout approval is a Finance action");
        }
        return actor;
    }

    /** The organisation the caller acts for: its own for org members, the requested one for staff. */
    String orgOf(String requested) {
        Actor actor = actors.current();
        if (isStaff(actor)) {
            if (requested == null || requested.isBlank()) {
                throw new ForbiddenActionException("payments.org-required", "Staff must name the organisation");
            }
            return requested.trim();
        }
        return actor.orgId().orElseThrow(() -> new ForbiddenActionException("payments.no-org",
                "Only seller or provider organisations have payouts"));
    }

    /** Payout balance: any member of the organisation, or staff who read payments or approve payouts. */
    Actor balanceViewer(String orgId) {
        Actor actor = actors.current();
        if (isStaff(actor)) {
            if (!actor.can(PaymentsPermissions.READ) && !actor.can(PaymentsPermissions.PAYOUTS_APPROVE)) {
                throw new ForbiddenActionException("auth.missing-permission", "Missing permission "
                        + PaymentsPermissions.READ);
            }
            return actor;
        }
        if (!actor.orgId().map(orgId::equals).orElse(false)) {
            throw new ForbiddenActionException("auth.wrong-org", "Actor does not belong to organisation " + orgId);
        }
        return actor;
    }

    /** payouts:request for the organisation; the payout policy then checks the owner / finance role. */
    Requester payoutRequester(String orgId) {
        Actor actor = permissions.requireForOrg(PaymentsPermissions.PAYOUTS_REQUEST, orgId);
        return new Requester(actor.subject(), actor.roles(), isStaff(actor));
    }

    /** The signed-in shopper (gateway-bff relays the customer's token); the JWT subject is the customer id. */
    Actor customer() {
        Actor actor = actors.current();
        if (actor.type() != PrincipalType.CUSTOMER && !actor.permissions().contains("*")) {
            throw new ForbiddenActionException("payments.customer-only", "Saved payment methods belong to shoppers");
        }
        return actor;
    }

    Actor current() {
        return actors.current();
    }

    static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }
}
