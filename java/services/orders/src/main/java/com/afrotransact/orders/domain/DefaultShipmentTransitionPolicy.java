package com.afrotransact.orders.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Architecture §17c "Who can do what", enforced here rather than in any UI:
 * <ul>
 *   <li><b>Seller-fulfilled</b>: only the seller confirms, packs and marks ready (and hands a pickup order to the
 *       customer at its own shop). Carriers (webhooks) and AfroTransact Delivery couriers move the transit steps.
 *       Platform admins may confirm delivery, record a failure, process returns or cancel — never "packed" (nor
 *       confirmed / ready).</li>
 *   <li><b>Fulfilled by AfroTransact / AfroTransact Retail</b>: admins and fulfilment ops own every step; carriers
 *       and couriers still report the transit steps.</li>
 *   <li><b>Customers</b> may cancel a shipment until it is packed.</li>
 *   <li><b>Carriers and couriers</b> only move forward: an event for an earlier step than the current one is ignored,
 *       never applied (a failed delivery may be re-attempted).</li>
 * </ul>
 */
public final class DefaultShipmentTransitionPolicy implements ShipmentTransitionPolicy {

    private static final Set<ShipmentStatus> TRANSIT_TARGETS = EnumSet.of(ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED,
            ShipmentStatus.DELIVERY_FAILED, ShipmentStatus.RETURNED);
    private static final Set<ShipmentStatus> ON_THE_WAY = EnumSet.of(ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_FAILED);
    private static final Set<ShipmentStatus> BEFORE_PICKUP = EnumSet.of(ShipmentStatus.PLACED,
            ShipmentStatus.CONFIRMED, ShipmentStatus.PACKED, ShipmentStatus.READY);

    @Override
    public TransitionDecision decide(Shipment shipment, ShipmentStatus to, ActorKind actor) {
        if (actor == ActorKind.CARRIER || actor == ActorKind.COURIER) {
            return transit(shipment, to, actor);
        }
        Map<ShipmentStatus, Set<ShipmentStatus>> rules = rules(actor, shipment.fulfilmentType(),
                shipment.deliveryMethod());
        Set<ShipmentStatus> from = rules.get(to);
        if (from == null) {
            throw TransitionRefused.forbidden(actor, to, shipment.fulfilmentType());
        }
        if (shipment.status() == to) {
            return TransitionDecision.unchanged();
        }
        if (!from.contains(shipment.status())) {
            if (actor == ActorKind.CUSTOMER && to == ShipmentStatus.CANCELLED) {
                throw TransitionRefused.cancelWindowClosed(shipment.status());
            }
            throw TransitionRefused.invalid(shipment.status(), to);
        }
        return TransitionDecision.allow();
    }

    @Override
    public Set<ShipmentStatus> nextFor(Shipment shipment, ActorKind actor) {
        Set<ShipmentStatus> out = EnumSet.noneOf(ShipmentStatus.class);
        for (ShipmentStatus to : ShipmentStatus.values()) {
            try {
                if (decide(shipment, to, actor).kind() == TransitionDecision.Kind.ALLOW) {
                    out.add(to);
                }
            } catch (TransitionRefused refused) {
                // not available to this actor
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** Carrier webhooks and AfroTransact Delivery couriers: transit steps only, forward only. */
    private static TransitionDecision transit(Shipment s, ShipmentStatus to, ActorKind actor) {
        if (!TRANSIT_TARGETS.contains(to)) {
            throw TransitionRefused.forbidden(actor, to, s.fulfilmentType());
        }
        DeliveryMethod expected = actor == ActorKind.CARRIER ? DeliveryMethod.CARRIER
                : DeliveryMethod.AFROTRANSACT_DELIVERY;
        if (s.deliveryMethod() != expected) {
            throw TransitionRefused.forbidden((actor == ActorKind.CARRIER ? "Carrier events" : "Courier events")
                    + " only apply to " + expected.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " shipments");
        }
        ShipmentStatus from = s.status();
        if (from.terminal()) {
            return TransitionDecision.ignore("shipment is already " + from.wire());
        }
        if (from == ShipmentStatus.PENDING_PAYMENT || from == ShipmentStatus.PLACED) {
            throw TransitionRefused.invalid(from, to);
        }
        if (from == to) {
            return TransitionDecision.unchanged();
        }
        boolean reattempt = from == ShipmentStatus.DELIVERY_FAILED
                && (to == ShipmentStatus.IN_TRANSIT || to == ShipmentStatus.OUT_FOR_DELIVERY);
        if (!reattempt && to.rank() <= from.rank()) {
            return TransitionDecision.ignore("out-of-order event: " + to.wire() + " is behind " + from.wire());
        }
        return TransitionDecision.allow();
    }

    private static Map<ShipmentStatus, Set<ShipmentStatus>> rules(ActorKind actor, FulfilmentType type,
                                                                  DeliveryMethod method) {
        return switch (actor) {
            case CUSTOMER -> Map.of(ShipmentStatus.CANCELLED, EnumSet.of(ShipmentStatus.PLACED,
                    ShipmentStatus.CONFIRMED));
            case SYSTEM -> Map.of(ShipmentStatus.PLACED, EnumSet.of(ShipmentStatus.PENDING_PAYMENT),
                    ShipmentStatus.CANCELLED, EnumSet.of(ShipmentStatus.PENDING_PAYMENT));
            case SELLER -> type == FulfilmentType.SELLER_FULFILLED ? sellerSteps(method) : Map.of();
            case PLATFORM_ADMIN -> type == FulfilmentType.SELLER_FULFILLED ? oversight(method) : everyStep(method);
            case FULFILMENT_OPS -> type == FulfilmentType.SELLER_FULFILLED ? Map.of() : everyStep(method);
            case CARRIER, COURIER -> Map.of();
        };
    }

    /** Seller-fulfilled: the seller prepares the parcel (and hands pickup orders over at its own shop). */
    private static Map<ShipmentStatus, Set<ShipmentStatus>> sellerSteps(DeliveryMethod method) {
        Map<ShipmentStatus, Set<ShipmentStatus>> m = new EnumMap<>(ShipmentStatus.class);
        m.put(ShipmentStatus.CONFIRMED, EnumSet.of(ShipmentStatus.PLACED));
        m.put(ShipmentStatus.PACKED, EnumSet.of(ShipmentStatus.CONFIRMED));
        m.put(ShipmentStatus.READY, EnumSet.of(ShipmentStatus.PACKED));
        if (method == DeliveryMethod.PICKUP) {
            m.put(ShipmentStatus.DELIVERED, EnumSet.of(ShipmentStatus.READY));
        }
        return m;
    }

    /** Platform admins on seller-fulfilled shipments: confirm delivery, record failure, returns, cancel. */
    private static Map<ShipmentStatus, Set<ShipmentStatus>> oversight(DeliveryMethod method) {
        Map<ShipmentStatus, Set<ShipmentStatus>> m = new EnumMap<>(ShipmentStatus.class);
        Set<ShipmentStatus> deliverFrom = EnumSet.copyOf(ON_THE_WAY);
        if (method == DeliveryMethod.PICKUP) {
            deliverFrom.add(ShipmentStatus.READY);
        }
        m.put(ShipmentStatus.DELIVERED, deliverFrom);
        m.put(ShipmentStatus.DELIVERY_FAILED, EnumSet.of(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT,
                ShipmentStatus.OUT_FOR_DELIVERY));
        m.put(ShipmentStatus.RETURNED, EnumSet.of(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY,
                ShipmentStatus.DELIVERY_FAILED, ShipmentStatus.DELIVERED));
        m.put(ShipmentStatus.CANCELLED, EnumSet.copyOf(BEFORE_PICKUP));
        return m;
    }

    /** AfroTransact-fulfilled: admins and fulfilment ops own every step. */
    private static Map<ShipmentStatus, Set<ShipmentStatus>> everyStep(DeliveryMethod method) {
        Map<ShipmentStatus, Set<ShipmentStatus>> m = oversight(method);
        m.put(ShipmentStatus.CONFIRMED, EnumSet.of(ShipmentStatus.PLACED));
        m.put(ShipmentStatus.PACKED, EnumSet.of(ShipmentStatus.CONFIRMED));
        m.put(ShipmentStatus.READY, EnumSet.of(ShipmentStatus.PACKED));
        m.put(ShipmentStatus.PICKED_UP, EnumSet.of(ShipmentStatus.READY));
        m.put(ShipmentStatus.IN_TRANSIT, EnumSet.of(ShipmentStatus.PICKED_UP, ShipmentStatus.DELIVERY_FAILED));
        m.put(ShipmentStatus.OUT_FOR_DELIVERY, EnumSet.of(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT,
                ShipmentStatus.DELIVERY_FAILED));
        return m;
    }
}
