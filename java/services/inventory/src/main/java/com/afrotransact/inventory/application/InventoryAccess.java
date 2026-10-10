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

    /** Requires {@code inventory:read}; returns the owner organisation the actor is limited to (empty for staff). */
    Optional<String> readScope() {
        Actor actor = permissions.require(InventoryPermissions.READ);
        if (isStaff(actor)) {
            return Optional.empty();
        }
        return Optional.of(actor.orgId().orElseThrow(() -> new ForbiddenActionException("inventory.no-org",
                "Only staff or seller organisations can view inventory")));
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
