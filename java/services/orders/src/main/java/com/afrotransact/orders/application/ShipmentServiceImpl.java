package com.afrotransact.orders.application;

import java.util.Locale;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.CourierProofPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShipmentServiceImpl implements ShipmentService {

    private final OrdersAccess access;
    private final OrderRepository orders;
    private final ShipmentTransitions transitions;
    private final CourierProofPolicy proofs;
    private final OrderViews views;

    ShipmentServiceImpl(OrdersAccess access, OrderRepository orders, ShipmentTransitions transitions,
                        CourierProofPolicy proofs, OrderViews views) {
        this.access = access;
        this.orders = orders;
        this.transitions = transitions;
        this.proofs = proofs;
        this.views = views;
    }

    @Override
    @Transactional
    public ShipmentActionResult transition(String shipmentId, String to, String reason, String idempotencyKey) {
        ShipmentStatus target = status(to);
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId, target,
                (o, s) -> access.transitionActor(s), reason, TransitionSource.API, idempotencyKey, null, null, null));
        return result(outcome);
    }

    @Override
    @Transactional
    public OrderView cancelByCustomer(String orderNumber, String shipmentId, String reason, String idempotencyKey) {
        Actor customer = access.customer();
        Order order = orders.findByNumber(orderNumber).orElseThrow(() -> OrdersAccess.notFound(orderNumber));
        access.ensureOwner(customer, order);
        order.shipment(shipmentId);
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId,
                ShipmentStatus.CANCELLED, (o, s) -> new TransitionActor(ActorKind.CUSTOMER, customer.subject(), null),
                reason == null || reason.isBlank() ? "Cancelled by the customer" : reason, TransitionSource.API,
                idempotencyKey, null, null, null));
        return views.of(outcome.order(), new Viewer(Viewer.Kind.CUSTOMER, null),
                orders.history(outcome.order().id()));
    }

    @Override
    @Transactional
    public ShipmentActionResult courierEvent(String shipmentId, CourierEventCommand cmd) {
        Actor driver = access.driver();
        if (cmd.eventId() == null || cmd.eventId().isBlank()) {
            throw new OrdersRuleViolation("orders.event-id-required", "Courier events need an event id");
        }
        ShipmentStatus target = status(cmd.status());
        String reason = cmd.proof() != null && cmd.proof().failureReason() != null ? cmd.proof().failureReason()
                : cmd.note();
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId, target,
                (o, s) -> new TransitionActor(ActorKind.COURIER, driver.subject(), null), reason,
                TransitionSource.DRIVER, "driver:" + cmd.eventId().trim(), cmd.occurredAt(), cmd.proof(),
                s -> proofs.check(s, target, cmd.proof())));
        return result(outcome);
    }

    @Override
    @Transactional
    public OrderView attachTracking(String shipmentId, String carrier, String trackingNumber) {
        if (carrier == null || carrier.isBlank() || trackingNumber == null || trackingNumber.isBlank()) {
            throw new OrdersRuleViolation("orders.tracking-required", "Carrier and tracking number are required");
        }
        Order order = lock(shipmentId);
        Shipment shipment = order.shipment(shipmentId);
        TransitionActor actor = access.transitionActor(shipment);
        if (actor.kind() == ActorKind.SELLER && shipment.fulfilmentType().afrotransactFulfilled()) {
            throw new OrdersRuleViolation("orders.not-seller-shipment", "AfroTransact ships this order");
        }
        Order updated = order.withShipment(shipment.withTracking(carrier.trim().toLowerCase(Locale.ROOT),
                trackingNumber.trim()));
        orders.update(updated);
        return views.of(updated, viewerFor(actor), orders.history(order.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryBrief deliveryBrief(String shipmentId) {
        access.driver();
        String orderId = orders.orderIdOfShipment(shipmentId).orElseThrow(() -> notFound(shipmentId));
        Order order = orders.findById(orderId).orElseThrow(() -> notFound(shipmentId));
        Shipment s = order.shipment(shipmentId);
        if (s.deliveryMethod() != DeliveryMethod.AFROTRANSACT_DELIVERY) {
            throw notFound(shipmentId);
        }
        return new DeliveryBrief(s.id(), order.number(), s.status().wire(), order.shipTo(), s.preferences(),
                s.perishable(), s.handoverRequired(), !s.perishable() && !s.preferences().handToCustomer(),
                s.lines().stream().mapToInt(OrderLine::quantity).sum(), s.sellerName(), s.nodeId());
    }

    private ShipmentActionResult result(ShipmentTransitions.Outcome outcome) {
        String kind = outcome.replayed() ? "replayed" : switch (outcome.kind()) {
            case ALLOW -> "applied";
            case UNCHANGED -> "unchanged";
            case IGNORE -> "ignored";
        };
        Viewer viewer = viewerFor(outcome.order());
        return new ShipmentActionResult(kind, outcome.note(), viewer == null ? null
                : views.of(outcome.order(), viewer, orders.history(outcome.order().id())));
    }

    /** Drivers and services get no order view back; others see what they may see. */
    private Viewer viewerFor(Order order) {
        Actor actor = access.current();
        if (actor.type() == PrincipalType.DRIVER && !OrdersAccess.superAdmin(actor)) {
            return null;
        }
        return access.viewerOf(order);
    }

    private static Viewer viewerFor(TransitionActor actor) {
        return actor.kind() == ActorKind.SELLER ? new Viewer(Viewer.Kind.SELLER, actor.orgId())
                : new Viewer(Viewer.Kind.STAFF, null);
    }

    private Order lock(String shipmentId) {
        String orderId = orders.orderIdOfShipment(shipmentId).orElseThrow(() -> notFound(shipmentId));
        return orders.lockById(orderId).orElseThrow(() -> notFound(shipmentId));
    }

    private static ShipmentStatus status(String value) {
        if (value == null || value.isBlank()) {
            throw new OrdersRuleViolation("orders.status-required", "Say which status to move to");
        }
        try {
            return ShipmentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrdersRuleViolation("orders.unknown-status", "Unknown shipment status " + value);
        }
    }

    private static NotFoundException notFound(String shipmentId) {
        return new NotFoundException("orders.shipment-not-found", "Shipment " + shipmentId + " not found");
    }
}
