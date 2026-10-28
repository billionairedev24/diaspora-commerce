package com.afrotransact.inventory.application;

import java.util.Optional;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/** Authorisation rules shared by the inventory use cases. */
@Component
class InventoryAccess {

    private final PermissionChecker permissions;
    private final ActorResolver actors;

    InventoryAccess(PermissionChecker permissions, ActorResolver actors) {
        this.permissions = permissions;
        this.actors = actors;
    }

    Actor require(String permission) {
        return permissions.require(permission);
    }

    /**
     * Reserve / commit / release are machine-to-machine calls from the orders service: the caller must be a SERVICE
     * principal with {@code inventory:reserve}. Super admins ({@code *}) may call them for the flash-sale drill.
     */
    Actor reserver() {
        Actor actor = permissions.require(InventoryPermissions.RESERVE);
        if (actor.type() != PrincipalType.SERVICE && !actor.permissions().contains("*")) {
            throw new ForbiddenActionException("inventory.service-only",
                    "Reservations are made by the orders service, not by " + actor.type());
        }
        return actor;
    }

    /**
     * Returns after inspection: staff with {@code inventory:adjust}, or a SERVICE principal (tax-returns-service)
     * with {@code inventory:returns}, which lets the returns service post stock without a staff-wide key.
     */
    Actor returner() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(InventoryPermissions.RETURNS)) {
            return actor;
        }
        return permissions.require(InventoryPermissions.ADJUST);
    }

    /** Readers of reservations: the orders service, or staff with {@code inventory:read}. */
    Actor reservationReader() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(InventoryPermissions.RESERVE)) {
            return actor;
        }
        Actor reader = permissions.require(InventoryPermissions.READ);
        if (!isStaff(reader)) {
            throw new ForbiddenActionException("inventory.staff-only", "Reservations are visible to staff only");
        }
        return reader;
    }

    /** Staff with {@code inventory:read}: figures that sellers never see (exchange rates, costs). */
    Actor staffReader() {
        Actor reader = permissions.require(InventoryPermissions.READ);
        if (!isStaff(reader)) {
            throw new ForbiddenActionException("inventory.staff-only", "Exchange rates are visible to staff only");
        }
        return reader;
    }

    /** Requires {@code inventory:read}; returns the owner organisation the actor is limited to (empty for staff). */
    Optional<String> readScope() {
        Actor actor = permissions.require(InventoryPermissions.READ);
        if (isStaff(actor)) {
            return Optional.empty();
        }
        return Optional.of(actor.orgId().orElseThrow(() -> new ForbiddenActionException("inventory.no-org",
                "Only staff or seller organisations can view inventory")));
    }

    /**
     * FBA inbound plans (S257): {@code inventory:inbound}. Returns the organisation the caller acts for: their own
     * org for sellers (whatever they asked for), the requested org for staff.
     */
    InboundActor inboundWriter(String requestedOrg) {
        Actor actor = permissions.require(InventoryPermissions.INBOUND);
        if (isStaff(actor)) {
            return new InboundActor(actor, requestedOrg == null || requestedOrg.isBlank() ? null
                    : requestedOrg.trim());
        }
        return new InboundActor(actor, actor.orgId().orElseThrow(() -> new ForbiddenActionException(
                "inventory.no-org", "Only seller organisations send stock to AfroTransact")));
    }

    /**
     * Who may see inbound shipments: sellers with {@code inventory:inbound} or {@code inventory:read} (their own
     * org), staff with either or {@code inventory:receive} (all). Empty scope = all organisations.
     */
    Optional<String> inboundReadScope() {
        Actor actor = actors.current();
        if (!actor.can(InventoryPermissions.INBOUND) && !actor.can(InventoryPermissions.READ)
                && !actor.can(InventoryPermissions.RECEIVE)) {
            permissions.require(InventoryPermissions.INBOUND);
        }
        if (isStaff(actor)) {
            return Optional.empty();
        }
        return Optional.of(actor.orgId().orElseThrow(() -> new ForbiddenActionException("inventory.no-org",
                "Only staff or seller organisations can view inbound shipments")));
    }

    /** Cancelling a shipment: the seller ({@code inventory:inbound}) or FC staff ({@code inventory:receive}). */
    Actor actorForCancel() {
        Actor actor = actors.current();
        if (isStaff(actor) && actor.can(InventoryPermissions.RECEIVE)) {
            return actor;
        }
        return permissions.require(InventoryPermissions.INBOUND);
    }

    /** Staff act for any organisation; others only for their own. */
    boolean mayActFor(Actor actor, String ownerOrg) {
        return isStaff(actor) || actor.orgId().map(ownerOrg::equals).orElse(false);
    }

    String currentSubject() {
        return actors.current().subject();
    }

    /** The caller of an inbound write and the organisation it acts for (null: staff without one). */
    record InboundActor(Actor actor, String orgId) {
    }

    /** The owner filter to apply: the caller's own org for sellers (ignoring what they asked for), else the request. */
    String ownerFilter(String requested) {
        return readScope().orElse(requested);
    }

    /** Throws 404 (not 403, to avoid leaking existence) when a seller looks at another organisation's stock. */
    void ensureVisible(Optional<String> scope, String ownerOrg, String what) {
        if (scope.isPresent() && !scope.get().equals(ownerOrg)) {
            throw new NotFoundException("inventory.not-found", what + " not found");
        }
    }

    static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }
}
