package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/** Authorisation rules shared by the orders use cases (checked here, never only in a UI). */
@Component
class OrdersAccess {

    private final PermissionChecker permissions;
    private final ActorResolver actors;

    OrdersAccess(PermissionChecker permissions, ActorResolver actors) {
        this.permissions = permissions;
        this.actors = actors;
    }

    Actor current() {
        return actors.current();
    }

    /** The signed-in shopper (the JWT subject is the customer id). Super admins may act as one for drills. */
    Actor customer() {
        Actor actor = actors.current();
        if (actor.type() != PrincipalType.CUSTOMER && !superAdmin(actor)) {
            throw new ForbiddenActionException("orders.customer-only", "Only shoppers place and cancel their orders");
        }
        return actor;
    }

    /** AfroTransact Delivery driver with delivery:operate. */
    Actor driver() {
        Actor actor = permissions.require(OrdersPermissions.DELIVERY);
        if (actor.type() != PrincipalType.DRIVER && !superAdmin(actor)) {
            throw new ForbiddenActionException("orders.driver-only", "Courier events come from the driver app");
        }
        return actor;
    }

    /**
     * The courier of a driver-app event: the driver themself (delivery:operate), or the driver named by
     * fulfilment-service when it relays queued events (SERVICE principal with delivery:relay).
     */
    String courier(String relayedDriverId) {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(OrdersPermissions.RELAY)) {
            if (relayedDriverId == null || relayedDriverId.isBlank()) {
                throw new OrdersRuleViolation("orders.driver-id-required",
                        "Relayed courier events must name the driver (driverId)");
            }
            return relayedDriverId.trim();
        }
        return driver().subject();
    }

    /** Delivery briefs: drivers (delivery:operate) and dispatch (delivery:dispatch). */
    Actor briefReader() {
        Actor actor = actors.current();
        if (actor.can(OrdersPermissions.DISPATCH) || superAdmin(actor)) {
            return actor;
        }
        return driver();
    }

    /** Staff (or a service) with orders:read. */
    Actor staffReader() {
        Actor actor = permissions.require(OrdersPermissions.READ);
        if (!staff(actor)) {
            throw new ForbiddenActionException("orders.staff-only", "Order search is a staff view");
        }
        return actor;
    }

    /** The organisation a seller queue is for: the caller's own org, or the requested one for staff. */
    String sellerOrg(String requested) {
        Actor actor = permissions.require(OrdersPermissions.READ);
        if (staff(actor)) {
            if (requested == null || requested.isBlank()) {
                throw new ForbiddenActionException("orders.org-required", "Staff must name the seller organisation");
            }
            return requested.trim();
        }
        return actor.orgId().orElseThrow(() -> new ForbiddenActionException("orders.no-org",
                "Only seller organisations have a shipment queue"));
    }

    /** Decides what the current actor may see of an order; 404 (not 403) when nothing, to avoid leaking it. */
    Viewer viewerOf(Order order) {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.CUSTOMER) {
            if (actor.subject().equals(order.customerId())) {
                return new Viewer(Viewer.Kind.CUSTOMER, null);
            }
            throw notFound(order.number());
        }
        if (staff(actor)) {
            permissions.require(OrdersPermissions.READ);
            return new Viewer(Viewer.Kind.STAFF, null);
        }
        if (actor.can(OrdersPermissions.READ) && actor.orgId().isPresent()
                && order.status() != OrderStatus.PENDING_PAYMENT) {
            String org = actor.orgId().get();
            if (order.shipments().stream().anyMatch(s -> s.sellerOrgId().equals(org))) {
                return new Viewer(Viewer.Kind.SELLER, org);
            }
        }
        throw notFound(order.number());
    }

    /**
     * Maps the caller of {@code POST /v1/shipments/{id}/transitions} to a state-machine actor. Sellers act only on
     * their own organisation's shipments (404 otherwise); staff need orders:manage for AfroTransact-fulfilled
     * shipments (or fulfilment:operate as fulfilment ops); services with orders:update-status act as fulfilment ops.
     */
    TransitionActor transitionActor(Shipment shipment) {
        Actor actor = actors.current();
        if (superAdmin(actor)) {
            return new TransitionActor(ActorKind.PLATFORM_ADMIN, actor.subject(), null);
        }
        return switch (actor.type()) {
            case SELLER_MEMBER, PROVIDER_MEMBER -> {
                if (!actor.orgId().map(shipment.sellerOrgId()::equals).orElse(false)) {
                    throw new NotFoundException("orders.shipment-not-found", "Shipment not found");
                }
                permissions.require(OrdersPermissions.UPDATE_STATUS);
                yield new TransitionActor(ActorKind.SELLER, actor.subject(), shipment.sellerOrgId());
            }
            case STAFF -> staffActor(actor, shipment);
            case SERVICE -> {
                if (!actor.can(OrdersPermissions.UPDATE_STATUS) && !actor.can(OrdersPermissions.MANAGE)) {
                    throw new ForbiddenActionException("auth.missing-permission",
                            "Missing permission " + OrdersPermissions.UPDATE_STATUS);
                }
                yield new TransitionActor(ActorKind.FULFILMENT_OPS, actor.subject(), null);
            }
            case DRIVER -> throw new ForbiddenActionException("orders.use-courier-events",
                    "Drivers report progress with POST /v1/shipments/{id}/courier-events");
            case CUSTOMER -> throw new ForbiddenActionException("orders.use-cancel",
                    "Customers cancel with POST /v1/orders/{number}/shipments/{id}/cancel");
            default -> throw new ForbiddenActionException("orders.not-allowed", actor.type() + " cannot change "
                    + "shipment statuses");
        };
    }

    private static TransitionActor staffActor(Actor actor, Shipment shipment) {
        if (actor.can(OrdersPermissions.MANAGE)) {
            return new TransitionActor(ActorKind.PLATFORM_ADMIN, actor.subject(), null);
        }
        if (actor.can(OrdersPermissions.FULFILMENT)) {
            return new TransitionActor(ActorKind.FULFILMENT_OPS, actor.subject(), null);
        }
        if (actor.can(OrdersPermissions.UPDATE_STATUS) && !shipment.fulfilmentType().afrotransactFulfilled()) {
            return new TransitionActor(ActorKind.PLATFORM_ADMIN, actor.subject(), null);
        }
        throw new ForbiddenActionException("auth.missing-permission", "Missing permission "
                + OrdersPermissions.MANAGE);
    }

    /** A customer may only touch their own order; anything else is reported as not found. */
    void ensureOwner(Actor customer, Order order) {
        if (!superAdmin(customer) && !customer.subject().equals(order.customerId())) {
            throw notFound(order.number());
        }
    }

    static boolean staff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }

    static boolean superAdmin(Actor actor) {
        return actor.permissions().contains("*");
    }

    static NotFoundException notFound(String number) {
        return new NotFoundException("orders.order-not-found", "Order " + number + " not found");
    }
}
