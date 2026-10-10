package com.afrotransact.payments.application;

import java.util.UUID;

import com.afrotransact.payments.domain.Requester;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/**
 * Authorisation rules shared by the payments use cases. With OpenFGA enabled (S248) the organisation-scoped views and
 * actions also need the relationship ({@link PaymentsRelations}): payouts, balances and statements of an organisation
 * only for its owner / admin / manager / finance members and Finance staff of its market, whatever the token's
 * {@code org} claim; cross-organisation staff views only for platform-wide staff and first-party services.
 */
@Component
class PaymentsAccess {

    private final PermissionChecker permissions;
    private final ResourceAccessChecker relationships;
    private final ActorResolver actors;

    PaymentsAccess(PermissionChecker permissions, ResourceAccessChecker relationships, ActorResolver actors) {
        this.permissions = permissions;
        this.relationships = relationships;
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

    /** Staff with payments:read, or a calling service; platform-wide (the ledger and splits span organisations). */
    Actor reader() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(PaymentsPermissions.SERVICE)) {
            return relationships.require(PaymentsPermissions.SERVICE, PaymentsRelations.PLATFORM_VIEW_ALL,
                    FgaObject.PLATFORM);
        }
        Actor reader = permissions.require(PaymentsPermissions.READ);
        if (!isStaff(reader)) {
            throw new ForbiddenActionException("payments.staff-only", "Payment splits and the ledger are staff views");
        }
        return relationships.require(PaymentsPermissions.READ, PaymentsRelations.PLATFORM_VIEW_ALL,
                FgaObject.PLATFORM);
    }

    /** Staff with payments:read on one organisation's ledger accounts: Finance staff of its market qualify. */
    Actor orgReader(String orgId) {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(PaymentsPermissions.SERVICE)) {
            return relationships.requireForOrg(PaymentsPermissions.SERVICE, PaymentsRelations.ORG_VIEW_PAYOUTS, orgId);
        }
        Actor reader = permissions.require(PaymentsPermissions.READ);
        if (!isStaff(reader)) {
            throw new ForbiddenActionException("payments.staff-only", "Payment splits and the ledger are staff views");
        }
        return relationships.requireForOrg(PaymentsPermissions.READ, PaymentsRelations.ORG_VIEW_PAYOUTS, orgId);
    }

    /** Fee schedules: staff readers, calling services, or service clients holding only payments:fees.read. */
    Actor feeReader() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(PaymentsPermissions.FEES_READ)) {
            return actor;
        }
        return reader();
    }

    /**
     * AfroTransact Delivery quotes (S255): orders-service at checkout (payments:service), services holding
     * payments:fees.read, and staff readers (fee editor preview).
     */
    Actor deliveryQuoter() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE
                && (actor.can(PaymentsPermissions.SERVICE) || actor.can(PaymentsPermissions.FEES_READ))) {
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

    /** AfroTransact Retail supplier payments (S226): platform-wide Finance with payouts:approve. */
    Actor supplierPaymentApprover() {
        approver();
        return relationships.require(PaymentsPermissions.PAYOUTS_APPROVE, PaymentsRelations.PLATFORM_VIEW_ALL,
                FgaObject.PLATFORM);
    }

    /** The payout queue: one organisation's (its market's Finance staff) or every organisation's (platform-wide). */
    Actor queueReader(String orgId) {
        approver();
        if (orgId == null || orgId.isBlank()) {
            return relationships.require(PaymentsPermissions.PAYOUTS_APPROVE, PaymentsRelations.PLATFORM_VIEW_ALL,
                    FgaObject.PLATFORM);
        }
        return relationships.requireForOrg(PaymentsPermissions.PAYOUTS_APPROVE, PaymentsRelations.ORG_VIEW_PAYOUTS,
                orgId.trim());
    }

    /** Approve, hold or release one payout: Finance staff whose grant covers the payout's organisation. */
    Actor payoutApprover(UUID payoutId, String orgId) {
        approver();
        return relationships.requireOwned(PaymentsPermissions.PAYOUTS_APPROVE, PaymentsRelations.PAYOUT_CAN_VIEW,
                PaymentsRelations.payout(payoutId), orgId);
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

    /**
     * Payout balance, statement, label charges, provider fees: members of the organisation, or staff who read payments
     * or approve payouts. With relationships on, members also need payouts:read and {@code can_view_payouts} (owner,
     * admin, manager, finance), staff a Finance grant covering the organisation's market.
     */
    Actor balanceViewer(String orgId) {
        Actor actor = actors.current();
        if (isStaff(actor)) {
            if (!actor.can(PaymentsPermissions.READ) && !actor.can(PaymentsPermissions.PAYOUTS_APPROVE)) {
                throw new ForbiddenActionException("auth.missing-permission", "Missing permission "
                        + PaymentsPermissions.READ);
            }
            if (relationships.relationshipsEnabled()) {
                relationships.requireForOrg(actor.can(PaymentsPermissions.READ) ? PaymentsPermissions.READ
                        : PaymentsPermissions.PAYOUTS_APPROVE, PaymentsRelations.ORG_VIEW_PAYOUTS, orgId);
            }
            return actor;
        }
        if (!actor.orgId().map(orgId::equals).orElse(false)) {
            throw new ForbiddenActionException("auth.wrong-org", "Actor does not belong to organisation " + orgId);
        }
        if (relationships.relationshipsEnabled()) {
            relationships.requireForOrg(PaymentsPermissions.PAYOUTS_READ, PaymentsRelations.ORG_VIEW_PAYOUTS, orgId);
        }
        return actor;
    }

    /**
     * payouts:request for the organisation; the payout policy then checks the owner / finance role. Relationship:
     * {@code can_request_payouts} for members (finance; owners inherit it), {@code can_view_payouts} for staff acting
     * on behalf of an organisation of their market.
     */
    Requester payoutRequester(String orgId) {
        Actor actor = permissions.requireForOrg(PaymentsPermissions.PAYOUTS_REQUEST, orgId);
        relationships.requireForOrg(PaymentsPermissions.PAYOUTS_REQUEST, isStaff(actor)
                ? PaymentsRelations.ORG_VIEW_PAYOUTS : PaymentsRelations.ORG_REQUEST_PAYOUTS, orgId);
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
