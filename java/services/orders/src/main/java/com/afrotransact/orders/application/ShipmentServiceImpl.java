package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.CourierProofPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionRefused;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShipmentServiceImpl implements ShipmentService {

    /** The delivery steps of an AfroTransact-delivered shipment (see {@link #requireDeliverySteps}). */
    private static final Set<ShipmentStatus> DESK_STEPS = EnumSet.of(ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED,
            ShipmentStatus.DELIVERY_FAILED);

    private final OrdersAccess access;
    private final OrderRepository orders;
    private final ShipmentTransitions transitions;
    private final CourierProofPolicy proofs;
    private final HandoverPolicy handover;
    private final OrderViews views;
    private final OrdersSettings settings;
    private final Clock clock;

    ShipmentServiceImpl(OrdersAccess access, OrderRepository orders, ShipmentTransitions transitions,
                        CourierProofPolicy proofs, HandoverPolicy handover, OrderViews views, OrdersSettings settings,
                        Clock clock) {
        this.access = access;
        this.orders = orders;
        this.transitions = transitions;
        this.proofs = proofs;
        this.handover = handover;
        this.views = views;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ShipmentActionResult transition(String shipmentId, String to, String reason, String handoverCode,
                                           String idempotencyKey) {
        ShipmentStatus target = status(to);
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId, target,
                (o, s) -> access.transitionActor(o, s), reason, TransitionSource.API, idempotencyKey, null, null,
                s -> {
                    requireDeliverySteps(s, target);
                    checkSellerShipDetails(s, target, reason);
                    checkPickupHandover(s, target, handoverCode, reason);
                }));
        return result(outcome);
    }

    /**
     * The statuses of an AfroTransact-delivered shipment that people at the console set with the delivery steps (the
     * Delivery module, delivery:manage): the same steps, proof and audit as the Delivery queue. The general transition
     * endpoint refuses them for people, so the proof and the audit cannot be skipped by calling it directly. Services
     * (fulfilment relay, carriers, couriers) are unaffected.
     */
    private void requireDeliverySteps(Shipment s, ShipmentStatus target) {
        if (!s.fulfilmentMode().staffDelivers() || s.status() == target || !access.humanStaff()) {
            return;
        }
        boolean deskStep = DESK_STEPS.contains(target)
                || target == ShipmentStatus.RETURNED && s.status() == ShipmentStatus.DELIVERY_FAILED;
        if (deskStep) {
            throw new ForbiddenActionException("orders.use-delivery-steps", "AfroTransact delivers this order: "
                    + "move it with the delivery steps (permission delivery:manage), not the general status change");
        }
    }

    @Override
    @Transactional
    public ShipmentActionResult override(String shipmentId, String to, String reason, String idempotencyKey) {
        ShipmentStatus target = status(to);
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3) {
            throw new OrdersRuleViolation("orders.override-reason-required",
                    "Say why you are overriding the seller's status (at least a few words)");
        }
        if (why.length() > 500) {
            throw new OrdersRuleViolation("orders.reason-too-long", "The reason can be up to 500 characters");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : "override:" + idempotencyKey.trim();
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId, target,
                (o, s) -> access.overrideActor(o, s), why, TransitionSource.API, key, null, null, s -> { }));
        return result(outcome);
    }

    /**
     * A seller who ships it themselves says how it left: a tracking number recorded on the shipment, or handover
     * details in the note (who took it, when, a courier's name or a local delivery). Nothing is invented for them.
     */
    private static void checkSellerShipDetails(Shipment s, ShipmentStatus target, String reason) {
        if (target != ShipmentStatus.PICKED_UP || s.deliveryMethod() != DeliveryMethod.SELLER_SHIPS
                || s.status() == ShipmentStatus.PICKED_UP) {
            return;
        }
        boolean tracked = s.trackingNumber() != null && !s.trackingNumber().isBlank();
        if (!tracked && (reason == null || reason.isBlank())) {
            throw new OrdersRuleViolation("orders.ship-details-required",
                    "Add a tracking number, or say how you sent it (for example who took it)");
        }
    }

    /**
     * Handing a pickup order over is checked against the customer's code, like a driver's delivery. The seller must
     * have it; staff, who can fix what the people on the spot could not, may confirm with a reason instead.
     */
    private void checkPickupHandover(Shipment s, ShipmentStatus target, String code, String reason) {
        if (target != ShipmentStatus.DELIVERED || s.deliveryMethod() != DeliveryMethod.PICKUP || !s.handoverRequired()
                || s.status() == ShipmentStatus.DELIVERED || handover.matches(s.id(), code, s.handoverCodeHash())) {
            return;
        }
        Actor actor = access.current();
        boolean staff = OrdersAccess.superAdmin(actor) || actor.type() == PrincipalType.STAFF
                || actor.type() == PrincipalType.SERVICE;
        if (staff && reason != null && !reason.isBlank()) {
            return;
        }
        if (code == null || code.isBlank()) {
            throw new OrdersRuleViolation("orders.handover-code-required", staff
                    ? "Enter the customer's 4-digit code, or say why you are confirming the handover without it"
                    : "Ask the customer for their 4-digit code");
        }
        throw new OrdersRuleViolation("orders.handover-code-invalid",
                "The handover code does not match; ask the customer for the 4-digit code");
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
                idempotencyKey, null, null, s -> cancellableByCustomer(order, s)));
        return views.of(outcome.order(), new Viewer(Viewer.Kind.CUSTOMER, null),
                orders.history(outcome.order().id()));
    }

    @Override
    @Transactional
    public ShipmentActionResult courierEvent(String shipmentId, CourierEventCommand cmd) {
        String courier = access.courier(cmd.driverId());
        if (cmd.eventId() == null || cmd.eventId().isBlank()) {
            throw new OrdersRuleViolation("orders.event-id-required", "Courier events need an event id");
        }
        ShipmentStatus target = status(cmd.status());
        String reason = cmd.proof() != null && cmd.proof().failureReason() != null ? cmd.proof().failureReason()
                : cmd.note();
        ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId, target,
                (o, s) -> new TransitionActor(ActorKind.COURIER, courier, null), reason,
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
        TransitionActor actor = access.transitionActor(order, shipment);
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
        access.briefReader();
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

    /** S272: a pre-order is cancelled by the customer (full refund incl. delivery fee) only until its window closes. */
    private void cancellableByCustomer(Order o, Shipment s) {
        if (s.preorderShipment() && !s.preorder().cancellableAt(Instant.now(clock))) {
            throw TransitionRefused.preorderClosed(s.preorder().closesAt());
        }
        if (!s.preorderShipment() && o.placedAt() != null) {
            Instant expiry = o.placedAt().plus(settings.cancellationWindow());
            if (Instant.now(clock).isAfter(expiry)) {
                throw TransitionRefused.cancelWindowExpired(o.placedAt());
            }
        }
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
        if (actor.type() == PrincipalType.DRIVER && !OrdersAccess.superAdmin(actor)
                || actor.type() == PrincipalType.SERVICE && !actor.can(OrdersPermissions.READ)) {
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
