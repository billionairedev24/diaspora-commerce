package com.afrotransact.orders.application;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.ActorKind;
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
