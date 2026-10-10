package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionDecision;
import com.afrotransact.orders.domain.TransitionRefused;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CarrierEventServiceImpl implements CarrierEventService {

    static final String CONSUMER = "orders.carrier-events";

    private static final Logger LOG = LoggerFactory.getLogger(CarrierEventServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final OrderRepository orders;
    private final ShipmentTransitions transitions;

    CarrierEventServiceImpl(InboxDeduplicator inbox, OrderRepository orders, ShipmentTransitions transitions) {
        this.inbox = inbox;
        this.orders = orders;
        this.transitions = transitions;
    }

    @Override
    @Transactional
    public Result apply(UUID eventId, Scan scan) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return Result.DUPLICATE;
        }
        ShipmentStatus to = map(scan.state());
        if (to == null) {
            return Result.IGNORED;
        }
        Optional<String> shipmentId = match(scan);
        if (shipmentId.isEmpty()) {
            LOG.info("Carrier event {} {} {} matches no shipment", scan.carrier(), scan.trackingNumber(),
                    scan.state());
            return Result.UNMATCHED;
        }
        String reason = scan.description() == null ? scan.state()
                : scan.description() + (scan.location() == null ? "" : " — " + scan.location());
        try {
            ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(shipmentId.get(),
                    to, (o, s) -> new TransitionActor(ActorKind.CARRIER, scan.carrier(), null), reason,
                    TransitionSource.CARRIER, scan.carrierEventId() == null ? eventId.toString()
                    : scan.carrier() + ":" + scan.carrierEventId(), scan.occurredAt(), null, null));
            if (outcome.replayed()) {
                return Result.DUPLICATE;
            }
            if (outcome.kind() == TransitionDecision.Kind.IGNORE) {
                LOG.debug("Carrier event for shipment {} ignored: {}", shipmentId.get(), outcome.note());
                return Result.IGNORED;
            }
            return outcome.kind() == TransitionDecision.Kind.UNCHANGED ? Result.UNCHANGED : Result.APPLIED;
        } catch (TransitionRefused refused) {
            LOG.warn("Carrier event for shipment {} refused: {}", shipmentId.get(), refused.getMessage());
            return Result.REFUSED;
        }
    }

    @Override
    @Transactional
    public Result pickupReady(UUID eventId, PickupReady notice) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return Result.DUPLICATE;
        }
        Optional<Order> order = orderOf(notice.orderRef());
        if (order.isEmpty()) {
            LOG.info("Pickup-ready notice for {} matches no order", notice.orderRef());
            return Result.UNMATCHED;
        }
        if (notice.customerRef() != null && !notice.customerRef().isBlank()
                && !notice.customerRef().equals(order.get().customerId())) {
            LOG.warn("Pickup-ready notice for order {} names another customer; ignored", order.get().number());
            return Result.UNMATCHED;
        }
        boolean byShipment = order.get().shipments().stream().anyMatch(s -> s.id().equals(notice.orderRef()));
        List<Shipment> candidates = new ArrayList<>();
        for (Shipment s : order.get().shipments()) {
            boolean named = !byShipment || s.id().equals(notice.orderRef());
            boolean sameLocation = s.pickupLocationId() == null || notice.locationId() == null
                    || s.pickupLocationId().equals(notice.locationId());
            if (named && s.deliveryMethod() == DeliveryMethod.PICKUP && sameLocation) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            return Result.UNMATCHED;
        }
        String reason = "Ready for collection" + (notice.locationName() == null ? "" : " at " + notice.locationName());
        int applied = 0;
        int unchanged = 0;
        for (Shipment s : candidates) {
            try {
                ShipmentTransitions.Outcome outcome = transitions.apply(new ShipmentTransitions.Request(s.id(),
                        ShipmentStatus.READY, (o, sh) -> new TransitionActor(ActorKind.PICKUP_POINT,
                        notice.locationId(), null), reason, TransitionSource.CARRIER, "pickup-ready:" + eventId,
                        notice.readyAt(), null, null));
                if (outcome.applied()) {
                    applied++;
                } else {
                    unchanged++;
                }
            } catch (TransitionRefused refused) {
                LOG.info("Pickup-ready notice for shipment {} not applied: {}", s.id(), refused.getMessage());
            }
        }
        if (applied > 0) {
            return Result.APPLIED;
        }
        return unchanged > 0 ? Result.UNCHANGED : Result.REFUSED;
    }

    private Optional<Order> orderOf(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String r = ref.trim();
        if (isUuid(r)) {
            Optional<Order> byId = orders.findById(r);
            if (byId.isPresent()) {
                return byId;
            }
            return orders.orderIdOfShipment(r).flatMap(orders::findById);
        }
        return orders.findByNumber(r.toUpperCase(Locale.ROOT));
    }

    private Optional<String> match(Scan scan) {
        if (scan.reference() != null && isUuid(scan.reference()) && orders.orderIdOfShipment(scan.reference())
                .isPresent()) {
            return Optional.of(scan.reference());
        }
        if (scan.carrier() == null || scan.trackingNumber() == null) {
            return Optional.empty();
        }
        return orders.shipmentByTracking(scan.carrier().toLowerCase(Locale.ROOT), scan.trackingNumber());
    }

    static ShipmentStatus map(String state) {
        if (state == null) {
            return null;
        }
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "picked_up" -> ShipmentStatus.PICKED_UP;
            case "in_transit" -> ShipmentStatus.IN_TRANSIT;
            case "out_for_delivery" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "delivery_failed" -> ShipmentStatus.DELIVERY_FAILED;
            case "returned" -> ShipmentStatus.RETURNED;
            default -> null;
        };
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
