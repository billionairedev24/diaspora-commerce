package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.MissedDeliveryChoice;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class PreorderDeliveryServiceImpl implements PreorderDeliveryService {

    /** Actor id recorded on a refund the customer chose after a missed delivery. */
    static final String MISSED_DELIVERY_ACTOR = "system:preorder-missed-delivery";

    private static final Logger LOG = LoggerFactory.getLogger(PreorderDeliveryServiceImpl.class);

    private final OrdersAccess access;
    private final OrderRepository orders;
    private final ShipmentTransitions transitions;
    private final DomainEventPublisher events;
    private final OrderViews views;
    private final TransactionOperations tx;
    private final PreorderSettings preorderSettings;
    private final OrdersSettings settings;
    private final Clock clock;

    PreorderDeliveryServiceImpl(OrdersAccess access, OrderRepository orders, ShipmentTransitions transitions,
                                DomainEventPublisher events, OrderViews views, TransactionOperations tx,
                                PreorderSettings preorderSettings, OrdersSettings settings, Clock clock) {
        this.access = access;
        this.orders = orders;
        this.transitions = transitions;
        this.events = events;
        this.views = views;
        this.tx = tx;
        this.preorderSettings = preorderSettings;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public int flagMissedDeliveries() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.zone())));
        int flagged = 0;
        for (String shipmentId : orders.preorderShipmentsPastDue(today, preorderSettings.jobBatch())) {
            Boolean done = tx.execute(status -> flag(shipmentId));
            if (Boolean.TRUE.equals(done)) {
                flagged++;
            }
        }
        if (flagged > 0) {
            LOG.info("{} pre-order shipments missed their delivery date", flagged);
        }
        return flagged;
    }

    private boolean flag(String shipmentId) {
        Optional<Order> locked = orders.orderIdOfShipment(shipmentId).flatMap(orders::lockById);
        if (locked.isEmpty()) {
            return false;
        }
        Order order = locked.get();
        Shipment s = order.shipment(shipmentId);
        if (!s.preorderShipment() || s.preorder().deliveryMissed() || s.status().terminal()) {
            return false;
        }
        Instant now = Instant.now(clock);
        Shipment missed = s.withPreorder(s.preorder().missed(now));
        Order updated = order.withShipmentData(missed);
        orders.update(updated);
        events.publish(OrdersEvents.PREORDER_DELIVERY_MISSED, OrdersEvents.AGGREGATE, order.id(),
                OrdersEventPayloads.PreorderDeliveryMissed.of(updated, missed, now));
        return true;
    }

    @Override
    public OrderView answerMissedDelivery(String order, String shipmentId, MissedDeliveryChoice choice) {
        if (choice == null) {
            throw new OrdersRuleViolation("orders.invalid-request", "choice is REFUND or WAIT");
        }
        Actor customer = access.customer();
        Order found = find(order);
        access.ensureOwner(customer, found);
        return tx.execute(status -> {
            Order locked = orders.lockById(found.id()).orElseThrow();
            Shipment s = locked.shipment(shipmentId);
            if (!s.preorderShipment()) {
                throw new OrdersRuleViolation("orders.not-a-preorder", "Shipment " + shipmentId
                        + " is not a pre-order");
            }
            Instant now = Instant.now(clock);
            boolean alreadyAnswered = s.preorder().missedChoice() == choice;
            Shipment answered = s.withPreorder(s.preorder().answered(choice, now));
            Order current = locked.withShipmentData(answered);
            if (choice == MissedDeliveryChoice.REFUND && answered.status() != ShipmentStatus.CANCELLED) {
                // Cancelling the (captured) pre-order refunds it in full through the cancellation follow-up.
                ShipmentTransitions.Step step = transitions.step(current, answered, ShipmentStatus.CANCELLED,
                        new TransitionActor(ActorKind.PLATFORM_ADMIN, MISSED_DELIVERY_ACTOR, null),
                        "Pre-order delivery missed; the customer chose a refund", TransitionSource.API,
                        "missed-refund:" + shipmentId, null, null);
                current = step.order();
            }
            orders.update(current);
            if (!alreadyAnswered) {
                events.publish(OrdersEvents.PREORDER_MISSED_DELIVERY_ANSWERED, OrdersEvents.AGGREGATE, current.id(),
                        new OrdersEventPayloads.PreorderMissedDeliveryAnswered(current.id(), current.number(),
                                current.customerId(), shipmentId, answered.preorder().windowId(), choice.name(),
                                now));
            }
            Order view = current;
            return views.of(view, new Viewer(Viewer.Kind.CUSTOMER, null), orders.history(view.id()));
        });
    }

    private Order find(String order) {
        Optional<Order> byNumber = orders.findByNumber(order);
        if (byNumber.isPresent()) {
            return byNumber.get();
        }
        try {
            UUID.fromString(order);
        } catch (IllegalArgumentException e) {
            throw OrdersAccess.notFound(order);
        }
        return orders.findById(order).orElseThrow(() -> OrdersAccess.notFound(order));
    }
}
